package com.espobase.client

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Serializable
data class Row(val id: Int, val title: String = "")

class QueryTest {
    @Test
    fun filtersOrderingAndRangeBecomePostgrestParameters() = runTest {
        val (c, t) = client { json(JsonArray(emptyList())) }
        c.from("orders").select("id,total").eq("status", "open").neq("kind", "gift").gt("total", 10).gte("score", 1.5)
            .lt("n", 3).lte("m", 4).like("name", "a*").ilike("email", "%@x.com").inList("id", listOf(1, 2, 3))
            .inList("tag", listOf("a,b", "say \"hi\"")).isNull("deleted_at").isBool("active", true)
            .order("created_at", ascending = false, nullsFirst = false).range(10, 19).run()
        val pairs = t.requests.single().rawQuery.split("&").map { java.net.URLDecoder.decode(it, "UTF-8") }
        assertEquals(
            listOf(
                "select=id,total", "status=eq.open", "kind=neq.gift", "total=gt.10", "score=gte.1.5", "n=lt.3", "m=lte.4",
                "name=like.a*", "email=ilike.%@x.com", "id=in.(1,2,3)", "tag=in.(\"a,b\",\"say \\\"hi\\\"\")",
                "deleted_at=is.null", "active=is.true", "order=created_at.desc.nullslast", "offset=10", "limit=10",
            ),
            pairs,
        )
    }

    @Test
    fun readsDecodeIntoYourTypes() = runTest {
        val (c, _) = client { json("""[{"id":1,"title":"x","extra":true}]""") }
        val rows: List<Row> = c.from("todos").select().execute()
        assertEquals(listOf(Row(1, "x")), rows)
        assertEquals(1, c.from("todos").select().executeJson().size)
        val (one, t) = client { json("""{"id":3}""") }
        assertEquals(Row(3), one.from("todos").select().eq("id", 3).single())
        assertEquals("application/vnd.pgrst.object+json", t.requests.single().header("accept"))
        expectError("decode_error") { c.from("todos").execute(ListSerializer(ListSerializer(Row.serializer()))) }
    }

    @Test
    fun insertSendsJsonAndAsksForNoRowsUnlessSelected() = runTest {
        val (c, t) = client { Answer(201) }
        c.from("todos").insert(buildJsonObject { put("title", "Buy milk") }).run()
        val minimal = t.requests.single()
        assertEquals("POST", minimal.method)
        assertEquals("return=minimal", minimal.header("prefer"))
        assertEquals("Buy milk", minimal.json!!["title"].toString().trim('"'))
        val (c2, t2) = client { json("""[{"id":1,"title":"x"}]""", 201) }
        val rows: List<Row> = c2.from("todos").insert(buildJsonArray { add(buildJsonObject { put("title", "x") }) }).select().execute()
        assertEquals(listOf(Row(1, "x")), rows)
        assertEquals("return=representation", t2.requests.single().header("prefer"))
        assertEquals("application/json", t2.requests.single().header("content-type"))
    }

    @Test
    fun updateAndDeleteNeedAFilterAndNothingIsSentWithoutOne() = runTest {
        val (c, t) = client { Answer(204) }
        expectError("filter_required") { c.from("todos").update(buildJsonObject { put("done", true) }).run() }
        expectError("filter_required") { c.from("todos").delete().run() }
        assertTrue(t.requests.isEmpty())
        c.from("todos").update(buildJsonObject { put("done", true) }).eq("id", 5).run()
        c.from("todos").delete().eq("id", 5).run()
        assertEquals(listOf("PATCH", "DELETE"), t.requests.map { it.method })
        assertEquals("eq.5", t.requests[0].query["id"])
    }

    @Test
    fun badInputIsRefusedBeforeSendingAndBuildersAreImmutable() = runTest {
        val (c, t) = client { json(JsonArray(emptyList())) }
        expectError("invalid_query") { c.from("todos").eq("bad\ncolumn", 1).run() }
        expectError("invalid_query") { c.from("todos").limit(-1).run() }
        expectError("invalid_query") { c.from("todos").range(5, 2).run() }
        assertTrue(t.requests.isEmpty())
        assertEquals("invalid_table", expectThrows<EspobaseException> { c.from("") }.code)
        val base = c.from("my table").select()
        val filtered = base.eq("id", 1)
        base.run()
        filtered.run()
        assertTrue(t.requests[0].url.contains("/rest/v1/my%20table?select=*"))
        assertEquals(null, t.requests[0].query["id"])
        assertEquals("eq.1", t.requests[1].query["id"])
        assertEquals(1, Json.parseToJsonElement("[1]").let { (it as JsonArray).size })
    }
}
