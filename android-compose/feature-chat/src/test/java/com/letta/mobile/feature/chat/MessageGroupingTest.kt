package com.letta.mobile.feature.chat

import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiApprovalToolCall
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.common.GroupPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [groupMessagesForRender] — verifies that contiguous
 * assistant messages sharing a `runId` collapse into [ChatRenderItem.RunBlock]
 * entries while everything else stays as [ChatRenderItem.Single].
 *
 * letta-mobile-m772.2
 */
class MessageGroupingTest {

    @Test
    fun `empty input returns empty output`() {
        assertEquals(emptyList<ChatRenderItem>(), groupMessagesForRender(emptyList()))
    }

    @Test
    fun `single user message renders as Single`() {
        val items = groupMessagesForRender(
            listOf(user("u1") to GroupPosition.None),
        )
        assertEquals(1, items.size)
        val first = items.single()
        assertTrue(first is ChatRenderItem.Single)
        assertEquals("u1", (first as ChatRenderItem.Single).message.id)
    }

    @Test
    fun `assistant message without runId renders as Single`() {
        val items = groupMessagesForRender(
            listOf(assistant("a1", runId = null) to GroupPosition.None),
        )
        assertEquals(1, items.size)
        assertTrue(items.single() is ChatRenderItem.Single)
    }

    @Test
    fun `single-message run renders as Single not RunBlock`() {
        // A run with only one message has no grouping benefit — emit Single
        // so we don't paint a degenerate gutter.
        val items = groupMessagesForRender(
            listOf(assistant("a1", runId = "r1") to GroupPosition.None),
        )
        assertEquals(1, items.size)
        assertTrue(items.single() is ChatRenderItem.Single)
    }

    @Test
    fun `two assistant messages sharing runId collapse into RunBlock`() {
        // Reversed input (newest first): a2, a1 — both runId=r1.
        val items = groupMessagesForRender(
            listOf(
                assistant("a2", runId = "r1") to GroupPosition.First,
                assistant("a1", runId = "r1") to GroupPosition.Last,
            ),
        )
        assertEquals(1, items.size)
        val block = items.single() as ChatRenderItem.RunBlock
        assertEquals("r1", block.runId)
        // Internal storage is chat order (oldest → newest).
        assertEquals(listOf("a1", "a2"), block.messages.map { it.first.id })
    }

    @Test
    fun `RunBlock key and contains lookup are stable`() {
        val items = groupMessagesForRender(
            listOf(
                assistant("a2", runId = "r1") to GroupPosition.First,
                assistant("a1", runId = "r1") to GroupPosition.Last,
            ),
        )
        val block = items.single() as ChatRenderItem.RunBlock
        assertEquals("run-r1", block.key)
        assertTrue(block.containsMessageId("a1"))
        assertTrue(block.containsMessageId("a2"))
        assertTrue(!block.containsMessageId("nope"))
    }

    @Test
    fun `mixed conversation interleaves Singles and RunBlocks correctly`() {
        // Reversed (newest → oldest):
        //   user "u3"
        //   assistant "a2b" runId=r2
        //   assistant "a2a" runId=r2
        //   user "u2"
        //   assistant "a1" runId=r1 (singleton)
        //   user "u1"
        val items = groupMessagesForRender(
            listOf(
                user("u3") to GroupPosition.None,
                assistant("a2b", runId = "r2") to GroupPosition.First,
                assistant("a2a", runId = "r2") to GroupPosition.Last,
                user("u2") to GroupPosition.None,
                assistant("a1", runId = "r1") to GroupPosition.None,
                user("u1") to GroupPosition.None,
            ),
        )
        assertEquals(5, items.size)
        assertEquals("msg-u3", items[0].key)
        assertEquals("run-r2", items[1].key)
        assertEquals("msg-u2", items[2].key)
        // letta-mobile-w9l3: a1's runId (r1) is unique in the snapshot, so
        // the Single adopts `run-r1` to keep the LazyColumn slot stable
        // when a sibling later promotes it to a RunBlock mid-stream.
        assertEquals("run-r1", items[3].key)
        assertEquals("msg-u1", items[4].key)
    }

