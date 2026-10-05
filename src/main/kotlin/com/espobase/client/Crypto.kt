package com.espobase.client

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

internal val secureRandom = SecureRandom()

internal fun base64UrlNoPad(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

internal fun randomBytes(count: Int): ByteArray = ByteArray(count).also { secureRandom.nextBytes(it) }

internal fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

internal fun pkceChallenge(verifier: String): String = base64UrlNoPad(sha256(verifier.toByteArray(Charsets.UTF_8)))

/** RFC 7636 (S256) verifier and challenge for a social sign-in. */
internal class Pkce private constructor(val verifier: String, val challenge: String) {
    companion object {
        fun generate(): Pkce {
            val verifier = base64UrlNoPad(randomBytes(32))
            return Pkce(verifier, pkceChallenge(verifier))
        }
    }
}

/**
 * Sign in with Apple: the nonce for the sign-in request. Give Apple [hashed] and keep [raw] to send to
 * `auth.signInWithIdToken`.
 */
public class AppleNonce(public val raw: String = base64UrlNoPad(randomBytes(32))) {
    /** SHA-256 of [raw], lowercase hex (what Apple puts in the identity token). */
    public val hashed: String = sha256(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
