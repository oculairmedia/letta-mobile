package com.letta.mobile.ui.screens.chat

import com.letta.mobile.data.model.UiMessage
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
    fun `Single shares key with RunBlock when both reference same runId is impossible by construction`() {
        // Sanity: the grouper produces *either* a Single or a RunBlock for
        // a given runId in a snapshot, never both. With one assistant in
        // run r1 and a contiguous pair in run r2, we expect:
        //   Single(run-r1), RunBlock(run-r2) — no key collision.
        val items = groupMessagesForRender(
            listOf(
                assistant("a3b", runId = "r2") to GroupPosition.First,
                assistant("a3a", runId = "r2") to GroupPosition.Last,
                assistant("a1", runId = "r1") to GroupPosition.None,
            ),
        )
        val keys = items.map { it.key }
        assertEquals(listOf("run-r2", "run-r1"), keys)
        assertEquals(keys.size, keys.toSet().size)
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
}