    @Test
    fun `non-contiguous runIds do not merge across other messages`() {
        // a3(r1), user(u2), a1(r1) — should produce three Singles, NOT one
        // RunBlock — the user message between them breaks contiguity.
        val items = groupMessagesForRender(
            listOf(
                assistant("a3", runId = "r1") to GroupPosition.None,
                user("u2") to GroupPosition.None,
                assistant("a1", runId = "r1") to GroupPosition.None,
            ),
        )
        assertEquals(3, items.size)
        items.forEach { assertTrue(it is ChatRenderItem.Single) }
    }

    @Test
    fun `boundaryTimestamp on RunBlock returns the newest member`() {
        val items = groupMessagesForRender(
            listOf(
                assistant("a2", runId = "r1", ts = "2026-04-19T12:00:30Z") to GroupPosition.First,
                assistant("a1", runId = "r1", ts = "2026-04-19T12:00:00Z") to GroupPosition.Last,
            ),
        )
        val block = items.single() as ChatRenderItem.RunBlock
        assertEquals("2026-04-19T12:00:30Z", block.boundaryTimestamp)
    }

    @Test
    fun `assistant runId blank is treated as null`() {
        val items = groupMessagesForRender(
            listOf(
                assistant("a2", runId = "") to GroupPosition.None,
                assistant("a1", runId = "") to GroupPosition.None,
            ),
        )
        // Blank runIds shouldn't collapse — emit two Singles.
        assertEquals(2, items.size)
        items.forEach { assertTrue(it is ChatRenderItem.Single) }
    }

    // ──────────────────────────────────────────────────────────────────────
    // letta-mobile-w9l3 — stable LazyColumn key across the Single→RunBlock
    // transition that happens mid-stream when a sibling message in the same
    // run arrives. The grouper preemptively adopts `run-$runId` for an
    // assistant Single whose runId is unique in the snapshot, so when a
    // sibling lands and the item promotes to a RunBlock, the LazyColumn
    // slot identity is preserved and Compose reuses the composable instead
    // of unmount→remount (which used to flash visibly).
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `Single with unique runId adopts run key for stable transition`() {
        // Mid-stream snapshot 1: only the assistant message has landed so
        // far. It MUST be keyed `run-$runId` so the next snapshot (when a
        // sibling joins and promotes it to a RunBlock with the same key)
        // doesn't trigger an unmount+remount.
        val items = groupMessagesForRender(
            listOf(
                assistant("a1", runId = "r1") to GroupPosition.None,
                user("u1") to GroupPosition.None,
            ),
        )
        val single = items[0] as ChatRenderItem.Single
        assertEquals("run-r1", single.key)
    }

    @Test
    fun `Single key matches RunBlock key after sibling arrives`() {
        // Snapshot 1: solo assistant message in a run.
        val before = groupMessagesForRender(
            listOf(assistant("a1", runId = "r1") to GroupPosition.None),
        )
        // Snapshot 2: a sibling message lands in the same run, promoting
        // the item to a RunBlock.
        val after = groupMessagesForRender(
            listOf(
                assistant("a2", runId = "r1") to GroupPosition.First,
                assistant("a1", runId = "r1") to GroupPosition.Last,
            ),
        )
        // The key MUST be identical across both snapshots — that's the
        // whole point of the w9l3 fix. If these diverge, LazyColumn will
        // unmount the old slot and remount a new one, causing the flash.
        assertEquals(before.single().key, after.single().key)
        assertEquals("run-r1", before.single().key)
        assertEquals("run-r1", after.single().key)
    }

    @Test
    fun `Single without runId still keys by message id`() {
        // User messages and untagged assistants have no runId — they MUST
        // fall back to the message-id key, never a run key.
        val items = groupMessagesForRender(
            listOf(
                user("u1") to GroupPosition.None,
                assistant("a1", runId = null) to GroupPosition.None,
            ),
        )
        assertEquals("msg-u1", items[0].key)
        assertEquals("msg-a1", items[1].key)
    }

