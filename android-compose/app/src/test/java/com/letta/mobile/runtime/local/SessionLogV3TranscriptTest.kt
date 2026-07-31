package com.letta.mobile.runtime.local

import com.letta.mobile.util.Telemetry
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Regression suite for letta-mobile-6ppdr: [LocalImageContextStripper],
 * [LocalConversationHealer] and `readToolResults` used to read `role`/`content`
 * at the TOP LEVEL of every `messages.jsonl` row, but letta-code 0.29.x writes
 * **session-log v3 envelopes** —
 * `{"type":"message","id":…,"parentId":…,"timestamp":…,"message":{role,content}}`.
 * Every pre-existing unit test used the legacy FLAT shape, so both mutating
 * passes were silent no-ops on 100% of real transcripts (live-store census:
 * 89,991 envelope rows vs 27 flat, 124 image parts, ZERO stripped placeholders).
 *
 * These fixtures are SYNTHESIZED to the documented on-disk shape — no real user
 * content is read into the tree. They deliberately carry EXTRA, unknown fields
 * (both on the envelope and inside the message body) because the stripper
 * REWRITES this file on the live user store: a lossy rewrite would corrupt it.
 */
class SessionLogV3TranscriptTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun resetTelemetry() {
        Telemetry.clear()
    }

    // ── fixtures ────────────────────────────────────────────────────────

    /** The `{"type":"session","version":3,…}` header letta.js writes first. */
    private val sessionHeader =
        """{"type":"session","version":3,"id":"default","timestamp":"$TS","cwd":"/tmp"}"""

    /**
     * A v3 envelope carrying [body]. Includes `unknownEnvelopeField` — a stand-in
     * for any future field the bundle may add — which MUST survive a rewrite.
     */
    private fun envelope(entryId: String, parentId: String?, body: String): String =
        """{"type":"message","id":"$entryId","parentId":${parentId?.let { "\"$it\"" } ?: "null"},""" +
            """"timestamp":"$TS","unknownEnvelopeField":{"nested":[1,2,3]},"message":$body}"""

    /** A v3 user row with one text + one flat image part, plus an unknown body field. */
    private fun userImageBody(id: String, data: String, mime: String = "image/png"): String =
        """{"id":"$id","role":"user","futureBodyField":"keep-me","content":[""" +
            """{"type":"text","text":"see"},{"type":"image","mimeType":"$mime","data":"$data"}]}"""

    private fun assistantToolCallBody(id: String, callId: String, name: String): String =
        """{"id":"$id","role":"assistant","content":[""" +
            """{"type":"toolCall","id":"$callId","name":"$name","arguments":{}}]}"""

    private fun toolResultBody(id: String, callId: String, text: String): String =
        """{"id":"$id","role":"toolResult","toolCallId":"$callId","isError":false,""" +
            """"content":[{"type":"text","text":"$text"}]}"""

    private fun write(vararg lines: String): File =
        tempFolder.newFile("messages.jsonl").apply { writeText(lines.joinToString("\n") + "\n") }

    private fun rows(file: File): List<JsonObject> =
        file.readLines().filter { it.isNotBlank() }.map { json.parseToJsonElement(it).jsonObject }

    private fun bodyOf(row: JsonObject): JsonObject = SessionLogEnvelope.body(row)

    private fun contentOf(row: JsonObject): JsonArray = bodyOf(row)["content"]!!.jsonArray

    // ── (a) v3 row with image → stripped inside message.content ─────────

    @Test
    fun `v3 envelope image row is stripped inside message content with the envelope intact`() {
        // Two image rows: the LATEST user image row is deliberately preserved
        // (a follow-up turn must still be able to reason about it), so the
        // FIRST one is the row that gets stripped.
        val file = write(
            sessionHeader,
            envelope("e1", null, userImageBody("u1", BIG_BASE64, "image/jpeg")),
            envelope("e2", "e1", userImageBody("u2", TINY_PNG)),
        )

        val report = LocalImageContextStripper(blobStore = null, json = json).stripTranscript(file)

        assertEquals("the v3 row must actually be stripped now", 1, report.partsStripped)
        assertTrue("bytes reclaimed must be reported", report.bytesFreed >= BIG_BASE64.length)

        val out = rows(file)
        assertEquals("no row may be added or dropped", 3, out.size)

        // Header untouched.
        assertEquals("session", out[0]["type"]!!.jsonPrimitive.content)
        assertEquals(3, out[0]["version"]!!.jsonPrimitive.content.toInt())

        // Envelope of the stripped row survives in full.
        val stripped = out[1]
        assertEquals("message", stripped["type"]!!.jsonPrimitive.content)
        assertEquals("e1", stripped["id"]!!.jsonPrimitive.content)
        assertEquals(TS, stripped["timestamp"]!!.jsonPrimitive.content)
        assertNotNull("unknown envelope fields must survive", stripped["unknownEnvelopeField"])
        assertEquals(
            """{"nested":[1,2,3]}""",
            stripped["unknownEnvelopeField"].toString(),
        )

        // Body identity + unknown body field survive.
        val body = bodyOf(stripped)
        assertEquals("u1", body["id"]!!.jsonPrimitive.content)
        assertEquals("user", body["role"]!!.jsonPrimitive.content)
        assertEquals("keep-me", body["futureBodyField"]!!.jsonPrimitive.content)

        // The image part inside message.content became a text placeholder.
        val parts = contentOf(stripped)
        assertEquals(2, parts.size)
        assertEquals("text", parts[0].jsonObject["type"]!!.jsonPrimitive.content)
        val placeholder = parts[1].jsonObject
        assertEquals("text", placeholder["type"]!!.jsonPrimitive.content)
        assertTrue(placeholder["stripped"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(placeholder["text"]!!.jsonPrimitive.content.contains("image omitted"))

        // The latest image row is untouched (its data must still be sendable).
        val preserved = contentOf(out[2])[1].jsonObject
        assertEquals("image", preserved["type"]!!.jsonPrimitive.content)
        assertEquals(TINY_PNG, preserved["data"]!!.jsonPrimitive.content)

        // Telemetry: the strip is observable.
        val fired = Telemetry.snapshot().single { it.name == "strip.parts_stripped" }
        assertEquals(1, fired.attrs["parts"])
    }

    @Test
    fun `stripping a v3 transcript is idempotent`() {
        val file = write(
            sessionHeader,
            envelope("e1", null, userImageBody("u1", BIG_BASE64, "image/jpeg")),
            envelope("e2", "e1", userImageBody("u2", TINY_PNG)),
        )
        LocalImageContextStripper(blobStore = null, json = json).stripTranscript(file)
        val afterFirst = file.readText()

        val second = LocalImageContextStripper(blobStore = null, json = json).stripTranscript(file)

        assertEquals("second pass must be a no-op", 0, second.partsStripped)
        assertEquals("and must not rewrite a single byte", afterFirst, file.readText())
    }

    // ── (b) flat legacy row still works ─────────────────────────────────

    @Test
    fun `legacy flat rows are still stripped`() {
        val file = write(
            """{"id":"u1","role":"user","legacyExtra":7,"content":[""" +
                """{"type":"image","mimeType":"image/jpeg","data":"$BIG_BASE64"}]}""",
            """{"id":"u2","role":"user","content":[{"type":"image","mimeType":"image/png","data":"$TINY_PNG"}]}""",
        )

        val report = LocalImageContextStripper(blobStore = null, json = json).stripTranscript(file)

        assertEquals(1, report.partsStripped)
        val out = rows(file)
        assertEquals("flat rows must not gain an envelope", null, out[0]["message"])
        assertEquals("unknown flat fields survive", 7, out[0]["legacyExtra"]!!.jsonPrimitive.content.toInt())
        assertTrue(contentOf(out[0])[0].jsonObject["stripped"]!!.jsonPrimitive.content.toBoolean())
    }

    // ── (c) v3 dangling tool_call → healed ──────────────────────────────

    @Test
    fun `v3 envelope dangling toolCall is healed with an envelope-shaped synthetic row`() {
        val file = write(
            sessionHeader,
            envelope("e1", null, assistantToolCallBody("a1", "call-1", "read_file")),
        )

        val report = LocalConversationHealer(json).healTranscript(file)

        assertEquals(listOf("call-1"), report.orphanCallIds)
        assertEquals(1, report.rowsAppended)

        val out = rows(file)
        assertEquals(3, out.size)
        val healed = out[2]
        assertEquals("synthetic row must match the transcript shape", "message", healed["type"]!!.jsonPrimitive.content)
        assertEquals("heal-call-1", healed["id"]!!.jsonPrimitive.content)
        assertEquals("anchored to its declaring assistant entry", "e1", healed["parentId"]!!.jsonPrimitive.content)
        val body = bodyOf(healed)
        assertEquals("toolResult", body["role"]!!.jsonPrimitive.content)
        assertEquals("call-1", body["toolCallId"]!!.jsonPrimitive.content)
        assertTrue(body["isError"]!!.jsonPrimitive.content.toBoolean())

        // The anchor row is byte-preserved by the streaming rewrite.
        assertEquals("e1", out[1]["id"]!!.jsonPrimitive.content)

        val fired = Telemetry.snapshot().single { it.name == "heal.transcript_healed" }
        assertEquals(1, fired.attrs["rowsAppended"])

        // Idempotent: a second heal regenerates the identical row → true no-op.
        val after = file.readText()
        val second = LocalConversationHealer(json).healTranscript(file)
        assertEquals(0, second.rowsAppended)
        assertEquals(after, file.readText())
    }

    @Test
    fun `v3 envelope orphan toolResult is removed`() {
        val file = write(
            sessionHeader,
            envelope("e1", null, toolResultBody("t1", "call-ghost", "stale")),
            envelope("e2", "e1", """{"id":"a1","role":"assistant","content":[{"type":"text","text":"hi"}]}"""),
        )

        val report = LocalConversationHealer(json).healTranscript(file)

        assertEquals(listOf("call-ghost"), report.orphanResultIds)
        assertEquals(1, report.rowsRemoved)
        val out = rows(file)
        assertEquals(2, out.size)
        assertEquals("session", out[0]["type"]!!.jsonPrimitive.content)
        assertEquals("e2", out[1]["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `readToolResults reads v3 envelope toolResult rows`() {
        val file = write(
            sessionHeader,
            envelope("e1", null, assistantToolCallBody("a1", "call-1", "read_file")),
            envelope("e2", "e1", toolResultBody("t1", "call-1", "file contents")),
        )

        // Exercise the same body-unwrap contract readToolResults now applies.
        val results = file.readLines().filter { it.isNotBlank() }
            .map { SessionLogEnvelope.body(json.parseToJsonElement(it).jsonObject) }
            .filter { it["role"]?.jsonPrimitive?.content == "toolResult" }
        assertEquals(1, results.size)
        assertEquals("call-1", results.single()["toolCallId"]!!.jsonPrimitive.content)
    }

    // ── (d) unknown-field preservation round trip ───────────────────────

    @Test
    fun `non-image rows are byte-preserved verbatim across a strip rewrite`() {
        // A row bristling with fields this codebase has never heard of. It is
        // NOT an image row, so it must come out the other side unchanged.
        val exotic = """{"type":"message","id":"e0","parentId":null,"timestamp":"$TS",""" +
            """"provenance":{"host":"phone","seq":42},"tags":["a","b"],"nullish":null,""" +
            """"message":{"id":"x0","role":"assistant","weird":{"deep":{"deeper":true}},""" +
            """"content":[{"type":"text","text":"quoted \" and \\ backslash"}]}}"""
        val file = write(
            sessionHeader,
            exotic,
            envelope("e1", "e0", userImageBody("u1", BIG_BASE64, "image/jpeg")),
            envelope("e2", "e1", userImageBody("u2", TINY_PNG)),
        )

        LocalImageContextStripper(blobStore = null, json = json).stripTranscript(file)

        val out = file.readLines().filter { it.isNotBlank() }
        assertEquals("the exotic row must be byte-identical", exotic, out[1])
        assertEquals("the session header must be byte-identical", sessionHeader, out[0])
    }

    @Test
    fun `the healer byte-preserves every kept row including unknown fields`() {
        val exotic = """{"type":"message","id":"e0","parentId":null,"timestamp":"$TS",""" +
            """"provenance":{"host":"phone","seq":42},"message":{"id":"x0","role":"user",""" +
            """"weird":{"deep":true},"content":[{"type":"text","text":"hello"}]}}"""
        val file = write(sessionHeader, exotic, envelope("e1", "e0", assistantToolCallBody("a1", "call-1", "grep")))

        LocalConversationHealer(json).healTranscript(file)

        val out = file.readLines().filter { it.isNotBlank() }
        assertEquals(sessionHeader, out[0])
        assertEquals(exotic, out[1])
    }

    // ── (e) unparseable row passthrough ─────────────────────────────────

    @Test
    fun `unparseable rows pass through unchanged and are telemetered`() {
        val junk = """{"type":"message","id":"broken",  NOT JSON AT ALL """
        val file = write(
            sessionHeader,
            junk,
            envelope("e1", null, userImageBody("u1", BIG_BASE64, "image/jpeg")),
            envelope("e2", "e1", userImageBody("u2", TINY_PNG)),
        )

        val report = LocalImageContextStripper(blobStore = null, json = json).stripTranscript(file)

        assertEquals("the healthy v3 row is still stripped", 1, report.partsStripped)
        val out = file.readLines().filter { it.isNotBlank() }
        assertEquals("the unparseable row survives verbatim", junk, out[1])

        val passthrough = Telemetry.snapshot().single { it.name == "strip.unparseable_row_passthrough" }
        assertEquals(1, passthrough.attrs["rows"])
        assertEquals(Telemetry.Level.WARN, passthrough.level)
    }

    @Test
    fun `the healer keeps unparseable rows and telemeters the passthrough`() {
        val junk = """not json {{{"""
        val file = write(sessionHeader, junk, envelope("e1", null, assistantToolCallBody("a1", "call-1", "grep")))

        val report = LocalConversationHealer(json).healTranscript(file)

        assertEquals(1, report.rowsAppended)
        val out = file.readLines().filter { it.isNotBlank() }
        assertEquals(junk, out[1])
        assertEquals(
            1,
            Telemetry.snapshot().single { it.name == "heal.unparseable_row_passthrough" }.attrs["rows"],
        )
    }

    @Test
    fun `a well-formed v3 transcript is never rewritten by the healer`() {
        val file = write(
            sessionHeader,
            envelope("e1", null, assistantToolCallBody("a1", "call-1", "grep")),
            envelope("e2", "e1", toolResultBody("t1", "call-1", "ok")),
        )
        val before = file.readText()

        val report = LocalConversationHealer(json).healTranscript(file)

        assertEquals(0, report.rowsAppended)
        assertEquals(0, report.rowsRemoved)
        assertEquals(before, file.readText())
        assertNull(Telemetry.snapshot().firstOrNull { it.name == "heal.transcript_healed" })
    }

    // ── envelope helper contract ────────────────────────────────────────

    @Test
    fun `SessionLogEnvelope only unwraps a genuine message envelope`() {
        fun obj(raw: String) = json.parseToJsonElement(raw).jsonObject
        val env = obj(envelope("e1", null, """{"id":"m","role":"user","content":[]}"""))
        assertTrue(SessionLogEnvelope.isEnvelope(env))
        assertEquals("m", SessionLogEnvelope.body(env)["id"]!!.jsonPrimitive.content)

        val header = obj(sessionHeader)
        assertTrue(!SessionLogEnvelope.isEnvelope(header))
        assertTrue(SessionLogEnvelope.isSessionHeader(header))
        assertEquals("a header has no body → it IS its own body", header, SessionLogEnvelope.body(header))

        // type:"message" but no nested object → NOT an envelope (flat row whose
        // own `type` field happens to say message).
        val decoy = obj("""{"type":"message","role":"user","content":[]}""")
        assertTrue(!SessionLogEnvelope.isEnvelope(decoy))
        assertEquals(decoy, SessionLogEnvelope.body(decoy))
    }

    private companion object {
        const val TS = "2026-07-31T00:00:00.000Z"

        /** A real 1x1 PNG (synthetic fixture, no user content). */
        const val TINY_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="

        /** A larger decodable stand-in for an already-sent photo. */
        val BIG_BASE64 = "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAg=".repeat(8)
    }
}
