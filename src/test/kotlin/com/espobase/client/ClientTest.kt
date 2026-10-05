package com.espobase.client

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CryptoTest {
    @Test
    fun pkceChallengeIsTheS256OfTheVerifier() {
        // RFC 7636 appendix B.
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", pkceChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val pkce = Pkce.generate()
        assertEquals(43, pkce.verifier.length)
        assertEquals(pkceChallenge(pkce.verifier), pkce.challenge)
        assertTrue(Pkce.generate().verifier != pkce.verifier)
    }

    @Test
    fun appleNonceHashesTheRawValueAsLowercaseHex() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", AppleNonce("abc").hashed)
        val random = AppleNonce()
        assertTrue(random.raw.length >= 32)
        assertEquals(64, random.hashed.length)
        assertTrue(AppleNonce().raw != random.raw)
    }
}

class ClientTest {
    @Test
    fun refusesBadUrlsBadKeysAndSecretKeysInAnApp() {
        assertEquals("invalid_url", expectThrows<EspobaseException> { EspobaseClient("ftp://x.test", PUBLISHABLE_KEY) }.code)
        assertEquals("invalid_key", expectThrows<EspobaseException> { EspobaseClient(PROJECT_URL, "nope") }.code)
        assertEquals("secret_key", expectThrows<EspobaseException> { EspobaseClient(PROJECT_URL, SECRET_KEY) }.code)
        EspobaseClient(PROJECT_URL, SECRET_KEY, ClientOptions(serverSide = true))
    }

    @Test
    fun acceptsAUrlEndingInRestV1OrASlashAndSendsTheKeyAndClientInfo() = runTest {
        for (text in listOf("$PROJECT_URL/rest/v1/", "$PROJECT_URL/", "$PROJECT_URL?x=1")) {
            val (c, t) = client(url = text, configure = { ClientOptions(transport = it.transport, headers = mapOf("x-extra" to "1")) }) {
                json(JsonArray(emptyList()))
            }
            c.from("t").run()
            val seen = t.requests.single()
            assertEquals("$PROJECT_URL/rest/v1/t", seen.url)
            assertEquals(PUBLISHABLE_KEY, seen.header("apikey"))
            assertTrue(seen.header("x-client-info")!!.startsWith("espobase-kotlin/"))
            assertEquals("1", seen.header("x-extra"))
            assertNull(seen.header("authorization"), "anonymous until signed in")
        }
    }

    @Test
    fun normalizesErrorsAndNetworkFailures() = runTest {
        val (c, _) = client { json("""{"code":"PGRST205","message":"no table","hint":"check"}""", 404) }
        try {
            c.from("missing").run()
        } catch (e: EspobaseException) {
            assertEquals(404, e.status)
            assertEquals("PGRST205", e.code)
            assertEquals("check", e.hint)
        }
        val down = EspobaseClient(PROJECT_URL, PUBLISHABLE_KEY, ClientOptions(transport = HttpTransport { throw java.io.IOException("offline") }))
        expectError("network_error", 0) { down.from("t").run() }
    }

    @Test
    fun rpcSendsTheArgumentsWithTheUsersTokenAndValidatesTheName() = runTest {
        val (c, t) = client { if (it.path.endsWith("/auth/v1/token")) json(sessionJson()) else json("""[{"id":7}]""") }
        c.auth.signInWithPassword("a@b.co", "pw")
        val rows = c.rpc("match_documents", buildJsonObject { put("match_count", 5) })
        assertEquals("""[{"id":7}]""", rows.toString())
        val call = t.requests.last()
        assertEquals("/project/abcde12345/rest/v1/rpc/match_documents", call.path)
        assertEquals("Bearer at1", call.header("authorization"))
        assertEquals("5", call.json!!["match_count"].toString())
        expectError("invalid_function") { c.rpc("bad name!") }
        @Serializable
        data class Row(val id: Int)
        assertEquals(listOf(Row(7)), c.rpcAs<List<Row>>("match_documents"))
    }
}
