package com.espobase.client

/**
 * An error from Espobase or the network. [status] is the HTTP status (0 for network and client-side errors) and [code] is an
 * Espobase or PostgreSQL error code such as `invalid_credentials` or `42501`.
 */
public class EspobaseException(
    public val status: Int,
    public val code: String,
    override val message: String,
    public val details: String? = null,
    public val hint: String? = null,
    /** With `mfa_required` / `mfa_enrollment_required`: the sign-in waits for its second step (`auth.mfa.verify`). */
    public val mfa: MfaPending? = null,
) : Exception(message) {
    override fun toString(): String = "EspobaseException($status $code: $message)"

    internal companion object {
        fun client(code: String, message: String) = EspobaseException(0, code, message)

        val notSignedIn = EspobaseException(401, "no_session", "Not signed in.")
    }
}

/** A sign-in that waits for its second step. */
public data class MfaPending(
    val enrollmentRequired: Boolean,
    val factors: List<Factor>,
    /** Whether a recovery code can be used instead. */
    val recoveryCodes: Boolean,
) {
    public data class Factor(val id: String, val type: MfaFactorType)
}

public enum class MfaFactorType { totp, email }
