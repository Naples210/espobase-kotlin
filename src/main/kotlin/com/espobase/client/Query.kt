package com.espobase.client

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import java.time.Instant

private fun text(value: Any): String = when (value) {
    is Instant -> value.toString()
    else -> value.toString()
}

private fun listItem(value: Any): String {
    if (value is Number) return value.toString()
    return "\"" + text(value).replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

private fun hasControl(s: String): Boolean = s.isEmpty() || s.any { it.code < 0x20 || it.code == 0x7f }

/**
 * A query on one table or view of the project's REST API. Nothing is sent until you call [execute], [single] or [run]:
 *
 * ```kotlin
 * val open: List<Order> = espobase.from("orders").select("id,total").eq("status", "open").order("id").limit(10).execute()
 * ```
 *
 * `update()` and `delete()` refuse to run without at least one filter, so a missing `.eq()` can't change every row by
 * accident (row level security still decides which rows a request may touch). Every method returns a new query, so a base
 * query can be reused. Types you decode into must be `@Serializable`.
 */
public class QueryBuilder internal constructor(
    private val http: Http,
    private val auth: AuthClient,
    private val table: String,
    private val method: String = "GET",
    private val body: JsonElement? = null,
    private val params: List<Pair<String, String>> = emptyList(),
    private val filters: Int = 0,
    private val returnRows: Boolean = false,
    private val invalid: String? = null,
) {
    private fun copy(
        method: String = this.method,
        body: JsonElement? = this.body,
        params: List<Pair<String, String>> = this.params,
        filters: Int = this.filters,
        returnRows: Boolean = this.returnRows,
        invalid: String? = this.invalid,
    ) = QueryBuilder(http, auth, table, method, body, params, filters, returnRows, invalid)

    /** Columns to return (PostgREST select syntax, e.g. `id,name,orders(total)`). On writes: return the rows. */
    public fun select(columns: String = "*"): QueryBuilder =
        copy(params = params + ("select" to columns), returnRows = returnRows || method != "GET")

    /** Inserts a row, or several (a `JsonArray`). Use `Json.encodeToJsonElement(value)` for your own types. */
    public fun insert(values: JsonElement): QueryBuilder = copy(method = "POST", body = values)

    public fun update(values: JsonObject): QueryBuilder = copy(method = "PATCH", body = values)

    public fun delete(): QueryBuilder = copy(method = "DELETE")

    private fun filter(column: String, op: String, value: String): QueryBuilder =
        if (hasControl(column)) copy(invalid = "Invalid column name.")
        else copy(params = params + (column to "$op.$value"), filters = filters + 1)

    public fun eq(column: String, value: Any): QueryBuilder = filter(column, "eq", text(value))
    public fun neq(column: String, value: Any): QueryBuilder = filter(column, "neq", text(value))
    public fun gt(column: String, value: Any): QueryBuilder = filter(column, "gt", text(value))
    public fun gte(column: String, value: Any): QueryBuilder = filter(column, "gte", text(value))
    public fun lt(column: String, value: Any): QueryBuilder = filter(column, "lt", text(value))
    public fun lte(column: String, value: Any): QueryBuilder = filter(column, "lte", text(value))
    public fun like(column: String, pattern: String): QueryBuilder = filter(column, "like", pattern)
    public fun ilike(column: String, pattern: String): QueryBuilder = filter(column, "ilike", pattern)
    public fun inList(column: String, values: List<Any>): QueryBuilder =
        filter(column, "in", values.joinToString(",", "(", ")") { listItem(it) })

    /** Matches NULL. */
    public fun isNull(column: String): QueryBuilder = filter(column, "is", "null")

    /** Matches a boolean column. */
    public fun isBool(column: String, value: Boolean): QueryBuilder = filter(column, "is", value.toString())

    public fun order(column: String, ascending: Boolean = true, nullsFirst: Boolean? = null): QueryBuilder {
        if (hasControl(column)) return copy(invalid = "Invalid column name.")
        var value = "$column.${if (ascending) "asc" else "desc"}"
        if (nullsFirst != null) value += if (nullsFirst) ".nullsfirst" else ".nullslast"
        return copy(params = params + ("order" to value))
    }

    public fun limit(count: Int): QueryBuilder =
        if (count < 0) copy(invalid = "limit must be a non-negative integer.") else copy(params = params + ("limit" to count.toString()))

    /** Rows [from] to [to], inclusive and zero-based. */
    public fun range(from: Int, to: Int): QueryBuilder =
        if (from < 0 || to < from) copy(invalid = "range needs 0 ≤ from ≤ to.")
        else copy(params = params + ("offset" to from.toString()) + ("limit" to (to - from + 1).toString()))

    private suspend fun send(single: Boolean): HttpResult {
        if (invalid != null) throw EspobaseException.client("invalid_query", invalid)
        if ((method == "PATCH" || method == "DELETE") && filters == 0) {
            throw EspobaseException.client(
                "filter_required",
                "Add a filter (for example .eq(\"id\", 1)) before ${if (method == "PATCH") "update" else "delete"}().",
            )
        }
        val headers = buildMap {
            if (method != "GET") put("prefer", if (returnRows) "return=representation" else "return=minimal")
            if (single) put("accept", "application/vnd.pgrst.object+json")
        }
        return http.send(
            "/rest/v1/" + java.net.URLEncoder.encode(table, "UTF-8").replace("+", "%20"),
            method,
            params,
            body?.let { json.encodeToString(JsonElement.serializer(), it).toByteArray() },
            null,
            auth.accessToken(),
            headers,
        )
    }

    /** Runs the query and returns the rows as JSON. */
    public suspend fun executeJson(): JsonArray {
        val answer = send(false).jsonOrNull ?: return JsonArray(emptyList())
        return answer as? JsonArray ?: JsonArray(listOf(answer))
    }

    /** Runs the query and decodes the rows into [T] (use `List<Row>` for [T]). */
    public suspend fun <T> execute(serializer: KSerializer<T>): T {
        val result = send(false)
        val answer = result.jsonOrNull ?: JsonArray(emptyList())
        return decode(serializer, answer)
    }

    /** Runs the query and decodes exactly one row: an error if there are zero or several. */
    public suspend fun <T> single(serializer: KSerializer<T>): T =
        decode(serializer, send(true).jsonOrNull ?: throw EspobaseException.client("decode_error", "The answer was empty."))

    /** Runs a write (insert / update / delete) when you don't need the rows back. */
    public suspend fun run() {
        send(false)
    }

    private fun <T> decode(serializer: KSerializer<T>, element: JsonElement): T = try {
        json.decodeFromJsonElement(serializer, element)
    } catch (e: Exception) {
        throw EspobaseException.client("decode_error", "The answer didn't match the expected type: ${e.message}")
    }
}

/** Runs the query and decodes the rows, inferring the type: `val todos: List<Todo> = query.execute()`. */
public suspend inline fun <reified T> QueryBuilder.execute(): T = execute(serializer<T>())

/** Runs the query and decodes exactly one row, inferring the type. */
public suspend inline fun <reified T> QueryBuilder.single(): T = single(serializer<T>())
