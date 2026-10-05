package com.espobase.client

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal const val SDK_VERSION: String = "0.1.0"

/** A request as the transport sees it. Header names are lowercase. */
public class HttpRequest(
    public val method: String,
    public val url: String,
    public val headers: Map<String, String>,
    public val body: ByteArray?,
)

public class HttpResponse(public val status: Int, public val headers: Map<String, String>, public val body: ByteArray)

/** How requests reach the network. The default is OkHttp; tests (and apps with their own stack) can provide another. */
public fun interface HttpTransport {
    public suspend fun send(request: HttpRequest): HttpResponse
}

/** The default transport: OkHttp. */
public class OkHttpTransport(private val client: OkHttpClient = OkHttpClient()) : HttpTransport {
    override suspend fun send(request: HttpRequest): HttpResponse = suspendCancellableCoroutine { continuation ->
        val builder = Request.Builder().url(request.url)
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        val contentType = request.headers["content-type"]?.toMediaTypeOrNull()
        val body = request.body?.toRequestBody(contentType)
            ?: if (request.method in setOf("POST", "PUT", "PATCH")) ByteArray(0).toRequestBody(null) else null
        builder.method(request.method, body)
        val call = client.newCall(builder.build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val headers = it.headers.toMultimap().mapKeys { e -> e.key.lowercase() }.mapValues { e -> e.value.last() }
                    continuation.resume(HttpResponse(it.code, headers, it.body?.bytes() ?: ByteArray(0)))
                }
            }
        })
    }
}

internal val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

internal class HttpResult(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
    val text: String get() = String(body, Charsets.UTF_8)
    val jsonOrNull: JsonElement? get() = if (body.isEmpty()) null else json.parseToJsonElement(text)
    val jsonObject: JsonObject
        get() = (jsonOrNull as? JsonObject) ?: throw EspobaseException.client("decode_error", "The answer was not a JSON object.")
}

/** Low-level HTTP: adds the API key, the user's token when given, JSON bodies and normalized errors. */
internal class Http(
    val base: String,
    val key: String,
    private val transport: HttpTransport,
    private val headers: Map<String, String>,
) {
    fun url(path: String, query: List<Pair<String, String>> = emptyList()): String {
        val parsed = (base + path).toHttpUrlOrNull() ?: throw EspobaseException.client("invalid_url", "Invalid URL: $path")
        if (query.isEmpty()) return parsed.toString()
        val builder = parsed.newBuilder()
        query.forEach { (name, value) -> builder.addQueryParameter(name, value) }
        return builder.build().toString()
    }

    /** Non-2xx answers throw an [EspobaseException] (Espobase's `{error, message}` or PostgREST's `{code, message, details, hint}`). */
    suspend fun send(
        path: String,
        method: String = "GET",
        query: List<Pair<String, String>> = emptyList(),
        body: ByteArray? = null,
        contentType: String? = null,
        token: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): HttpResult {
        val all = linkedMapOf("x-client-info" to "espobase-kotlin/$SDK_VERSION")
        headers.forEach { (k, v) -> all[k.lowercase()] = v }
        extraHeaders.forEach { (k, v) -> all[k.lowercase()] = v }
        all["apikey"] = key
        if (token != null) all["authorization"] = "Bearer $token"
        if (body != null) all["content-type"] = contentType ?: "application/json"
        val response = try {
            transport.send(HttpRequest(method, url(path, query), all, body))
        } catch (e: EspobaseException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw EspobaseException(0, "network_error", e.message ?: "Network error")
        }
        val result = HttpResult(response.status, response.headers, response.body)
        if (response.status !in 200..299) throw errorFrom(result)
        return result
    }

    suspend fun sendJson(
        path: String,
        method: String = "POST",
        body: JsonElement? = null,
        token: String? = null,
        query: List<Pair<String, String>> = emptyList(),
    ): HttpResult = send(path, method, query, body?.let { json.encodeToString(JsonElement.serializer(), it).toByteArray() }, null, token)

    companion object {
        fun errorFrom(result: HttpResult): EspobaseException {
            val body = try {
                result.jsonOrNull as? JsonObject
            } catch (_: Exception) {
                null
            }
            fun field(name: String) = (body?.get(name) as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
            return EspobaseException(
                status = result.status,
                code = field("error") ?: field("code") ?: "http_error",
                message = field("message") ?: "Request failed (${result.status})",
                details = field("details"),
                hint = field("hint"),
            )
        }
    }
}

internal fun JsonObject.string(name: String): String? = get(name)?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
