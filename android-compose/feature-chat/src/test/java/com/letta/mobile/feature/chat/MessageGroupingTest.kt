package com.letta.mobile.feature.chat

import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiApprovalToolCall
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.common.GroupPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.chat.projection.deduplicateRenderKeys
import com.letta.mobile.data.chat.projection.groupMessagesForRender
import com.letta.mobile.data.chat.projection.runKey
import com.letta.mobile.feature.chat.screen.RunTimelineStep
import com.letta.mobile.feature.chat.screen.compactRunToolCallSteps

/**
 * Pure-JVM tests for [groupMessagesForRender] â€” verifies that contiguous
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
        // A run with only one message has no grouping benefit â€” emit Single
        // so we don't paint a degenerate gutter.
        val items = groupMessagesForRender(
            listOf(assistant("a1", runId = "r1") to GroupPosition.None),
        )
        assertEquals(1, items.size)
        assertTrue(items.single() is ChatRenderItem.Single)
    }

    @Test
    fun `two assistant messages sharing runId collapse into RunBlock`() {
        // Reversed input (newest first): a2, a1 â€” both runId=r1.
        val items = groupMessagesForRender(
            listOf(
                assistant("a2", runId = "r1") to GroupPosition.First,
                assistant("a1", runId = "r1") to GroupPosition.Last,
            ),
        )
        assertEquals(1, items.size)
        val block = items.single() as ChatRenderItem.RunBlock
        assertEquals("r1", block.runId)
        // Internal storage is chat order (oldest â†’ newest).
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
        // Reversed (newest â†’ oldest):
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
        // a3(r1), user(u2), a1(r1) â€” should produce three Singles, NOT one
        // RunBlock â€” the user message between them breaks contiguity.
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
        // Blank runIds shouldn't collapse â€” emit two Singles.
        assertEquals(2, items.size)
        items.forEach { assertTrue(it is ChatRenderItem.Single) }
    }

    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    // letta-mobile-w9l3 â€” stable LazyColumn key across the Singleâ†’RunBlock
    // transition that happens mid-stream when a sibling message in the same
    // run arrives. The grouper preemptively adopts `run-$runId` for an
    // assistant Single whose runId is unique in the snapshot, so when a
    // sibling lands and the item promotes to a RunBlock, the LazyColumn
    // slot identity is preserved and Compose reuses the composable instead
    // of unmountâ†’remount (which used to flash visibly).
    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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
        // The key MUST be identical across both snapshots â€” that's the
        // whole point of the w9l3 fix. If these diverge, LazyColumn will
        // unmount the old slot and remount a new one, causing the flash.
        assertEquals(before.single().key, after.single().key)
        assertEquals("run-r1", before.single().key)
        assertEquals("run-r1", after.single().key)
    }

    @Test
    fun `Single without runId still keys by message id`() {
        // User messages and untagged assistants have no runId â€” they MUST
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
        // a3(r1), user(u2), a1(r1) â€” same runId appears twice as Singles
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
    fun `contiguous assistants from different runIds do not merge into one RunBlock`() {
        // Three contiguous assistant messages â€” two sharing runId "r2" and
        // one in runId "r1" â€” must not merge across the run boundary.
        val items = groupMessagesForRender(
            listOf(
                assistant("a3b", runId = "r2") to GroupPosition.First,
                assistant("a3a", runId = "r2") to GroupPosition.Last,
                assistant("a1", runId = "r1") to GroupPosition.None,
            ),
        )
        assertEquals(2, items.size)
        val block = items[0] as ChatRenderItem.RunBlock
        assertEquals("r2", block.runId)
        assertEquals("run-r2", block.key)
        assertEquals(listOf("a3a", "a3b"), block.messages.map { it.first.id })

        val single = items[1] as ChatRenderItem.Single
        assertEquals("a1", single.message.id)
        assertEquals("run-r1", single.key)
    }

    @Test
    fun `current run block excludes prior-turn assistant messages from older runs`() {
        // Newest-first reproduction of letta-mobile-m1fpc:
        // current run C appears beside prior assistant messages A/B from older
        // runs in the same conversation. The current run's block must not
        // absorb those prior-run messages as steps.
        val items = groupMessagesForRender(
            listOf(
                assistant("c-2", runId = "run-c") to GroupPosition.First,
                assistant("c-1", runId = "run-c") to GroupPosition.Last,
                assistant("b", runId = "run-b") to GroupPosition.None,
                assistant("a", runId = "run-a") to GroupPosition.None,
            ),
        )

        assertEquals(3, items.size)
        val currentRun = items[0] as ChatRenderItem.RunBlock
        assertEquals("run-c", currentRun.runId)
        // letta-mobile-lkj4r: server id already starts with `run-`, so the key
        // must be `run-c`, never the double-prefixed `run-run-c`.
        assertEquals("run-c", currentRun.key)
        assertEquals(listOf("c-1", "c-2"), currentRun.messages.map { it.first.id })
        assertTrue(!currentRun.containsMessageId("a"))
        assertTrue(!currentRun.containsMessageId("b"))

        val priorRunB = items[1] as ChatRenderItem.Single
        val priorRunA = items[2] as ChatRenderItem.Single
        assertEquals("b", priorRunB.message.id)
        assertEquals("a", priorRunA.message.id)
    }

    @Test
    fun `repeated runId singles separated by other runs keep unique keys`() {
        // Search/highlight hydration can surface two separate assistant groups
        // whose newest message shares the same runId, but each is separated
        // by other runs. The Singles must keep unique message keys.
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
        assertEquals("msg-new-tool", items[0].key)
        assertEquals("run-r2", items[1].key)
        assertEquals("msg-u1", items[2].key)
        assertEquals("msg-old-tool", items[3].key)
        assertEquals("run-r3", items[4].key)
        items.forEach { assertTrue(it is ChatRenderItem.Single) }
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
    fun `run block drops plain assistant echoes already present as older chat messages`() {
        val duplicateText = "Good â€” predictable nights are the best kind."
        val items = groupMessagesForRender(
            listOf(
                assistant("run-tool", runId = "current", content = "about to inspect") to GroupPosition.First,
                assistant("run-echo", runId = "current", content = duplicateText) to GroupPosition.Middle,
                assistant("run-final", runId = "current", content = "Fresh final answer") to GroupPosition.Last,
                assistant("history", runId = "older", content = duplicateText) to GroupPosition.None,
            ),
        )

        assertEquals(2, items.size)
        val currentRun = items[0] as ChatRenderItem.RunBlock
        assertEquals(listOf("run-final", "run-tool"), currentRun.messages.map { it.first.id })
        assertEquals("history", (items[1] as ChatRenderItem.Single).message.id)
    }

    @Test
    fun `run block removes duplicate plain assistant text within the same block`() {
        val repeated = "Morning, Emmanuel. How'd you sleep?"
        val items = groupMessagesForRender(
            listOf(
                assistant("run-new", runId = "current", content = "Fresh final answer") to GroupPosition.First,
                assistant("run-dup-2", runId = "current", content = repeated) to GroupPosition.Middle,
                assistant("run-dup-1", runId = "current", content = repeated) to GroupPosition.Last,
            ),
        )

        val currentRun = items.single() as ChatRenderItem.RunBlock
        assertEquals(listOf("run-dup-2", "run-new"), currentRun.messages.map { it.first.id })
    }

    @Test
    fun `run block keeps short repeated assistant messages as intentional content`() {
        val items = groupMessagesForRender(
            listOf(
                assistant("run-repeat", runId = "current", content = "OK") to GroupPosition.First,
                assistant("run-final", runId = "current", content = "Fresh final answer") to GroupPosition.Last,
                assistant("history", runId = "older", content = "OK") to GroupPosition.None,
            ),
        )

        val currentRun = items[0] as ChatRenderItem.RunBlock
        assertEquals(listOf("run-final", "run-repeat"), currentRun.messages.map { it.first.id })
    }

    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    // letta-mobile-lkj4r â€” server run ids that already carry a `run-` prefix
    // must NOT be double-prefixed into `run-run-<id>` keys. A doubled key both
    // (a) looks wrong and (b) collides with a sibling that derived the
    // single-prefixed `run-<id>` form, crashing the LazyColumn with
    // "Key 'run-run-<id>' was already used."
    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    fun `RunBlock with already-prefixed runId is not double-prefixed`() {
        val items = groupMessagesForRender(
            listOf(
                assistant("a2", runId = "run-80aa0047") to GroupPosition.First,
                assistant("a1", runId = "run-80aa0047") to GroupPosition.Last,
            ),
        )
        val block = items.single() as ChatRenderItem.RunBlock
        // Key must be the run id verbatim â€” NOT `run-run-80aa0047`.
        assertEquals("run-80aa0047", block.key)
        assertTrue("key must not be double-run-prefixed", !block.key.startsWith("run-run-"))
    }

    @Test
    fun `unique Single with already-prefixed runId adopts non-doubled run key`() {
        val items = groupMessagesForRender(
            listOf(
                assistant("a1", runId = "run-80aa0047") to GroupPosition.None,
                user("u1") to GroupPosition.None,
            ),
        )
        val single = items[0] as ChatRenderItem.Single
        assertEquals("run-80aa0047", single.key)
        assertEquals("run-80aa0047", single.stableRunId)
        assertTrue("key must not be double-run-prefixed", !single.key.startsWith("run-run-"))
    }

    @Test
    fun `no render-item key is double-run-prefixed across a mixed streaming model`() {
        // A realistic mid-stream snapshot mixing: a multi-message run whose id
        // already starts with `run-`, a unique single-run (also `run-`-prefixed),
        // a plain run id (no prefix), a user message, and a streaming tail.
        val items = groupMessagesForRender(
            listOf(
                // streaming tail: current run, two assistant frames sharing id
                assistant("s2", runId = "run-80aa0047-a612") to GroupPosition.First,
                assistant("s1", runId = "run-80aa0047-a612") to GroupPosition.Last,
                user("u3") to GroupPosition.None,
                // unique single whose run id already carries the prefix
                assistant("a-solo", runId = "run-deadbeef") to GroupPosition.None,
                user("u2") to GroupPosition.None,
                // a run id WITHOUT the server prefix â€” must gain exactly one
                assistant("b2", runId = "plainrun") to GroupPosition.First,
                assistant("b1", runId = "plainrun") to GroupPosition.Last,
                user("u1") to GroupPosition.None,
            ),
        )

        val keys = items.map { it.key }

        // (1) All keys are unique â€” the actual LazyColumn crash condition.
        assertEquals("render keys must be unique: $keys", keys.size, keys.toSet().size)

        // (2) No key is double-`run-`-prefixed.
        keys.forEach { key ->
            assertTrue("key must not be double-run-prefixed: $key", !key.startsWith("run-run-"))
        }

        // (3) Specific keys are normalized as expected.
        assertEquals("run-80aa0047-a612", items[0].key)
        assertEquals("msg-u3", items[1].key)
        assertEquals("run-deadbeef", items[2].key)
        assertEquals("msg-u2", items[3].key)
        assertEquals("run-plainrun", items[4].key)
        assertEquals("msg-u1", items[5].key)
    }

    @Test
    fun `single-prefixed Single transitions to single-prefixed RunBlock with stable key`() {
        // The exact streaming scenario from the crash: an assistant frame in a
        // run whose server id already starts with `run-` lands first (unique â†’
        // Single), then a sibling arrives and promotes it to a RunBlock. The
        // key MUST stay identical AND must never be `run-run-<id>`, or the
        // LazyColumn unmounts the slot (flash) or crashes on the doubled key.
        val before = groupMessagesForRender(
            listOf(assistant("a1", runId = "run-80aa0047-a612") to GroupPosition.None),
        )
        val after = groupMessagesForRender(
            listOf(
                assistant("a2", runId = "run-80aa0047-a612") to GroupPosition.First,
                assistant("a1", runId = "run-80aa0047-a612") to GroupPosition.Last,
            ),
        )
        assertEquals("run-80aa0047-a612", before.single().key)
        assertEquals("run-80aa0047-a612", after.single().key)
        assertEquals(before.single().key, after.single().key)
        assertTrue(!before.single().key.startsWith("run-run-"))
        assertTrue(!after.single().key.startsWith("run-run-"))
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

    // letta-mobile-y70m0 (defensive hardening): even if two distinct render
    // items resolve to the SAME run id (a future regression, or the
    // self-todo constant-id frame slipping through), the LazyColumn keys
    // must still be globally unique so we degrade gracefully instead of
    // hard-crashing with `Key "run-<id>" was already used`.


    @Test
    fun `tool-call message with standalone content splits into distinct keys`() {
        val steps = compactRunToolCallSteps(
            listOf(
                assistantToolCall("tc1", command = "pwd", content = "Here is the result")
            )
        )
        val keys = steps.map { it.key }
        assertEquals("Keys must be distinct", keys.size, keys.toSet().size)
        assertTrue(keys.contains("tc1-content"))
        assertTrue(keys.contains("tc1"))
    }

    @Test
    fun `deduplicateRenderKeys keeps unique keys untouched`() {
        val a = ChatRenderItem.Single(assistant("a1", runId = "r1"), GroupPosition.None)
        val b = ChatRenderItem.Single(user("u1"), GroupPosition.None)
        val deduped = deduplicateRenderKeys(listOf(a, b))
        // No collision -> same instances returned, keys unchanged.
        assertEquals(listOf(a.key, b.key), deduped.map { it.key })
        assertEquals(2, deduped.map { it.key }.toSet().size)
    }

    @Test
    fun `deduplicateRenderKeys yields unique keys when two RunBlocks share a run id`() {
        val sharedRunId = "d1a81dfa-7575-455a-be37-9141619194fd"
        fun runBlock(idA: String, idB: String) = ChatRenderItem.RunBlock(
            runId = sharedRunId,
            messages = listOf(
                assistant(idA, runId = sharedRunId) to GroupPosition.None,
                assistant(idB, runId = sharedRunId) to GroupPosition.None,
            ),
        )
        val first = runBlock("m1", "m2")
        val second = runBlock("m3", "m4")
        // Both naturally resolve to the identical `run-<id>` key.
        assertEquals(first.key, second.key)

        val deduped = deduplicateRenderKeys(listOf(first, second))
        val keys = deduped.map { it.key }
        assertEquals("all render keys must be globally unique", keys.size, keys.toSet().size)
        // First occurrence keeps the canonical (single-prefixed) key â€” no
        // #337 regression â€” and the collision is disambiguated by item id.
        assertEquals(runKey(sharedRunId), keys[0])
        assertTrue(keys[1].startsWith(runKey(sharedRunId)))
        assertTrue(keys[1] != keys[0])
    }

    @Test
    fun `deduplicateRenderKeys disambiguates a Single colliding with a RunBlock on the same run id`() {
        val sharedRunId = "run-already-prefixed-collide"
        val single = ChatRenderItem.Single(
            assistant("s1", runId = sharedRunId),
            GroupPosition.None,
            stableRunKey = runKey(sharedRunId),
            stableRunId = sharedRunId,
        )
        val block = ChatRenderItem.RunBlock(
            runId = sharedRunId,
            messages = listOf(
                assistant("b1", runId = sharedRunId) to GroupPosition.None,
                assistant("b2", runId = sharedRunId) to GroupPosition.None,
            ),
        )
        assertEquals(single.key, block.key)

        val deduped = deduplicateRenderKeys(listOf(single, block))
        val keys = deduped.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        // #337: already-`run-`-prefixed ids must NOT get double-prefixed.
        assertTrue(keys.none { it.startsWith("run-run-") })
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
        content: String = "a-$id",
        ts: String = "2026-04-19T12:00:00Z",
    ) = UiMessage(
        id = id,
        role = "assistant",
        content = content,
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
