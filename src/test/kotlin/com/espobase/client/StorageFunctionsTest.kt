package com.espobase.client

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StorageFunctionsTest {
    @Test
    fun uploadSendsTheBytesWithTheContentTypeAndUpsert() = runTest {
        val (c, t) = client {
            json("""{"id":"o1","bucket":"avatars","path":"me/pic 1.png","size":3,"sha256":"${"a".repeat(64)}"}""")
        }
        val out = c.storage.from("avatars").upload("me/pic 1.png", byteArrayOf(1, 2, 3), "image/png", upsert = true)
        assertEquals(3, out.size)
        val seen = t.requests.single()
        assertEquals("POST", seen.method)
        assertEquals("$PROJECT_URL/storage/v1/object/avatars/me/pic%201.png", seen.url)
        assertEquals("image/png", seen.header("content-type"))
        assertEquals("true", seen.header("x-upsert"))
        assertContentEquals(byteArrayOf(1, 2, 3), seen.request.body)
    }

    @Test
    fun downloadListRemoveAndBucketNames() = runTest {
        val (c, t) = client {
            if (it.path.contains("/object/list/") || it.method == "DELETE") json(JsonArray(emptyList())) else Answer(200, byteArrayOf(9, 9))
        }
        val bucket = c.storage.from("docs")
        assertContentEquals(byteArrayOf(9, 9), bucket.download("a/b.pdf"))
        bucket.list("a/", limit = 10)
        assertEquals("a/", t.requests[1].json!!["prefix"].toString().trim('"'))
        assertEquals("10", t.requests[1].json!!["limit"].toString())
        bucket.remove(listOf("a/b.pdf"))
        assertEquals("""["a/b.pdf"]""", t.requests[2].json!!["paths"].toString())
        expectError("invalid_paths") { bucket.remove(emptyList()) }
        assertEquals("invalid_bucket", expectThrows<EspobaseException> { c.storage.from("Bad Bucket") }.code)
    }

    @Test
    fun imageTransformsChangeTheDownloadRouteAndTheLinks() = runTest {
        val (c, t) = client {
            if (it.path.contains("/object/sign/")) json("""{"signedUrl":"$PROJECT_URL/storage/v1/object/sign/photos/a.jpg?token=T"}""")
            else Answer(200, byteArrayOf(1))
        }
        val photos = c.storage.from("photos")
        photos.download("a.jpg", ImageTransform(width = 200, height = 100, resize = "contain", quality = 70, format = "webp"))
        assertEquals("/project/abcde12345/storage/v1/render/image/authenticated/photos/a.jpg", t.requests[0].path)
        assertEquals(
            mapOf("width" to "200", "height" to "100", "resize" to "contain", "quality" to "70", "format" to "webp"),
            t.requests[0].query,
        )
        val signed = photos.createSignedUrl("a.jpg", 600, ImageTransform(width = 64))
        assertTrue(signed.contains("/storage/v1/render/image/sign/photos/a.jpg?token=T&width=64"), signed)
        assertEquals("$PROJECT_URL/storage/v1/object/public/photos/a%20b.jpg", photos.getPublicUrl("a b.jpg"))
        assertTrue(photos.getPublicUrl("a.jpg", ImageTransform(width = 50)).contains("/render/image/public/photos/a.jpg?width=50"))
        assertEquals(2, t.requests.size, "building a public URL makes no request")
    }

    @Test
    fun functionsSendJsonAndReturnTheAnswer() = runTest {
        val (c, t) = client {
            if (it.path.endsWith("/boom")) json("""{"error":"function_error","message":"it broke"}""", 500) else json("""{"message":"Hello"}""")
        }
        val response = c.functions.invoke("hello", buildJsonObject { put("name", "Ada") }, query = mapOf("x" to "1"))
        assertEquals("""{"message":"Hello"}""", response.json().toString())
        assertEquals(200, response.status)
        assertEquals("application/json", response.contentType)
        val seen = t.requests.single()
        assertEquals("/project/abcde12345/functions/v1/hello", seen.path)
        assertEquals(mapOf("x" to "1"), seen.query)
        assertEquals("Ada", seen.json!!["name"].toString().trim('"'))
        expectError("function_error", 500) { c.functions.invoke("boom") }
        expectError("invalid_function") { c.functions.invoke("Bad Name") }
        c.functions.invoke("hello", "hi".toByteArray(), "text/csv")
        assertEquals("text/csv", t.requests.last().header("content-type"))
    }
}
