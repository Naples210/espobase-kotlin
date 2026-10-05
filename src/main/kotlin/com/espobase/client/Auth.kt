package com.espobase.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Where the signed-in session is kept between launches. On Android, back it with `EncryptedSharedPreferences` (or the
 * Android Keystore); the default keeps it in memory only.
 */
public interface SessionStorage {
    public suspend fun get(key: String): String?
    public suspend fun set(key: String, value: String)
    public suspend fun remove(key: String)
}

public class MemorySessionStorage : SessionStorage {
    private val values = HashMap<String, String>()
    private val lock = Mutex()
    override suspend fun get(key: String): String? = lock.withLock { values[key] }
    override suspend fun set(key: String, value: String): Unit = lock.withLock { values[key] = value }
    override suspend fun remove(key: String): Unit = lock.withLock { values.remove(key) }.let { }
}

private fun body(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
    for ((name, value) in pairs) {
        when (value) {
            null -> Unit
            is String -> put(name, value)
            is Boolean -> put(name, value)
            is Number -> put(name, value)
            else -> error("unsupported body value for $name")
        }
    }
}

/**
 * Espobase App Authentication for your app's users (sign-up, sign-in, sessions). Access tokens are refreshed for you:
 * requests made through `from()`, `storage`, `functions` and `rpc` carry the signed-in user's token.
 */
