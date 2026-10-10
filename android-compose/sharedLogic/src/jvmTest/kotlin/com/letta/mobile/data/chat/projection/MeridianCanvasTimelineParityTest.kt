package com.letta.mobile.data.chat.projection

import com.letta.mobile.data.canvas.CanvasToolContract
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.data.timeline.TimelineEvent
import com.letta.mobile.data.timeline.TimelineMessageType
import com.letta.mobile.data.timeline.parseTimelineInstant
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-jna0o.6: a `meridian canvas …` call run through Bash or the `meridian` meta-tool
 * projects to the same timeline row and the same compose receipt as the native canvas tool call.
 */
class MeridianCanvasTimelineParityTest {
    private val request = fixture("request.json")
    private val compactRequest = Json.parseToJsonElement(request).toString()
    private val receiptJson = fixture("receipt.json")
    private val refusalJson = fixture("error-validation.json")

    private val native = ToolCall(id = "t1", name = CanvasToolContract.COMPOSE, arguments = compactRequest)
    private val viaBash = ToolCall(id = "t1", name = "Bash", arguments = bashArgs("meridian canvas compose <<'JSON'\n$request\nJSON"))
    private val viaWrappedBash = ToolCall(
        id = "t1",
        name = "Bash",
        arguments = bashArgs("cd ~ && LETTA_API_KEY=sk-secret bash -lc \"meridian canvas compose <<'JSON'\n${request.replace("\"", "\\\"")}\nJSON\""),
    )
    private val viaMetaTool = ToolCall(id = "t1", name = "meridian", arguments = """{"command":"canvas compose","input":$request}""")

    @Test
    fun aComposeReceiptFromTheCliIsTheNativeReceipt() {
        val expected = receipt(native, Returned(receiptJson))
        assertEquals(CanvasArtifactStatus.Published, expected.status)
        assertEquals("weekend-plan", expected.artifactId)
        listOf(viaBash, viaWrappedBash, viaMetaTool).forEach { cli ->
            assertEquals(expected, receipt(cli, Returned(receiptJson)), cli.name)
        }
    }

    @Test
    fun theRowModelIsTheNativeRowModel() {
        listOf(null, Returned(receiptJson), Returned(refusalJson, isError = true)).forEach { returned ->
            val expected = row(native, returned)
            assertEquals(CanvasToolContract.COMPOSE, expected.name)
            listOf(viaBash, viaWrappedBash, viaMetaTool).forEach { cli -> assertEquals(expected, row(cli, returned), "${cli.name} $returned") }
        }
    }

    @Test
    fun shellFramingAroundStdoutIsReadAsTheNativeReturn() {
        val framed = Returned("Exit code: 0\n$receiptJson\n[stderr] composed 6 items", isError = false)
        assertEquals(receipt(native, Returned(receiptJson)), receipt(viaBash, framed))
        assertEquals(row(native, Returned(receiptJson)).result!!.trim(), row(viaBash, framed).result!!.trim())
    }

    @Test
    fun aRefusalOnStdoutIsAFailedReceiptEvenWithoutTheErrorFlag() {
        // The CLI exits 2 with `{"ok":false,…}` on stdout; a shell tool may not flag the return.
        val expected = receipt(native, Returned(refusalJson, isError = true))
        val actual = receipt(viaBash, Returned("Exit code: 2\n$refusalJson", isError = false))
        assertEquals(expected, actual)
        assertEquals(CanvasArtifactStatus.Failed, actual.status)
        assertEquals("VALIDATION_FAILED", actual.error?.code)
        assertEquals(3, actual.error?.problemCount)
        assertEquals("error", row(viaBash, Returned("Exit code: 2\n$refusalJson")).status)
    }

    @Test
    fun anUnreachableHostIsAFailedReceiptAndAnErrorRow() {
        val actual = receipt(viaBash, Returned("""{"error":"host_unavailable"}"""))
        assertEquals(CanvasArtifactStatus.Failed, actual.status)
        assertEquals("host_unavailable", actual.error?.message)
        assertEquals("Weekend plan", actual.title)
        assertEquals("error", row(viaBash, Returned("""{"error":"host_unavailable"}""")).status)
    }

    @Test
    fun anUnreadableHeredocDegradesInsteadOfBreakingTheCard() {
        val broken = ToolCall(id = "t9", name = "Bash", arguments = bashArgs("meridian canvas compose <<'JSON'\n{\"title\": \"x\", \"items\": [\nJSON"))
        val pending = receipt(broken, null)
        assertEquals(CanvasArtifactStatus.Pending, pending.status)
        assertEquals("call:t9", pending.artifactId)
        val published = receipt(broken, Returned(receiptJson))
        assertEquals("weekend-plan", published.artifactId)
        assertEquals(CanvasArtifactStatus.Published, published.status)
    }

    @Test
    fun otherVerbsRenderAsTheirNativeRowsWithoutAReceipt() {
        val layoutJson = """{"canvasId":"c1","rows":[]}"""
        val nativeLayout = ToolCall(id = "t2", name = CanvasToolContract.GET_LAYOUT, arguments = """{"limit":50}""")
        val cliLayout = ToolCall(id = "t2", name = "Bash", arguments = bashArgs("meridian canvas layout --limit 50"))
        assertEquals(row(nativeLayout, Returned(layoutJson)), row(cliLayout, Returned(layoutJson)))
        assertTrue(CanvasArtifactReceipts.attach(listOf(event(cliLayout, Returned(layoutJson)))).isEmpty())
    }

    @Test
    fun anOrdinaryBashCallKeepsItsBashRow() {
        val ls = ToolCall(id = "t3", name = "Bash", arguments = bashArgs("ls ~/meridian"))
        val row = row(ls, Returned("a\nb"))
        assertEquals("Bash", row.name)
        assertEquals(ls.arguments, row.arguments)
        assertEquals("a\nb", row.result)
        assertTrue(CanvasArtifactReceipts.attach(listOf(event(ls, Returned("a\nb")))).isEmpty())
    }

    @Test
    fun theWrapperSecretNeverReachesTheRow() {
        val row = row(viaWrappedBash, Returned(receiptJson))
        assertTrue("sk-secret" !in row.arguments)
        assertTrue("sk-secret" !in row.name)
    }

    // --- helpers ----------------------------------------------------------------------------

    private data class Returned(val text: String, val isError: Boolean = false)

    private fun event(call: ToolCall, returned: Returned?) = TimelineEvent.Confirmed(
        position = 1.0, otid = "otid-${call.id}", content = "", serverId = "m-${call.id}",
        messageType = TimelineMessageType.TOOL_CALL, date = parseTimelineInstant(T0), runId = "run-1", stepId = "s1",
        toolCalls = persistentListOf(call),
        toolReturnContentByCallId = returned?.let { persistentMapOf(call.effectiveId to it.text) } ?: persistentMapOf(),
        toolReturnIsErrorByCallId = returned?.let { persistentMapOf(call.effectiveId to it.isError) } ?: persistentMapOf(),
    )

    private fun receipt(call: ToolCall, returned: Returned?): CanvasArtifactReceipt =
        CanvasArtifactReceipts.attach(listOf(event(call, returned))).values.single().single()

    private fun row(call: ToolCall, returned: Returned?): UiToolCall =
        timelineEventToUiMessage(event(call, returned))!!.toolCalls!!.single()

    private fun bashArgs(script: String): String =
        buildJsonObject { put("command", script); put("description", "Draw the plan") }.toString()

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/canvas/compose/v1/$name")) { "missing fixture $name" }.readText()

    private companion object {
        const val T0 = "2026-10-01T12:00:00Z"
    }
}
