package com.espobase.client

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive

private fun encodePath(path: String): String =
    path.split("/").joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

/**
 * Espobase Storage. Requests carry the project key and, when a user is signed in, their access token, so the project's
 * storage policies decide what each user may do.
 */
public class StorageClient internal constructor(private val http: Http, private val auth: AuthClient) {
    /** Files in one bucket (lowercase letters, digits, `-` and `_`). */
    public fun from(bucket: String): BucketClient {
        if (!Regex("^[a-z0-9][a-z0-9_-]{1,62}$").matches(bucket)) {
            throw EspobaseException.client(
                "invalid_bucket", "storage.from(): a bucket name (lowercase letters, digits, - and _) is required.",
            )
        }
        return BucketClient(http, auth, bucket)
    }
}

public class BucketClient internal constructor(private val http: Http, private val auth: AuthClient, public val bucket: String) {
    private fun url(rest: String) = "/storage/v1$rest"

    /** Uploads a file to [path] (e.g. `avatars/me.png`). Fails with 409 if it exists, unless [upsert] is true. */
    public suspend fun upload(
        path: String,
        bytes: ByteArray,
        contentType: String = "application/octet-stream",
        upsert: Boolean = false,
    ): StorageUploadResult {
        val result = http.send(
            url("/object/$bucket/${encodePath(path)}"),
            "POST",
            body = bytes,
            contentType = contentType,
            token = auth.accessToken(),
            extraHeaders = if (upsert) mapOf("x-upsert" to "true") else emptyMap(),
        )
        return json.decodeFromJsonElement(StorageUploadResult.serializer(), result.jsonObject)
    }

    /** Downloads a file the caller may read. With [transform], an image comes back resized (Pro and Group). */
    public suspend fun download(path: String, transform: ImageTransform? = null): ByteArray {
        val query = transform?.query.orEmpty()
        val route = if (query.isEmpty()) url("/object/$bucket/${encodePath(path)}")
        else url("/render/image/authenticated/$bucket/${encodePath(path)}")
        return http.send(route, query = query, token = auth.accessToken()).body
    }

    /** Files whose path starts with [prefix] (default: all), by name. */
    public suspend fun list(prefix: String = "", limit: Int? = null, offset: Int? = null): List<StorageFile> {
        val body = buildJsonObject {
            put("prefix", prefix)
            if (limit != null) put("limit", limit)
            if (offset != null) put("offset", offset)
        }
        val result = http.sendJson(url("/object/list/$bucket"), "POST", body, auth.accessToken())
        return json.decodeFromJsonElement(ListSerializer(StorageFile.serializer()), result.jsonOrNull ?: return emptyList())
    }

    /** Deletes files (up to 100). Returns what was actually removed. */
    public suspend fun remove(paths: List<String>): List<StorageFile> {
        if (paths.isEmpty()) throw EspobaseException.client("invalid_paths", "remove(): list at least one path.")
        val body = buildJsonObject { put("paths", buildJsonArray { paths.forEach { add(it) } }) }
        val result = http.sendJson(url("/object/$bucket"), "DELETE", body, auth.accessToken())
        return json.decodeFromJsonElement(ListSerializer(StorageFile.serializer()), result.jsonOrNull ?: return emptyList())
    }

    /** A link anyone can use to download the file for [expiresIn] seconds (max 7 days). With [transform], the link serves a resized image. */
    public suspend fun createSignedUrl(path: String, expiresIn: Int, transform: ImageTransform? = null): String {
        val result = http.sendJson(
            url("/object/sign/$bucket/${encodePath(path)}"), "POST", buildJsonObject { put("expiresIn", expiresIn) }, auth.accessToken(),
        )
        var signed = result.jsonObject.getValue("signedUrl").jsonPrimitive.content
        val query = transform?.query.orEmpty()
        if (query.isNotEmpty()) {
            signed = signed.replace("/object/sign/", "/render/image/sign/")
            val separator = if (signed.contains("?")) "&" else "?"
            signed += separator + query.joinToString("&") { (k, v) -> "$k=" + java.net.URLEncoder.encode(v, "UTF-8") }
        }
        return signed
    }

    /** The permanent URL of a file in a PUBLIC bucket (no request is made; private buckets answer 404). */
    public fun getPublicUrl(path: String, transform: ImageTransform? = null): String {
        val query = transform?.query.orEmpty()
        val route = if (query.isEmpty()) url("/object/public/$bucket/${encodePath(path)}")
        else url("/render/image/public/$bucket/${encodePath(path)}")
        return http.url(route, query)
    }
}
