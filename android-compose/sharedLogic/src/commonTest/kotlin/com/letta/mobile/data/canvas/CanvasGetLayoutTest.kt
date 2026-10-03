package com.letta.mobile.data.canvas

import com.letta.mobile.data.controller.extras.ExternalToolResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.jvm.JvmInline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * canvas_get_layout (letta-mobile-i9yps.3): paged geometry, a stale cursor refused, every page under
 * the byte cap, and the same rows from the Iroh host and from an app (board open and closed).
 */
class CanvasGetLayoutTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun pagesCoverEveryId() = runTest {
        assertEquals(16 * 1024, CanvasLayoutRead.LAYOUT_PAGE_MAX_BYTES)
        assertEquals(200, CanvasLayoutRead.DEFAULT_LIMIT)
        assertEquals(500, CanvasLayoutRead.MAX_LIMIT)

        val host = PluginToolHost.Iroh()
        host.applyOps(*mixedBoard().toTypedArray()).content()
        val scene = host.scene()
        val pages = pages(host, limit = RowLimit(3))
        val rows = pages.flatMap { it.rows }

        assertEquals(sceneIds(scene), rows.map { it.id }.toSet(), "union of pages is every id canvas_get_scene reports")
        assertEquals(rows.map { it.id }.sorted(), rows.map { it.id }, "stable id order")
        assertTrue(pages.size > 1, "limit 3 pages a board of ${rows.size}")
        pages.dropLast(1).forEach { page ->
            assertEquals(3, page.rows.size)
            assertNotNull(page.nextCursor)
        }
        assertNull(pages.last().nextCursor)
        assertTrue(pages.all { it.revision == scene.revision })

        val byId = rows.associateBy { it.id }
        assertEquals(CanvasLayoutRead.KIND_NOTE, byId.getValue("note-body").kind)
        assertEquals("First line of the note", byId.getValue("note-body").label)
        assertEquals(listOf(11, 20, 31, 40), byId.getValue("note-title").frame)
        assertEquals(60, byId.getValue("note-title").label!!.length)
        assertTrue(byId.getValue("note-title").label!!.endsWith("…"))

        val box = byId.getValue("box-plan")
        assertEquals(CanvasLayoutRead.KIND_SHAPE, box.kind)
        assertEquals("RECTANGLE", box.shape)
        assertEquals("Plan", box.label)
        assertEquals(listOf(100, 100, 300, 160), box.frame)

        assertEquals(CanvasLayoutRead.KIND_TEXT, byId.getValue("title-1").kind)
        assertEquals("Hello layout", byId.getValue("title-1").label)
        assertEquals(listOf(12, 8, 80, 24), byId.getValue("title-1").frame)

        assertEquals(CanvasLayoutRead.KIND_PATH, byId.getValue("stroke-1").kind)
        assertNull(byId.getValue("stroke-1").label)
        assertEquals(listOf(0, 0, 10, 4), byId.getValue("stroke-1").frame)

        assertEquals(CanvasLayoutRead.KIND_IMAGE, byId.getValue("image-1").kind)
        assertNull(byId.getValue("image-1").label)
        assertEquals(listOf(10, 20, 40, 20), byId.getValue("image-1").frame)

        val arrow = byId.getValue("arrow-1")
        assertEquals("ARROW", arrow.shape)
        assertEquals("box-plan", arrow.bindings?.from)
        assertEquals("note-title", arrow.bindings?.to)