public class AuthClient internal constructor(
    private val http: Http,
    private val storage: SessionStorage,
    private val secretKey: Boolean,
    private val autoRefresh: Boolean,
    private val now: () -> Long,
) {
    private val stateLock = Mutex()
    private var session: Session? = null
    private var loaded = false
    private var inFlight: CompletableDeferred<Session>? = null
    internal var pendingMfa: String? = null
    private val storageKey = "espobase.auth." + (java.net.URI(http.base).path ?: "").replace(Regex("[^A-Za-z0-9_]+"), "_")
    private val changes = MutableSharedFlow<AuthStateChange>(extraBufferCapacity = 64)

    /** Two-step sign-in: finish a waiting sign-in, set up and remove methods. */
    public val mfa: MfaClient = MfaClient(this, http)

    /** Sign-in, sign-out and refresh events. */
    public val authStateChanges: SharedFlow<AuthStateChange> = changes.asSharedFlow()

    private fun guardKey() {
        if (secretKey) throw EspobaseException.client("secret_key", "Use a publishable key for App Authentication.")
    }

    private suspend fun load() {
        if (loaded) return
        loaded = true
        val text = storage.get(storageKey) ?: return
        session = try {
            json.decodeFromString(Session.serializer(), text)
        } catch (_: Exception) {
            storage.remove(storageKey) // unreadable: start signed out
            null
        }
    }

    private suspend fun save(next: Session, event: AuthEvent) {
        session = next
        loaded = true
        storage.set(storageKey, json.encodeToString(Session.serializer(), next))
        changes.tryEmit(AuthStateChange(event, next))
    }

    private suspend fun clear(event: AuthEvent) {
        session = null
        loaded = true
        storage.remove(storageKey)
        changes.tryEmit(AuthStateChange(event, null))
    }

    /** The current session (refreshed first when it is about to expire), or null when signed out. */
    public suspend fun currentSession(): Session? {
        val current = stateLock.withLock { load(); session } ?: return null
        if (autoRefresh && current.expiresAt - REFRESH_MARGIN_SECONDS <= now() / 1000) return refreshSession()
        return current
    }

    /** The signed-in user (from the stored session; null when signed out). */
    public suspend fun currentUser(): User? = currentSession()?.user

    /** Access token for API calls (null when signed out). */
    public suspend fun accessToken(): String? = currentSession()?.accessToken

    /**
     * Turns a sign-in answer into a session, or throws `mfa_required` / `mfa_enrollment_required` when the second step is
     * still waiting (then finish it with `auth.mfa.verify`).
     */
    internal suspend fun finishSignIn(answer: JsonObject, status: Int): Session {
        if ((answer["mfa_required"] as? JsonPrimitive)?.booleanOrNull() == true && answer.string("mfa_token") != null) {
            pendingMfa = answer.string("mfa_token")
            val enrollment = (answer["enrollment_required"] as? JsonPrimitive)?.booleanOrNull() == true
            throw EspobaseException(
                status,
                if (enrollment) "mfa_enrollment_required" else "mfa_required",
                if (enrollment) "Set up two-step sign-in to finish signing in." else "Enter the code from your second sign-in step.",
                mfa = MfaPending(
                    enrollmentRequired = enrollment,
                    factors = (answer["factors"] as? JsonArray).orEmpty().map {
                        MfaPending.Factor(it.jsonObject.string("id")!!, MfaFactorType.valueOf(it.jsonObject.string("type")!!))
                    },
                    recoveryCodes = (answer["recovery_codes"] as? JsonPrimitive)?.booleanOrNull() == true,
                ),
            )
        }
        val signedIn = json.decodeFromJsonElement(Session.serializer(), answer)
        pendingMfa = null
        stateLock.withLock { save(signedIn, AuthEvent.SIGNED_IN) }
        return signedIn
    }

    private suspend fun post(path: String, vararg pairs: Pair<String, Any?>): Session {
        val result = http.sendJson(path, "POST", body(*pairs))
        return finishSignIn(result.jsonObject, result.status)
    }

    /**
     * Creates an account. With email confirmation on (the default) the user gets a link and no session yet. [redirectTo] is
     * where the link opens: an https URL or an app link such as `myapp://auth/callback`, listed under Authentication ›
     * Settings › Allowed redirect URLs.
     */
    public suspend fun signUp(email: String, password: String, redirectTo: String? = null): SignUpResult {
        guardKey()
        val result = http.sendJson(
            "/auth/v1/signup", "POST", body("email" to email, "password" to password, "redirect_to" to redirectTo),
        )
        val answer = result.jsonObject
        if ((answer["mfa_required"] as? JsonPrimitive)?.booleanOrNull() == true) finishSignIn(answer, result.status) // throws mfa_*
        val sessionJson = answer["session"] as? JsonObject
        val session = sessionJson?.let { json.decodeFromJsonElement(Session.serializer(), it) }
        if (session != null) stateLock.withLock { save(session, AuthEvent.SIGNED_IN) }
        val user = (answer["user"] as? JsonObject)?.let { json.decodeFromJsonElement(User.serializer(), it) }
        return SignUpResult(user, session)
    }

    public suspend fun signInWithPassword(email: String, password: String): Session {
        guardKey()
        return post("/auth/v1/token", "email" to email, "password" to password)
    }

    /**
     * Native sign-in sheets: signs in with the identity token an app got from the provider (today: Sign in with Apple).
     * Give Apple `AppleNonce().hashed` and pass `AppleNonce().raw` here. The token's audience must be your app's bundle ID or
     * Services ID (add it under Authentication › Providers › Apple).
     */
    public suspend fun signInWithIdToken(token: String, nonce: String, provider: OAuthProvider = OAuthProvider.apple): Session {
        guardKey()
        return post("/auth/v1/id-token", "provider" to provider.name, "id_token" to token, "nonce" to nonce)
    }

    /**
     * Social sign-in with a web page (the provider must be on for the project). Open the returned URL in a Custom Tab (or
     * your platform's web-auth session) with your app's callback scheme; the user comes back to [redirectTo] with
     * `?code=…` (or `?error=…`): pass that code to [exchangeCodeForSession]. The one-time secret stays on this device.
     */
    public suspend fun signInWithOAuth(provider: OAuthProvider, redirectTo: String? = null): String {
        guardKey()
        val pkce = Pkce.generate()
        storage.set("$storageKey.code_verifier", pkce.verifier)
        val query = buildList {
            add("provider" to provider.name)
            add("code_challenge" to pkce.challenge)
            add("code_challenge_method" to "S256")
            add("apikey" to http.key)
            if (redirectTo != null) add("redirect_to" to redirectTo)
        }
        return http.url("/auth/v1/authorize", query)
    }

    /** Finishes a social sign-in: the `code` from the redirect URL plus this device's PKCE verifier. */
    public suspend fun exchangeCodeForSession(code: String): Session {
        guardKey()
        val key = "$storageKey.code_verifier"
        val verifier = storage.get(key)
            ?: throw EspobaseException.client("no_code_verifier", "This sign-in wasn't started on this device. Start it again.")
        try {
            return post("/auth/v1/exchange", "auth_code" to code, "code_verifier" to verifier)
        } finally {
            storage.remove(key) // one attempt per sign-in
        }
    }

    /** Confirms an email address with the token from the verification link, and signs the user in. */
    public suspend fun verifyEmail(token: String): Session = post("/auth/v1/verify", "type" to "signup", "token" to token)

    /** Passwordless sign-in (turned on per project): emails a link and a 6-digit code. */
    public suspend fun signInWithOtp(email: String, redirectTo: String? = null, shouldCreateUser: Boolean = true) {
        guardKey()
        http.sendJson("/auth/v1/otp", "POST", body("email" to email, "redirect_to" to redirectTo, "create_user" to shouldCreateUser))
    }

    /** Finishes a passwordless sign-in with the emailed code. */
    public suspend fun verifyOtpCode(email: String, code: String): Session =
        post("/auth/v1/verify", "type" to "email", "email" to email, "code" to code)

    /** Finishes a passwordless sign-in with the link's token. */
    public suspend fun verifyOtpToken(token: String): Session = post("/auth/v1/verify", "type" to "magiclink", "token" to token)

    /** Ends the session on this device ([global]: on every device). The local session is cleared even when the network call fails. */
    public suspend fun signOut(global: Boolean = false) {
        val token = stateLock.withLock { load(); session?.accessToken }
        var failure: Exception? = null
        if (token != null) {
            try {
                http.sendJson("/auth/v1/logout", "POST", body("scope" to if (global) "global" else "local"), token)
            } catch (e: EspobaseException) {
                failure = e
            }
        }
        stateLock.withLock { clear(AuthEvent.SIGNED_OUT) }
        if (failure != null && (failure as EspobaseException).status != 401) throw failure
    }

    /** Swaps the refresh token for a new session. Concurrent callers share one request. */
    public suspend fun refreshSession(): Session {
        var owner = false
        val running = stateLock.withLock {
            inFlight ?: CompletableDeferred<Session>().also {
                inFlight = it
                owner = true
            }
        }
        if (!owner) return running.await()
        try {
            val current = stateLock.withLock { load(); session } ?: throw EspobaseException.notSignedIn
            val next = try {
                val result = http.sendJson("/auth/v1/refresh", "POST", body("refresh_token" to current.refreshToken))
                json.decodeFromJsonElement(Session.serializer(), result.jsonObject)
            } catch (e: EspobaseException) {
                // The refresh token is spent or revoked: the user must sign in again.
                if (e.status == 400 || e.status == 401) stateLock.withLock { clear(AuthEvent.SIGNED_OUT) }
                throw e
            }
            stateLock.withLock { save(next, AuthEvent.TOKEN_REFRESHED) }
            running.complete(next)
            return next
        } catch (e: Throwable) {
            running.completeExceptionally(e)
            throw e
        } finally {
            stateLock.withLock { inFlight = null }
        }
    }

    public suspend fun resetPasswordForEmail(email: String, redirectTo: String? = null) {
        guardKey()
        http.sendJson("/auth/v1/recover", "POST", body("email" to email, "redirect_to" to redirectTo))
    }

    /** Completes a password reset with the token from the reset link. */
    public suspend fun resetPassword(token: String, password: String) {
        http.sendJson("/auth/v1/reset-password", "POST", body("token" to token, "password" to password))
    }

    /** Sets a new password and/or asks to change the email address (when the project allows it). */
    public suspend fun updateUser(
        password: String? = null,
        email: String? = null,
        currentPassword: String? = null,
        redirectTo: String? = null,
    ): User {
        val token = accessToken() ?: throw EspobaseException.notSignedIn
        val result = http.sendJson(
            "/auth/v1/user", "PUT",
            body("password" to password, "email" to email, "current_password" to currentPassword, "redirect_to" to redirectTo),
            token,
        )
        val user = json.decodeFromJsonElement(User.serializer(), result.jsonObject.getValue("user"))
        stateLock.withLock { session?.let { save(it.copy(user = user), AuthEvent.USER_UPDATED) } }
        return user
    }

    /** Confirms an email change with the token from the link sent to the new address. */
    public suspend fun verifyEmailChange(token: String): User {
        val result = http.sendJson("/auth/v1/verify", "POST", body("type" to "email_change", "token" to token))
        val user = json.decodeFromJsonElement(User.serializer(), result.jsonObject.getValue("user"))
        stateLock.withLock {
            val current = session
            if (current != null && current.user.id == user.id) save(current.copy(user = user), AuthEvent.USER_UPDATED)
        }
        return user
    }

    private companion object {
        const val REFRESH_MARGIN_SECONDS = 30L
    }
}

