package com.letta.mobile.data.timeline

import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * letta-mobile-jp78k: a deferred tool call shows what the tool did, not the `name(arguments)`
 * string the projection synthesizes into `content` and then blanks.
 *
 * The bodies here are the shapes the device ledger actually stores.
 */
class TimelineDeferredBodyTest {

    @Test fun aNestedStringIsReachedByPath() = runTest {
        val body = """{"messageType":"TOOL_CALL","toolCalls":[{"id":"call_1","name":"Bash","arguments":"{\"command\":\"ls\"}"}]}"""
        val text = assertIs<TimelineSemanticWindowResult.Text>(read(body, TimelineSemanticField.toolArguments()))
        assertEquals("""{"command":"ls"}""", text.value)
        assertEquals("Bash", assertIs<TimelineSemanticWindowResult.Text>(read(body, TimelineSemanticField.toolName())).value)
    }

    @Test fun aMapKeyedByCallIdIsReadWithoutKnowingTheCallId() = runTest {
        val body = """{"messageType":"TOOL_CALL","toolReturnContentByCallId":{"call_85a3":"total 4\ndrwx"}}"""
        val text = assertIs<TimelineSemanticWindowResult.Text>(read(body, TimelineSemanticField.ToolReturnByCallId))
        assertEquals("total 4\ndrwx", text.value)
    }

    @Test fun onlyTheFirstEntryOfSuchAMapIsRead() = runTest {
        val body = """{"messageType":"TOOL_CALL","toolReturnContentByCallId":{"a":"first","b":"second"}}"""
        val text = assertIs<TimelineSemanticWindowResult.Text>(read(body, TimelineSemanticField.ToolReturnByCallId))
        assertEquals("first", text.value)
    }

    @Test fun anIndexPastTheEndOfTheArrayIsSimplyAbsent() = runTest {
        val body = """{"messageType":"TOOL_CALL","toolCalls":[{"name":"Bash","arguments":"x"}]}"""
        val missing = assertIs<TimelineSemanticWindowResult.Deferred>(read(body, TimelineSemanticField.toolArguments(3)))
        assertEquals(TimelineSemanticWindowResult.Reason.MissingField, missing.reason)
        assertEquals("tool_call", missing.messageType)
    }

    @Test fun theMessageTypeComesBackEvenWhenTheFieldDoesNot() = runTest {
        val body = """{"messageType":"REASONING","content":"thinking"}"""
        val missing = assertIs<TimelineSemanticWindowResult.Deferred>(read(body, TimelineSemanticField.toolName()))
        assertEquals("reasoning", missing.messageType)
    }

    @Test fun aToolCallResolvesToItsOutputKeyedByCallId() = runTest {
        val body = """{"messageType":"TOOL_CALL","content":"Bash({\"command\":\"ls\"})",
            "toolCalls":[{"id":"call_1","name":"Bash","arguments":"{\"command\":\"ls\"}"}],
            "toolReturnContent":"","toolReturnContentByCallId":{"call_1":"total 4"}}""".trimIndent()
        val resolved = resolve(body)
        assertEquals("Bash", resolved.toolName)
        assertEquals(TimelineSemanticField.ToolReturnByCallId, resolved.field)
        assertEquals("total 4", resolved.first?.value)
    }

    @Test fun aToolCallWithAFlatReturnResolvesToThatReturn() = runTest {
        val body = """{"messageType":"TOOL_CALL","content":"Bash({\"command\":\"ls\"})",
            "toolCalls":[{"id":"call_1","name":"Bash","arguments":"{\"command\":\"ls\"}"}],
            "toolReturnContent":"total 4","toolReturnContentByCallId":{}}""".trimIndent()
        val resolved = resolve(body)
        assertEquals(TimelineSemanticField.ToolReturn, resolved.field)
        assertEquals("total 4", resolved.first?.value)
    }

    @Test fun aToolCallThatHasNotReturnedResolvesToItsArgumentsNeverToContent() = runTest {
        val body = """{"messageType":"TOOL_CALL","content":"Bash({\"command\":\"ls\"})",
            "toolCalls":[{"id":"call_1","name":"Bash","arguments":"{\"command\":\"ls\"}"}],
            "toolReturnContent":"","toolReturnContentByCallId":{}}""".trimIndent()
        val resolved = resolve(body)
        assertEquals(TimelineSemanticField.toolArguments(), resolved.field)
        assertEquals("""{"command":"ls"}""", resolved.first?.value)
        assertTrue(resolved.first?.value?.startsWith("Bash(") != true)
    }

    @Test fun anOrdinaryMessageResolvesToItsContent() = runTest {
        val resolved = resolve("""{"messageType":"REASONING","content":"thinking out loud"}""")
        assertEquals(TimelineSemanticField.Content, resolved.field)
        assertEquals("thinking out loud", resolved.first?.value)
        assertNull(resolved.toolName)
    }

    @Test fun aBodyWithNothingToShowResolvesToNoField() = runTest {
        val resolved = resolve("""{"messageType":"ASSISTANT","content":""}""")
        assertNull(resolved.field)
        assertNull(resolved.first)
    }

    private suspend fun resolve(body: String): TimelineDeferredBody =
        resolveDeferredBody { field, offset -> read(body, field, offset) }

    private suspend fun read(
        body: String,
        field: TimelineSemanticField,
        offset: Long = 0,
    ): TimelineSemanticWindowResult {
        val bytes = body.encodeToByteArray()
        val reference = TimelineBodyReference(
            TimelineScope("backend", "conversation"), TimelinePageKey(1, TimelineMessageId("id")),
            TimelineBodyPointer("digest", bytes.size.toLong()),
            "application/vnd.letta.timeline-event+json;version=1", 1,
        )
        return TimelineSemanticBodyWindow.read(reference, field, offset, TimelineSemanticBudget(chunkBytes = 7)) { at, size ->
            TimelineBodyChunk(reference, at, bytes.copyOfRange(at.toInt(), minOf(bytes.size, at.toInt() + size)))
        }
    }
}