    @Test
    fun `non-contiguous duplicate runIds keep msg key to avoid LazyColumn collision`() {
        // a3(r1), user(u2), a1(r1) — same runId appears twice as Singles
        // separated by a non-matching message. Adopting `run-r1` on both
        // would produce two LazyColumn items with the same key, which
        // crashes Compose. The grouper MUST detect this and keep
        // `msg-${id}` for safety.
        val items = groupMessagesForRender(
            listOf(
                assistant("a3", runId = "r1") to GroupPosition.None,
                user("u2") to GroupPosition.None,
                assistant("a1", runId = "r1") to GroupPosition.None,
            ),
        )
        val keys = items.map { it.key }
        // No two items may share a key.
        assertEquals(keys.size, keys.toSet().size)
        // Specifically, the two r1 Singles must keep msg- keys.
        assertEquals("msg-a3", items[0].key)
        assertEquals("msg-u2", items[1].key)
        assertEquals("msg-a1", items[2].key)
    }

    @Test
    fun `contiguous assistants merge into one RunBlock regardless of runId`() {
        // Three contiguous assistant messages — two sharing runId "r2" and
        // one in runId "r1" — merge into a single RunBlock keyed by the
        // first (newest) runId encountered: "r2".
        val items = groupMessagesForRender(
            listOf(
                assistant("a3b", runId = "r2") to GroupPosition.First,
                assistant("a3a", runId = "r2") to GroupPosition.Last,
                assistant("a1", runId = "r1") to GroupPosition.None,
            ),
        )
        assertEquals(1, items.size)
        val block = items.single() as ChatRenderItem.RunBlock
        assertEquals("r2", block.runId)
        assertEquals("run-r2", block.key)
        assertEquals(listOf("a1", "a3a", "a3b"), block.messages.map { it.first.id })
    }

    @Test
    fun `non-contiguous RunBlocks with repeated leading runId get unique keys`() {
        // Search/highlight hydration can surface two separate assistant groups
        // whose newest message shares the same runId, while each group also
        // contains tool/reasoning sub-runs. The groups must remain collapsed
        // for realtime tool visibility, but their LazyColumn keys cannot both
        // be `run-r1`.
        val items = groupMessagesForRender(
            listOf(
                assistant("new-tool", runId = "r1") to GroupPosition.First,
                assistant("new-text", runId = "r2") to GroupPosition.Last,
                user("u1") to GroupPosition.None,
                assistant("old-tool", runId = "r1") to GroupPosition.First,
                assistant("old-text", runId = "r3") to GroupPosition.Last,
            ),
        )

        val keys = items.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals("run-r1-new-tool", items[0].key)
        assertEquals("msg-u1", items[1].key)
        assertEquals("run-r1-old-tool", items[2].key)
        assertTrue(items[0] is ChatRenderItem.RunBlock)
        assertTrue(items[2] is ChatRenderItem.RunBlock)
    }

    @Test
    fun `cross-runId assistants do not merge across user messages`() {
        // User message breaks assistant contiguity.
        val items = groupMessagesForRender(
            listOf(
                assistant("a2", runId = "r2") to GroupPosition.None,
                user("u1") to GroupPosition.None,
                assistant("a1", runId = "r1") to GroupPosition.None,
            ),
        )
        assertEquals(3, items.size)
        items.forEach { assertTrue(it is ChatRenderItem.Single) }
    }

    @Test
    fun `consecutive tool-call messages compact into one run timeline step`() {
        val steps = compactRunToolCallSteps(
            listOf(
                assistantToolCall("tc1", command = "pwd"),
                assistantToolCall("tc2", command = "ls"),
                assistant("a1", runId = "r1"),
            ),
        )

        assertEquals(2, steps.size)
        val group = steps.first() as RunTimelineStep.ToolCallGroup
        assertEquals(listOf("tc1", "tc2"), group.messages.map { it.id })
        assertEquals(listOf("call-tc1", "call-tc2"), group.toolCalls.map { it.toolCallId })
        assertEquals("a1", (steps[1] as RunTimelineStep.Message).message.id)
    }

    @Test
    fun `tool-call group key stays stable as more calls append`() {
        val initialGroup = compactRunToolCallSteps(
            listOf(
                assistantToolCall("tc1", command = "pwd"),
                assistantToolCall("tc2", command = "ls"),
            ),
        ).single() as RunTimelineStep.ToolCallGroup

        val appendedGroup = compactRunToolCallSteps(
            listOf(
                assistantToolCall("tc1", command = "pwd"),
                assistantToolCall("tc2", command = "ls"),
                assistantToolCall("tc3", command = "date"),
            ),
        ).single() as RunTimelineStep.ToolCallGroup

        assertEquals(initialGroup.key, appendedGroup.key)
    }

