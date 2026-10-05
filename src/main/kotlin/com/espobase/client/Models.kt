package com.espobase.client

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public enum class AuthProvider { email, google, github, apple }

/** Social sign-in providers (turned on per project in the dashboard). */
public enum class OAuthProvider { google, github, apple }

@Serializable
public data class User(
    val id: String,
    val email: String,
    val emailVerified: Boolean,
    val appRole: String,
    /** The first way this user signed in. */
    val provider: AuthProvider,
    /** Every sign-in method linked to the user. */
    val providers: List<AuthProvider>,
    val createdAt: String,
    val lastSignInAt: String? = null,
)

@Serializable
public data class Session(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String,
    @SerialName("expires_in") val expiresIn: Int,
    /** Epoch seconds. */
    @SerialName("expires_at") val expiresAt: Long,
    @SerialName("refresh_token") val refreshToken: String,
    val user: User,
)

public enum class AuthEvent { SIGNED_IN, SIGNED_OUT, TOKEN_REFRESHED, USER_UPDATED }

public data class AuthStateChange(val event: AuthEvent, val session: Session?)

/** The result of a sign-up: a session when no confirmation is needed, otherwise only the user. */
public data class SignUpResult(val user: User?, val session: Session?)

@Serializable
public data class MfaFactor(val id: String, val type: MfaFactorType, val verified: Boolean, val createdAt: String)

/** A Storage file, as listed. */
@Serializable
public data class StorageFile(
    val id: String,
    /** Full path in the bucket, e.g. `avatars/me.png`. */
    val name: String,
    val size: Long,
    val mimeType: String? = null,
    val sha256: String? = null,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
public data class StorageUploadResult(
    val id: String,
    val bucket: String,
    val path: String,
    val size: Long,
    /** SHA-256 of the stored bytes (hex). */
    val sha256: String,
)

/** Resizing for stored images (Pro and Group). Give a width, a height or both. */
public data class ImageTransform(
    val width: Int? = null,
    val height: Int? = null,
    /** `cover` (default), `contain`, `fill`, `inside` or `outside`. */
    val resize: String? = null,
    val quality: Int? = null,
    /** `webp` (default), `jpeg`, `png` or `avif`. */
    val format: String? = null,
) {
    internal val query: List<Pair<String, String>>
        get() = listOfNotNull(
            width?.let { "width" to it.toString() },
            height?.let { "height" to it.toString() },
            resize?.let { "resize" to it },
            quality?.let { "quality" to it.toString() },
            format?.let { "format" to it },
        )
}
