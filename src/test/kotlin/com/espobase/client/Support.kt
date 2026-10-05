package com.espobase.client

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

const val PROJECT_URL = "https://api.espobase.test/project/abcde12345"
val PUBLISHABLE_KEY = "eb_pk_" + "a".repeat(43)
val SECRET_KEY = "eb_sk_" + "b".repeat(43)

/** A request as the transport saw it. */
class Seen(val request: HttpRequest) {
    val method get() = request.method
    val url get() = request.url
    val path: String get() = java.net.URI(request.url).path
    val query: Map<String, String> get() =
        (java.net.URI(request.url).rawQuery ?: "").split("&").filter { it.isNotEmpty() }.associate {
            val (k, v) = it.split("=", limit = 2).let { p -> p[0] to p.getOrElse(1) { "" } }
            java.net.URLDecoder.decode(k, "UTF-8") to java.net.URLDecoder.decode(v, "UTF-8")
        }
    val rawQuery: String get() = java.net.URI(request.url).rawQuery ?: ""
    fun header(name: String): String? = request.headers[name.lowercase()]
    val bodyText: String? get() = request.body?.toString(Charsets.UTF_8)
    val json: JsonObject? get() = bodyText?.let { Json.parseToJsonElement(it) as? JsonObject }
}

class Answer(val status: Int = 200, val body: ByteArray = ByteArray(0), val headers: Map<String, String> = emptyMap())

fun json(element: JsonElement, status: Int = 200) =
    Answer(status, element.toString().toByteArray(), mapOf("content-type" to "application/json"))

fun json(text: String, status: Int = 200) = Answer(status, text.toByteArray(), mapOf("content-type" to "application/json"))

/** Answers requests from a script and remembers them. */
class MockTransport(private val route: (Seen) -> Answer) : HttpTransport {
    val requests = mutableListOf<Seen>()
    override suspend fun send(request: HttpRequest): HttpResponse {
        val seen = Seen(request)
        requests += seen
        val answer = route(seen)
        return HttpResponse(answer.status, answer.headers, answer.body)
    }
}

fun sessionJson(n: Int = 1, expiresIn: Int = 900, nowMillis: Long = System.currentTimeMillis(), userId: String = "u1"): JsonObject =
    buildJsonObject {
        put("access_token", "at$n")
        put("refresh_token", "rt$n")
        put("token_type", "bearer")
        put("expires_in", expiresIn)
        put("expires_at", nowMillis / 1000 + expiresIn)
        put("user", userJson(userId))
    }

fun userJson(userId: String = "u1"): JsonObject = buildJsonObject {
    put("id", userId)
    put("email", "a@b.co")
    put("emailVerified", true)
    put("appRole", "authenticated")
    put("provider", "email")
    put("providers", JsonArray(listOf(JsonPrimitive("email"))))
    put("createdAt", "2026-10-01T00:00:00Z")
    put("lastSignInAt", JsonNull)
}

fun client(
    key: String = PUBLISHABLE_KEY,
    url: String = PROJECT_URL,
    configure: (ClientOptions) -> ClientOptions = { it },
    route: (Seen) -> Answer,
): Pair<EspobaseClient, MockTransport> {
    val transport = MockTransport(route)
    val options = configure(ClientOptions(transport = transport))
    return EspobaseClient(url, key, options) to transport
}

suspend fun expectError(code: String, status: Int? = null, block: suspend () -> Unit) {
    val error = try {
        block()
        null
    } catch (e: EspobaseException) {
        e
    }
    assertEquals(code, error?.code ?: "no error was thrown")
    if (status != null) assertEquals(status, error?.status)
}

inline fun <reified T : Throwable> expectThrows(block: () -> Unit): T = assertFailsWith<T> { block() }