    @Test
    fun `consecutive tool-call compaction carries pending approval request`() {
        val approval = UiApprovalRequest(
            requestId = "approval-1",
            toolCalls = listOf(
                UiApprovalToolCall(
                    toolCallId = "call-tc1",
                    name = "Bash",
                    arguments = """{"command":"pwd"}""",
                ),
                UiApprovalToolCall(
                    toolCallId = "call-tc2",
                    name = "Bash",
                    arguments = """{"command":"ls"}""",
                ),
            ),
        )

        val steps = compactRunToolCallSteps(
            listOf(
                assistantToolCall("tc1", command = "pwd", approvalRequest = approval),
                assistantToolCall("tc2", command = "ls"),
            ),
        )

        val group = steps.single() as RunTimelineStep.ToolCallGroup
        assertEquals(setOf("call-tc1", "call-tc2"), group.pendingApprovalToolCallIds)
        assertEquals(listOf("approval-1"), group.approvalRequests.map { it.requestId })
    }

    @Test
    fun `run tool-call compaction preserves non-empty content as its own step`() {
        val steps = compactRunToolCallSteps(
            listOf(
                assistantToolCall("tc1", command = "pwd"),
                assistantToolCall("tc2", command = "ls", content = "about to run ls"),
                assistantToolCall("tc3", command = "cat file"),
            ),
        )

        assertEquals(3, steps.size)
        val firstTool = steps[0] as RunTimelineStep.Message
        val text = steps[1] as RunTimelineStep.Message
        val group = steps[2] as RunTimelineStep.ToolCallGroup
        assertEquals(listOf("tc1"), listOf(firstTool.message.id))
        assertEquals("about to run ls", text.message.content)
        assertTrue(text.message.toolCalls.isNullOrEmpty())
        assertEquals(listOf("tc2", "tc3"), group.messages.map { it.id })
        assertEquals(listOf("call-tc2", "call-tc3"), group.toolCalls.map { it.toolCallId })
    }

    @Test
    fun `first tool-call message with preamble still joins following compact group`() {
        val steps = compactRunToolCallSteps(
            listOf(
                assistantToolCall("tc1", command = "pwd", content = "I'll inspect the environment."),
                assistantToolCall("tc2", command = "date"),
                assistantToolCall("tc3", command = "whoami"),
                assistant("a1", runId = "r1"),
            ),
        )

        assertEquals(3, steps.size)
        val preamble = steps[0] as RunTimelineStep.Message
        val group = steps[1] as RunTimelineStep.ToolCallGroup
        assertEquals("I'll inspect the environment.", preamble.message.content)
        assertTrue(preamble.message.toolCalls.isNullOrEmpty())
        assertEquals(listOf("tc1", "tc2", "tc3"), group.messages.map { it.id })
        assertEquals(listOf("call-tc1", "call-tc2", "call-tc3"), group.toolCalls.map { it.toolCallId })
        assertEquals("a1", (steps[2] as RunTimelineStep.Message).message.id)
    }

    private fun user(id: String, ts: String = "2026-04-19T12:00:00Z") = UiMessage(
        id = id,
        role = "user",
        content = "u-$id",
        timestamp = ts,
    )

    private fun assistant(
        id: String,
        runId: String?,
        ts: String = "2026-04-19T12:00:00Z",
    ) = UiMessage(
        id = id,
        role = "assistant",
        content = "a-$id",
        timestamp = ts,
        runId = runId,
    )

    private fun assistantToolCall(
        id: String,
        command: String,
        content: String = "",
        ts: String = "2026-04-19T12:00:00Z",
        approvalRequest: UiApprovalRequest? = null,
    ) = UiMessage(
        id = id,
        role = "assistant",
        content = content,
        timestamp = ts,
        runId = "r1",
        approvalRequest = approvalRequest,
        toolCalls = listOf(
            UiToolCall(
                name = "Bash",
                arguments = """{"command":"$command"}""",
                result = null,
                toolCallId = "call-$id",
            )
        ),
    )
}
