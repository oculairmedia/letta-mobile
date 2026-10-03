package com.letta.mobile.data.timeline

import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.LettaMessage
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * letta-mobile-iyj4s: one LocalBackend assistant message carrying a reasoning part and a text
 * part is two timeline rows, a Thought and a reply, on both the live and the settled path.
 *
 * The wrapper used to give both wire frames the source message's otid, and the canonical writer
 * resolved the second through that otid onto the first: live kept the thought and dropped the
 * reply, settled kept the reply under the reasoning part's id, and the Thought row visible while
 * streaming disappeared at settlement.
 */
class ReasoningTextPartSplitTest {
    @Test fun wireNamesEachPartByMessageIdAndPartIndex() {
        val wire = LocalBackendTurnHarness.wireJson(turn()).filter { it.type in PROSE_TYPES && it.string("id").startsWith("m02") }

        assertEquals(listOf(REASONING_ID, REPLY_ID), wire.map { it.string("id") })
        assertEquals(listOf(REASONING_ID, REPLY_ID), wire.map { it.string("otid") })
    }

    @Test fun liveAndSettledRenderTheSameThoughtAndReply() = runTest {
        val projected = LocalBackendTurnHarness("iyj4s-fresh").project(LocalBackendTurnHarness.wireMessages(turn()))

        assertEquals(signature(projected.live), signature(projected.settled))
        assertThoughtAndReplyShareOneRun(projected.settled)
    }

    @Test fun legacySharedOtidWireStillSettlesBothParts() = runTest {
        val expected = freshSettled()
        val harness = LocalBackendTurnHarness("iyj4s-legacy")

        harness.settle(legacyWire())

        assertEquals(expected, signature(harness.settled()))
    }

    @Test fun collapsedStoredEventSplitsOnTheNextReconcile() = runTest {
        val expected = freshSettled()
        val harness = LocalBackendTurnHarness("iyj4s-collapsed")
        seedCollapsedRow(harness)
        assertFalse(harness.settled().flatMap { it.members() }.any { it.isReasoning }, "seed must be the collapsed row")

        harness.settle(legacyWire())
        assertEquals(expected, signature(harness.settled()))
        harness.settle(LocalBackendTurnHarness.wireMessages(turn()))
        assertEquals(expected, signature(harness.settled()), "a current wrapper must keep the split")
    }

    private fun assertThoughtAndReplyShareOneRun(items: List<ChatRenderItem>) {
        val run = items.filterIsInstance<ChatRenderItem.RunBlock>().single { block ->
            block.messages.any { it.first.id.startsWith(REASONING_ID) }
        }
        val members = run.messages.map { it.first }
        assertTrue(members.any { it.id.startsWith(REASONING_ID) && it.isReasoning && it.content == THOUGHT })
        assertTrue(members.any { it.id == REPLY_ID && !it.isReasoning && it.content == REPLY })
        assertEquals(2, members.sumOf { it.toolCalls.orEmpty().size })
    }

    private suspend fun freshSettled() = LocalBackendTurnHarness("iyj4s-reference").let { reference ->
        reference.settle(LocalBackendTurnHarness.wireMessages(turn()))
        signature(reference.settled()).also { rows ->
            assertTrue(rows.flatMap { it.second }.any { it.startsWith("$REASONING_ID:REASONING|thought|") })
        }
    }

    /**
     * The row the pre-fix writer stored: the reasoning part merged first and fixed the row's id and
     * position, then the reply folded its body onto it and was recorded as an alias of it.
     */
    private suspend fun seedCollapsedRow(harness: LocalBackendTurnHarness) {
        val reasoningDate = partJson(REASONING_ID).getValue("date")
        val collapsed = assertNotNull(LocalBackendTurnHarness.decode(listOf(partJson(REPLY_ID).shareSourceOtid())) { wire ->
            JsonObject(wire + ("id" to JsonPrimitive(REASONING_ID)) + ("date" to reasoningDate))
        }.single().toTimelineEvent(0.0))
        val writer = TimelineExactCanonicalWriter(harness.scope, 2_000_000)
        harness.store.transaction(harness.scope) {
            writer.mergeEvent(this, collapsed)
            putEvidence("identity/serverId/$REPLY_ID", REASONING_ID.encodeToByteArray())
            nextRevision()
        }
    }

    private fun partJson(id: String) = LocalBackendTurnHarness.wireJson(turn()).single { it.string("id") == id }

    /** The wire an older wrapper serves: every prose part carries the source message's otid. */
    private fun legacyWire(): List<LettaMessage> =
        LocalBackendTurnHarness.decode(LocalBackendTurnHarness.wireJson(turn())) { it.shareSourceOtid() }

    private fun JsonObject.shareSourceOtid(): JsonObject = if (type !in PROSE_TYPES) this
    else JsonObject(this + ("otid" to JsonPrimitive(string("id").substringBefore(':'))))

    /**
     * Row count, run keys and every member's identity, kind and text, which is what a row renders,
     * in timeline order (a settled page is read tail first). A standalone row's key is left out:
     * the settled path names it in its own segment domain, which #1673/#1688 reconcile.
     */
    private fun signature(items: List<ChatRenderItem>) = items.sortedBy { it.boundaryTimestamp }.map { item ->
        (item as? ChatRenderItem.RunBlock)?.key to item.members().map { member ->
            "${member.id}|${if (member.isReasoning) "thought" else member.role}|${member.content}|${member.toolCalls.orEmpty().size}"
        }
    }

    private val JsonObject.type get() = string("message_type")

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content

    /** The real shape from the bead: reasoning, reply text, then two tool calls. */
    private fun turn(): List<TurnEntry> = listOf(
        TurnEntry.User("m01", "look at the build"),
        TurnEntry.Assistant(
            "m02",
            listOf(TurnPart.Reasoning(THOUGHT), TurnPart.Text(REPLY), TurnPart.Call("c1", "Read"), TurnPart.Call("c2", "Read")),
        ),
        TurnEntry.ToolResult("m03", "c1", "Read"),
        TurnEntry.ToolResult("m04", "c2", "Read"),
        TurnEntry.Assistant("m05", listOf(TurnPart.Text("Both files look fine."))),
    )

    private companion object {
        const val THOUGHT = "Planning"
        const val REPLY = "I'll inspect the files."
        const val REASONING_ID = "m02:reasoning:0"
        const val REPLY_ID = "m02:assistant:1"
        val PROSE_TYPES = setOf("reasoning_message", "assistant_message")
    }
}