private fun JsonPrimitive.booleanOrNull(): Boolean? = runCatching { boolean }.getOrNull()

/**
 * Two-step sign-in (turned on per project). After `mfa_required`, [verify] finishes the sign-in with an authenticator code,
 * an emailed code ([challenge] sends it) or a recovery code.
 */
public class MfaClient internal constructor(private val auth: AuthClient, private val http: Http) {
    private val noPending =
        EspobaseException.client("no_mfa_sign_in", "No sign-in is waiting for a second step. Sign in again.")

    /** Finishes the waiting sign-in. */
    public suspend fun verify(code: String, factorId: String? = null): Session {
        val mfaToken = auth.pendingMfa ?: throw noPending
        try {
            val result = http.sendJson(
                "/auth/v1/mfa-verify", "POST", body("mfa_token" to mfaToken, "code" to code, "factor_id" to factorId),
            )
            return auth.finishSignIn(result.jsonObject, result.status)
        } catch (e: EspobaseException) {
            if (e.code == "invalid_mfa_token") auth.pendingMfa = null
            throw e
        }
    }

    private suspend fun tokenUnlessWaiting(): String? {
        if (auth.pendingMfa != null) return null
        return auth.accessToken() ?: throw EspobaseException.notSignedIn
    }

    /** Emails a code for the email method: for the waiting sign-in, or (signed in) before [unenroll]. */
    public suspend fun challenge() {
        val waiting = auth.pendingMfa
        val token = tokenUnlessWaiting()
        http.sendJson("/auth/v1/mfa-challenge", "POST", body("mfa_token" to waiting), token)
    }

