package com.espobase.client

import kotlinx.serialization.json.JsonElement

/** A function's answer. */
public class FunctionResponse internal constructor(
    public val body: ByteArray,
    public val status: Int,
    public val contentType: String?,
) {
    /** The answer as text. */
    public val text: String get() = String(body, Charsets.UTF_8)

    /** The answer parsed as JSON. */
    public fun json(): JsonElement = com.espobase.client.json.parseToJsonElement(text)

    /** Decodes a JSON answer into [T]. */
    public fun <T> decode(serializer: kotlinx.serialization.KSerializer<T>): T = com.espobase.client.json.decodeFromString(serializer, text)
}

/**
 * Edge functions: calls carry the project key and, when a user is signed in, their access token, so the function receives
 * the verified user (`x-espobase-user-id`, `x-espobase-user-email`, `x-espobase-user-role`).
 */
public class FunctionsClient internal constructor(private val http: Http, private val auth: AuthClient) {
    private suspend fun call(
        name: String,
        method: String,
        body: ByteArray?,
        contentType: String?,
        headers: Map<String, String>,
        query: Map<String, String>,
    ): FunctionResponse {
        if (!Regex("^[a-z0-9][a-z0-9-]{0,62}$").matches(name)) {
            throw EspobaseException.client("invalid_function", "functions.invoke(): a function name (lowercase, digits, -) is required.")
        }
        val result = http.send(
            "/functions/v1/$name", method, query.toList().sortedBy { it.first }, body, contentType, auth.accessToken(), headers,
        )
        return FunctionResponse(result.body, result.status, result.headers["content-type"])
    }

    /** Calls the function [name] with a JSON body. Error statuses throw an [EspobaseException] (the function's own JSON `{ error, message }` is used when present). */
    public suspend fun invoke(
        name: String,
        body: JsonElement? = null,
        method: String = "POST",
        headers: Map<String, String> = emptyMap(),
        query: Map<String, String> = emptyMap(),
    ): FunctionResponse = call(
        name, method, body?.let { json.encodeToString(JsonElement.serializer(), it).toByteArray() }, null, headers, query,
    )

    /** Calls the function [name] with raw bytes (for example text or an image). */
    public suspend fun invoke(
        name: String,
        bytes: ByteArray,
        contentType: String,
        method: String = "POST",
        headers: Map<String, String> = emptyMap(),
        query: Map<String, String> = emptyMap(),
    ): FunctionResponse = call(name, method, bytes, contentType, headers, query)
}
