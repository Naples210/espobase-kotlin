package com.espobase.client

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AuthTest {
    @Test
    fun signInKeepsTheSessionAttachesTheTokenAndEmitsEvents() = runTest(UnconfinedTestDispatcher()) {
        val (c, t) = client { if (it.path.endsWith("/auth/v1/token")) json(sessionJson()) else json(JsonArray(emptyList())) }
        val events = mutableListOf<AuthEvent>()
        val job = launch { c.auth.authStateChanges.collect { events += it.event } }
        val session = c.auth.signInWithPassword("a@b.co", "pw-123456")
        assertEquals("at1", session.accessToken)
        assertEquals("a@b.co", session.user.email)
        assertEquals("a@b.co", t.requests[0].json!!["email"].toString().trim('"'))
        c.from("orders").run()
        assertEquals("Bearer at1", t.requests.last().header("authorization"))
        assertEquals("u1", c.auth.currentUser()?.id)
        c.auth.signOut()
        assertEquals("/project/abcde12345/auth/v1/logout", t.requests.last().path)
        assertNull(c.auth.currentSession())
        assertEquals(listOf(AuthEvent.SIGNED_IN, AuthEvent.SIGNED_OUT), events)
        job.cancel()
    }

    @Test
    fun theSessionSurvivesARestartThroughTheStorage() = runTest {
        val storage = MemorySessionStorage()
        val (first, _) = client(configure = { ClientOptions(transport = it.transport, sessionStorage = storage) }) { json(sessionJson(1)) }
        first.auth.signInWithPassword("a@b.co", "pw")
        val (second, _) = client(configure = { ClientOptions(transport = it.transport, sessionStorage = storage) }) { json(sessionJson(1)) }
        assertEquals("at1", second.auth.currentSession()?.accessToken)
    }

    @Test
    fun anExpiringSessionIsRefreshedOnceEvenForConcurrentCallers() = runTest {
        var now = 1_800_000_000_000L
        val refreshes = AtomicInteger()
        val (c, t) = client(configure = { ClientOptions(transport = it.transport, now = { now }) }) {
            when {
                it.path.endsWith("/auth/v1/token") -> json(sessionJson(1, 60, now))
                it.path.endsWith("/auth/v1/refresh") -> { refreshes.incrementAndGet(); json(sessionJson(2, 900, now)) }
                else -> json(JsonArray(emptyList()))
            }
        }
        c.auth.signInWithPassword("a@b.co", "pw")
        now += 40_000 // inside the 30 s margin of a 60 s token
        val tokens = listOf(async { c.auth.accessToken() }, async { c.auth.accessToken() }).awaitAll()
        assertEquals(listOf("at2", "at2"), tokens)
        assertEquals(1, refreshes.get())
        assertEquals("rt1", t.requests.first { it.path.endsWith("/refresh") }.json!!["refresh_token"].toString().trim('"'))
    }

    @Test
    fun aRevokedRefreshTokenSignsTheUserOut() = runTest {
        var now = 1_800_000_000_000L
        val (c, _) = client(configure = { ClientOptions(transport = it.transport, now = { now }) }) {
            if (it.path.endsWith("/auth/v1/token")) json(sessionJson(1, 60, now))
            else json("""{"error":"invalid_grant","message":"Invalid refresh token."}""", 401)
        }
        c.auth.signInWithPassword("a@b.co", "pw")
        now += 45_000
        expectError("invalid_grant", 401) { c.auth.accessToken() }
        assertNull(c.auth.currentSession())
    }

    @Test
    fun signUpWithConfirmationReturnsNoSessionAndPassesTheAppLink() = runTest {
        val (c, t) = client { json(buildJsonObject { put("user", userJson()); put("session", JsonNull) }) }
        val result = c.auth.signUp("a@b.co", "pw-123456", redirectTo = "myapp://auth/callback")
        assertNotNull(result.user)
        assertNull(result.session)
        assertEquals("myapp://auth/callback", t.requests.single().json!!["redirect_to"].toString().trim('"'))
        assertNull(c.auth.currentSession())
    }

    @Test
    fun signInWithAppleSendsTheIdentityTokenAndTheRawNonce() = runTest {
        val (c, t) = client { json(sessionJson(3)) }
        val nonce = AppleNonce()
        val session = c.auth.signInWithIdToken("x".repeat(40), nonce.raw)
        assertEquals("at3", session.accessToken)
        assertEquals("/project/abcde12345/auth/v1/id-token", t.requests.single().path)
        val body = t.requests.single().json!!
        assertEquals("apple", body["provider"].toString().trim('"'))
        assertEquals(nonce.raw, body["nonce"].toString().trim('"'))
        assertTrue(body["nonce"].toString().trim('"') != nonce.hashed, "the raw nonce goes to Espobase, the hash to Apple")
        assertEquals("x".repeat(40), body["id_token"].toString().trim('"'))
    }

    @Test
    fun socialSignInBuildsTheAuthorizeUrlAndExchangesTheCodeWithThisDevicesVerifierOnce() = runTest {
        val (c, t) = client { json(sessionJson(4)) }
        val url = c.auth.signInWithOAuth(OAuthProvider.google, "myapp://auth/callback")
        val seen = Seen(HttpRequest("GET", url, emptyMap(), null))
        assertEquals("/project/abcde12345/auth/v1/authorize", seen.path)
        assertEquals("google", seen.query["provider"])
        assertEquals("S256", seen.query["code_challenge_method"])
        assertEquals(PUBLISHABLE_KEY, seen.query["apikey"])
        assertEquals("myapp://auth/callback", seen.query["redirect_to"])
        c.auth.exchangeCodeForSession("one-time-code")
        val verifier = t.requests.single().json!!["code_verifier"].toString().trim('"')
        assertEquals(seen.query["code_challenge"], pkceChallenge(verifier))
        expectError("no_code_verifier") { c.auth.exchangeCodeForSession("again") }
    }

    @Test
    fun twoStepSignInWaitsForTheSecondStepAndFinishesWithTheMfaToken() = runTest {
        val (c, t) = client {
            if (it.path.endsWith("/auth/v1/token")) {
                json(
                    """{"mfa_required":true,"enrollment_required":false,"mfa_token":"mfa-token-1",
                        "factors":[{"id":"f1","type":"totp"}],"recovery_codes":true}""",
                )
            } else json(sessionJson(5))
        }
        val error = try {
            c.auth.signInWithPassword("a@b.co", "pw")
            null
        } catch (e: EspobaseException) {
            e
        }
        assertEquals("mfa_required", error?.code)
        assertEquals(listOf(MfaPending.Factor("f1", MfaFactorType.totp)), error?.mfa?.factors)
        assertEquals(true, error?.mfa?.recoveryCodes)
        assertNull(c.auth.currentSession(), "no session until the second step")
        assertEquals("at5", c.auth.mfa.verify("123456").accessToken)
        assertEquals("mfa-token-1", t.requests.last().json!!["mfa_token"].toString().trim('"'))
        expectError("no_mfa_sign_in") { c.auth.mfa.verify("123456") }
    }

    @Test
    fun aPublishableKeyIsRequiredForAuthAndUpdateUserNeedsASession() = runTest {
        val (server, t) = client(key = SECRET_KEY, configure = { ClientOptions(transport = it.transport, serverSide = true) }) { json(sessionJson()) }
        expectError("secret_key") { server.auth.signInWithPassword("a@b.co", "pw") }
        assertTrue(t.requests.isEmpty())
        val (c, _) = client { json(buildJsonObject { put("user", userJson()) }) }
        expectError("no_session", 401) { c.auth.updateUser(password = "new-password-1") }
        assertTrue(JsonPrimitive("x").isString)
    }
}
