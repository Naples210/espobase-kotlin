package com.espobase.client

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Options for [EspobaseClient]. Every field has a default. */
public class ClientOptions(
    /** Where the session is kept. Default: memory (use encrypted storage in an app to stay signed in). */
    public val sessionStorage: SessionStorage = MemorySessionStorage(),
    /** Refresh the access token shortly before it expires (default: true). */
    public val autoRefreshToken: Boolean = true,
    /** Extra headers on every request (never put keys here). */
    public val headers: Map<String, String> = emptyMap(),
    /** Another way to reach the network (default: OkHttp). */
    public val transport: HttpTransport = OkHttpTransport(),
    /** Allow a secret key (`eb_sk_…`): only for trusted servers, never in an app. */
    public val serverSide: Boolean = false,
    /** The clock in milliseconds (tests). */
    public val now: () -> Long = { System.currentTimeMillis() },
)

/**
 * The Espobase client: your project's database ([from], [rpc]), App Authentication ([auth]), Storage ([storage]) and edge
 * functions ([functions]).
 *
 * ```kotlin
 * val espobase = EspobaseClient("https://api.espobase.com/project/<ref>", "eb_pk_…")
 * ```
 */
public class EspobaseClient(url: String, key: String, options: ClientOptions = ClientOptions()) {
    private val http: Http
    public val auth: AuthClient
    public val storage: StorageClient
    public val functions: FunctionsClient

    init {
        val parsed = url.toHttpUrlOrNull()
        if (parsed == null || (parsed.scheme != "https" && parsed.scheme != "http")) {
            throw EspobaseException.client("invalid_url", "The project URL must be an absolute http(s) URL.")
        }
        if (!Regex("^eb_(pk|sk)_[0-9A-Za-z]{43}$").matches(key)) {
            throw EspobaseException.client("invalid_key", "That doesn't look like an Espobase API key (eb_pk_… or eb_sk_…).")
        }
        val secret = key.startsWith("eb_sk_")
        if (secret && !options.serverSide) {
            throw EspobaseException.client(
                "secret_key",
                "Espobase secret keys bypass row level security and must never be used in an app. Use the publishable key.",
            )
        }
        var base = parsed.newBuilder().query(null).fragment(null).build().toString().trimEnd('/')
        if (base.endsWith("/rest/v1")) base = base.removeSuffix("/rest/v1")
        http = Http(base, key, options.transport, options.headers)
        auth = AuthClient(http, options.sessionStorage, secret, options.autoRefreshToken, options.now)
        storage = StorageClient(http, auth)
        functions = FunctionsClient(http, auth)
    }

    /** A query on a table or view. */
    public fun from(table: String): QueryBuilder {
        if (table.isEmpty()) throw EspobaseException.client("invalid_table", "from(): a table name is required.")
        return QueryBuilder(http, auth, table)
    }

    /**
     * Calls a database function in your project (`POST /rest/v1/rpc/<fn>`), as the signed-in user, so row level security
     * applies. Returns the decoded JSON.
     */
    public suspend fun rpc(function: String, params: JsonObject = JsonObject(emptyMap())): JsonElement? {
        if (!Regex("^[A-Za-z_][A-Za-z0-9_]{0,62}$").matches(function)) {
            throw EspobaseException.client("invalid_function", "rpc(): a function name is required.")
        }
        return http.sendJson("/rest/v1/rpc/$function", "POST", params, auth.accessToken()).jsonOrNull
    }

    /** Like [rpc], decoded into [T]. */
    public suspend fun <T> rpc(function: String, serializer: KSerializer<T>, params: JsonObject = JsonObject(emptyMap())): T {
        val answer = rpc(function, params) ?: throw EspobaseException.client("decode_error", "The answer was empty.")
        return json.decodeFromJsonElement(serializer, answer)
    }

    /** Like [rpc], inferring the type. */
    public suspend inline fun <reified T> rpcAs(function: String, params: JsonObject = JsonObject(emptyMap())): T =
        rpc(function, serializer<T>(), params)
}