    public data class Enrollment(val factorId: String, val type: MfaFactorType, val secret: String?, val uri: String?)

    /** Starts setting up a method. `totp` answers with a `secret` and an `otpauth://` `uri` (show it as a QR code). */
    public suspend fun enroll(type: MfaFactorType): Enrollment {
        val waiting = auth.pendingMfa
        val token = tokenUnlessWaiting()
        val answer = http.sendJson("/auth/v1/mfa-enroll", "POST", body("type" to type.name, "mfa_token" to waiting), token).jsonObject
        return Enrollment(
            answer.string("factor_id")!!, MfaFactorType.valueOf(answer.string("type")!!), answer.string("secret"), answer.string("uri"),
        )
    }

    public data class EnrollmentResult(val recoveryCodes: List<String>?, val session: Session?)

    /** Confirms a new method with its first code. During a waiting sign-in this signs the user in. */
    public suspend fun verifyEnrollment(factorId: String, code: String): EnrollmentResult {
        val waiting = auth.pendingMfa
        val token = tokenUnlessWaiting()
        val result = http.sendJson(
            "/auth/v1/mfa-enroll-verify", "POST", body("factor_id" to factorId, "code" to code, "mfa_token" to waiting), token,
        )
        val answer = result.jsonObject
        val session = (answer["session"] as? JsonObject)?.let { auth.finishSignIn(it, result.status) }
        val codes = (answer["recovery_codes"] as? JsonArray)?.map { it.jsonPrimitive.content }
        return EnrollmentResult(codes, session)
    }

    public data class Factors(val factors: List<MfaFactor>, val recoveryCodesLeft: Int)

    /** The signed-in user's methods and how many recovery codes are left. */
    public suspend fun listFactors(): Factors {
        val token = auth.accessToken() ?: throw EspobaseException.notSignedIn
        val answer = http.send("/auth/v1/mfa-factors", token = token).jsonObject
        return Factors(
            answer.getValue("factors").jsonArray.map { json.decodeFromJsonElement(MfaFactor.serializer(), it) },
            answer.getValue("recovery_codes_left").jsonPrimitive.int,
        )
    }

    /** Removes a method; needs a current code (authenticator, emailed code or a recovery code). */
    public suspend fun unenroll(factorId: String, code: String) {
        val token = auth.accessToken() ?: throw EspobaseException.notSignedIn
        http.sendJson("/auth/v1/mfa-unenroll", "POST", body("factor_id" to factorId, "code" to code), token)
    }
}