        val plugin = byId.getValue("pe-1")
        assertEquals(CanvasLayoutRead.KIND_PLUGIN, plugin.kind)
        assertEquals("widget", plugin.pluginKind)
        assertEquals("Example widget", plugin.label)
        assertEquals(listOf(40, 60, 320, 240), plugin.frame)
    }

    @Test
    fun staleCursorRefused() = runTest {
        val host = PluginToolHost.Iroh()
        host.applyOps(
            note(id = "note-a", title = "A", body = "a", frame = CanvasDocumentFrame(0f, 0f, 120f, 80f)),
            note(id = "note-b", title = "B", body = "b", frame = CanvasDocumentFrame(40f, 0f, 120f, 80f)),
        ).content()
        val first = layout(host, limit = RowLimit(1))
        val cursor = assertNotNull(first.nextCursor)
        host.applyOps(note(id = "note-c", title = "C", body = "c", frame = CanvasDocumentFrame(80f, 0f, 120f, 80f))).content()

        val refused = host.call(CanvasToolContract.GET_LAYOUT, buildJsonObject { put("cursor", cursor) }).error()
        val body = json.decodeFromString(CanvasLayoutRefusal.serializer(), refused)
        assertEquals("stale_cursor", body.error)
        assertEquals(host.scene().revision, body.revision)
        assertTrue(body.revision != first.revision)

        val restarted = layout(host, limit = RowLimit(50))
        assertNull(restarted.nextCursor)
        assertEquals(setOf("note-a", "note-b", "note-c"), restarted.rows.map { it.id }.toSet())
    }

    @Test
    fun pageUnderByteCap() = runTest {
        val host = PluginToolHost.Iroh()
        val count = 220
        val ops = (0 until count).map { index ->
            note(
                id = "n-${index.toString().padStart(3, '0')}",
                title = "N".repeat(70),
                body = "row",
                frame = CanvasDocumentFrame(index.toFloat(), 0f, 120f, 80f),
            )
        }
        host.applyOps(*ops.toTypedArray()).content()

        val rawPages = rawPages(host, limit = RowLimit(CanvasLayoutRead.MAX_LIMIT))
        assertTrue(rawPages.size >= 2, "220 labelled notes must span pages under ${CanvasLayoutRead.LAYOUT_PAGE_MAX_BYTES} bytes")
        rawPages.forEach { raw ->
            assertTrue(raw.encodeToByteArray().size <= CanvasLayoutRead.LAYOUT_PAGE_MAX_BYTES, "${raw.encodeToByteArray().size} bytes")
        }
        val rows = rawPages.map { json.decodeFromString(CanvasLayoutResult.serializer(), it) }.flatMap { it.rows }
        assertEquals(count, rows.map { it.id }.toSet().size)
        assertEquals(sceneIds(host.scene()), rows.map { it.id }.toSet())
    }

    @Test
    fun bothHostsReturnTheSameRows() = runTest {
        val ops = mixedBoard()
        val rows = PluginToolHost.all().map { host ->
            host.applyOps(*ops.toTypedArray()).content()
            layout(host, limit = RowLimit(50)).rows
        }
        assertEquals(rows[0], rows[1], "the Iroh host and an open board")
        assertEquals(rows[0], rows[2], "the Iroh host and a closed board")
        assertTrue(rows[0].any { it.kind == CanvasLayoutRead.KIND_PLUGIN })
        assertTrue(rows[0].any { it.bindings != null })
    }

    private suspend fun pages(host: PluginToolHost, limit: RowLimit): List<CanvasLayoutResult> {
        val out = mutableListOf<CanvasLayoutResult>()
        var cursor: LayoutCursor? = null
        repeat(20) {
            val page = layout(host, limit, cursor)
            out += page
            cursor = page.nextCursor?.let(::LayoutCursor) ?: return out
        }
        error("layout did not finish")
    }

    private suspend fun rawPages(host: PluginToolHost, limit: RowLimit): List<String> {
        val out = mutableListOf<String>()
        var cursor: LayoutCursor? = null
        repeat(20) {
            val raw = host.call(CanvasToolContract.GET_LAYOUT, layoutInput(limit, cursor)).content()
            out += raw
            cursor = json.decodeFromString(CanvasLayoutResult.serializer(), raw).nextCursor?.let(::LayoutCursor) ?: return out
        }
        error("layout did not finish")
    }

    private suspend fun layout(host: PluginToolHost, limit: RowLimit, cursor: LayoutCursor? = null): CanvasLayoutResult =
        json.decodeFromString(CanvasLayoutResult.serializer(), host.call(CanvasToolContract.GET_LAYOUT, layoutInput(limit, cursor)).content())

    private fun layoutInput(limit: RowLimit, cursor: LayoutCursor?): JsonObject = buildJsonObject {
        put("limit", limit.value)
        cursor?.let { put("cursor", it.value) }
    }

    private fun sceneIds(scene: CanvasGetSceneResult): Set<String> {
        val root = json.parseToJsonElement(scene.sceneJson).jsonObject
        val elements = (root["elements"] as JsonArray).mapNotNull { (it as? JsonObject)?.string("id") }
        val documents = CanvasOpProjector.documentsOf(scene.sceneJson).map { it.id }
        val plugins = scene.pluginElements.mapNotNull { it.string("id") }
        return (elements + documents + plugins).toSet()
    }

    private fun mixedBoard(): List<String> = listOf(
        note(id = "note-body", title = null, body = "First line of the note\nsecond line", frame = CanvasDocumentFrame(0f, 0f, 120f, 80f)),
        note(
            id = "note-title",
            title = "T".repeat(70),
            body = "ignored when a title is set",
            frame = CanvasDocumentFrame(10.5f, 20f, 30.5f, 40f),
        ),
        element(
            "box-plan",
            buildJsonObject {
                put("type", "Shape")
                put("shapeType", "RECTANGLE")
                put("points", points(listOf("100.0,100.0", "400.0,260.0")))
                put("text", "Plan")
            },
        ),
        element(
            "title-1",
            buildJsonObject {
                put("type", "Text")
                put("text", "Hello layout")
                put("textTopLeft", "12.0,8.0")
                put("wrapWidth", 80.0)
                put("fontSize", 20.0)
            },
        ),
        element(
            "stroke-1",
            buildJsonObject {
                put("type", "Path")
                put("samples", points(listOf("0.0,0.0,2.0", "10.0,4.0,2.0")))
            },
        ),
        element(
            "image-1",
            buildJsonObject {
                put("type", "Image")
                put("points", points(listOf("10.0,20.0", "50.0,40.0")))
                put("imageRef", "sha256:" + "ab".repeat(32))
            },
        ),
        element(
            "arrow-1",
            buildJsonObject {
                put("type", "Shape")
                put("shapeType", "ARROW")
                put("points", points(listOf("0.0,0.0", "40.0,0.0")))
                put("text", "flows")
                put("startBinding", "box-plan")
            },
        ),
        buildJsonObject {
            put("type", "set_arrow_binding")
            put("elementId", "arrow-1")
            putJsonObject("binding") {
                putJsonObject("end") {
                    put("documentId", "note-title")
                    put("side", "left")
                }
            }
        }.toString(),
        PluginToolHost.PLACE,
    )

    private fun note(id: String, title: String?, body: String, frame: CanvasDocumentFrame): String = buildJsonObject {
        put("type", "set_document")
        put("documentId", id)
        put("documentJson", """{"version":2,"blocks":[{"id":"b","content":{"text":${JsonPrimitive(body)}}}]}""")
        title?.let { put("title", it) }
        putJsonObject("frame") {
            put("x", frame.x)
            put("y", frame.y)
            put("width", frame.width)
            put("height", frame.height)
        }
    }.toString()

    private fun element(id: String, element: JsonObject): String = buildJsonObject {
        put("type", "add_element")
        put("elementId", id)
        put("elementJson", element)
    }.toString()

    private fun points(values: List<String>) = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun ExternalToolResult.content(): String = assertIs<ExternalToolResult.Success>(this, "tool call failed: $this").content

    private fun ExternalToolResult.error(): String = assertIs<ExternalToolResult.Error>(this, "expected a refusal, got $this").error

    /** A page size for canvas_get_layout, so the helpers don't take a raw int. */
    @JvmInline
    private value class RowLimit(val value: Int)

    /** The nextCursor string from a previous page. */
    @JvmInline
    private value class LayoutCursor(val value: String)
}
