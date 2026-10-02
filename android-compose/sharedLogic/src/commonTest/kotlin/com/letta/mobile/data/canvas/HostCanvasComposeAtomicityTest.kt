package com.letta.mobile.data.canvas

import com.letta.mobile.data.canvas.HostCanvasComposeToolsTest.Companion.AGENT
import com.letta.mobile.data.canvas.HostCanvasComposeToolsTest.Companion.CONVERSATION
import com.letta.mobile.data.canvas.HostCanvasComposeToolsTest.Companion.WEEKEND_PLAN
import com.letta.mobile.data.canvas.HostCanvasComposeToolsTest.Companion.receipt
import com.letta.mobile.data.canvas.HostCanvasComposeToolsTest.Companion.refusal
import com.letta.mobile.data.canvas.compose.CanvasComposeService
import com.letta.mobile.data.canvas.compose.ComposeErrorCode
import com.letta.mobile.data.canvas.compose.ComposeStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A compose artifact reaches the host's log whole or not at all (letta-mobile-bglj6.12), with the
 * relay failing under it: the batch is one relay message, one append and one ack, so no failure
 * point falls inside it; and a failure after the append (the ack lost) heals on the agent's retry.
 */
class HostCanvasComposeAtomicityTest {
    /** A relay store that fails on demand: before an append, or after one is durable. */
    private class FlakyStore(private val inner: InMemoryCanvasRelayStore = InMemoryCanvasRelayStore()) : CanvasRelayStore by inner {
        /** Appends allowed before every further one fails; null never fails. */
        var allowedAppends: Int? = null
        var appends = 0

        /** Append durably, then fail as if the connection dropped before the ack went out. */
        var loseTheAck = false

        override suspend fun append(topic: String, op: CanvasOp, origin: String): CanvasRelayAppend {
            allowedAppends?.let { if (appends >= it) throw IllegalStateException("relay store failed") }
            appends++
            val appended = inner.append(topic, op, origin)
            if (loseTheAck) throw IllegalStateException("relay connection lost before the ack")
            return appended
        }
    }

    private fun addText(id: String) = CanvasOp.AddElementOp(
        opId = "", actorId = "", lamport = 0L, elementId = id,
        elementJson = buildJsonObject {
            put("id", id)
            put("type", "Text")
            put("text", id)
            put("textTopLeft", "10.0,10.0")
        }.toString(),
    )

    @Test
    fun sentOpByOpARelayFailingMidBatchLeavesPartOfIt() = runTest {
        // Why compose publishes atomically: the per-op path keeps what was acknowledged before the failure.
        val store = FlakyStore()
        val host = HostCanvasComposeToolsTest.Host(store)
        val caller = HostCanvasCaller(AGENT, CONVERSATION)
        val entry = assertIs<HostCanvasAccess.Granted>(host.backend.ownConversation(caller)).entry
        store.allowedAppends = 1
        assertFailsWith<IllegalStateException> {
            host.backend.publish(caller, entry, listOf(addText("t1"), addText("t2"), addText("t3")))
        }
        assertEquals(1, host.logged().size, "one of three ops is on the board")
    }

    @Test
    fun aRelayFailingUnderTheBatchPublishesNothingAndTheRetryPublishesItWhole() = runTest {
        val store = FlakyStore().apply { allowedAppends = 0 }
        val host = HostCanvasComposeToolsTest.Host(store)

        val refused = refusal(host.compose(WEEKEND_PLAN, toolCallId = "call-1"))
        assertEquals(ComposeErrorCode.BOARD_REFUSED, refused.code)
        assertEquals(CanvasComposeService.PUBLISH_FAILED, refused.problems.single().code)
        assertTrue(host.logged().isEmpty())
        assertEquals(0L, host.scene().revision)

        store.allowedAppends = null
        val published = receipt(host.compose(WEEKEND_PLAN, toolCallId = "call-1"))
        assertEquals(ComposeStatus.PUBLISHED, published.status)
        assertIs<CanvasOp.BatchOp>(host.logged().single().op)
        assertEquals(host.scene().revision, published.revision)
    }

    @Test
    fun aFailureRightAfterOneAppendNeverSplitsTheArtifact() = runTest {
        // The relay takes exactly one more append and then fails: with the artifact as one op,
        // that one append is all of it.
        val store = FlakyStore()
        val host = HostCanvasComposeToolsTest.Host(store)
        store.allowedAppends = store.appends + 1
        val receipt = receipt(host.compose(WEEKEND_PLAN))
        val batch = assertIs<CanvasOp.BatchOp>(host.logged().single().op)
        val sceneJson = host.scene().sceneJson
        receipt.items.flatMap { listOf(it) + it.children.orEmpty() }.forEach {
            val id = it.boardId(receipt.artifactId)
            assertTrue("\"$id\"" in sceneJson, id)
        }
        assertTrue(batch.ops.size >= receipt.items.size)
    }

    @Test
    fun aLostAckIsRefusedAndTheRetryFindsTheArtifactOnTheBoard() = runTest {
        val store = FlakyStore().apply { loseTheAck = true }
        val host = HostCanvasComposeToolsTest.Host(store)

        assertEquals(ComposeErrorCode.BOARD_REFUSED, refusal(host.compose(WEEKEND_PLAN, toolCallId = "call-1")).code)
        assertEquals(1, host.logged().size, "the batch is durable; only its ack was lost")

        store.loseTheAck = false
        val retried = receipt(host.compose(WEEKEND_PLAN, toolCallId = "call-1"))
        assertEquals(ComposeStatus.PUBLISHED, retried.status)
        assertEquals(listOf(CanvasComposeService.ALREADY_PUBLISHED_WARNING), retried.warnings)
        assertEquals(1, host.logged().size, "the retry wrote nothing again")
        assertEquals(host.scene().revision, retried.revision)
    }
}
