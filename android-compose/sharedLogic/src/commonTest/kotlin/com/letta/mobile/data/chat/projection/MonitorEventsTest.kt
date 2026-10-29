package com.letta.mobile.data.chat.projection

import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.data.model.extractSubagentNotification
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// letta-mobile-a7ir6.
/** A Monitor watch folds into one watcher chip; only actionable events keep a row. */
class MonitorEventsTest {

    @Test
    fun `a monitor event parses as an event and not a completion`() {
        val notification = notification(event("monitor_a", "CI 6/11"))

        assertEquals("event", notification.status)
        assertEquals("CI 6/11", notification.monitorEvent())
        assertFalse(notification.isMonitorWatcher())
        assertEquals(0, notification.monitorEventCount())
    }

    @Test
    fun `an ordinary task notification is not a monitor event`() {
        val notification = notification(ended("task-7", "completed", "Agent finished"))

        assertNull(notification.monitorEvent())
        assertFalse(notification.isMonitorWatcher())
    }

    @Test
    fun `routine words win over failure words`() {
        assertFalse(isActionableMonitorEvent("WatchPR recovered after 1 failed poll"))
        assertFalse(isActionableMonitorEvent("rate limited, backing off"))
        assertFalse(isActionableMonitorEvent("review comment edited"))
        assertFalse(isActionableMonitorEvent("Quality Gate Passed"))
        assertFalse(isActionableMonitorEvent("PR #2259 · CI 6/11"))
    }

    @Test
    fun `failures merges and reviews are actionable`() {
        assertTrue(isActionableMonitorEvent("required check test failed"))
        assertTrue(isActionableMonitorEvent("PR #2259 merged"))
        assertTrue(isActionableMonitorEvent("New review: changes requested"))
        assertTrue(isActionableMonitorEvent("merge conflict with main"))
    }

    @Test
    fun `a merge stays actionable when the event also reports passing checks`() {
        assertTrue(isActionableMonitorEvent("PR merged; checks passed"))
        assertTrue(isActionableMonitorEvent("merge conflict appeared while CI still running"))
        assertFalse(isActionableMonitorEvent("connection closed, retrying"))
    }

    @Test
    fun `forty routine events fold into one chip at the newest`() {
        val messages = listOf(monitorCall("monitor_a", "Watch PR #2259")) +
            (1..40).map { message("e$it", event("monitor_a", "CI $it/40")) }

        val folded = collapseMonitorEvents(messages)

        assertEquals(listOf("call", "e40"), folded.map { it.id })
        val chip = folded.last().subagentNotification!!
        assertTrue(chip.isMonitorWatcher())
        assertEquals("CI 40/40", chip.monitorEvent())
        assertEquals(40, chip.monitorEventCount())
        assertEquals("Watch PR #2259", chip.summary)
        assertEquals("running", chip.status)
    }

    @Test
    fun `an older actionable event keeps its row and the chip still counts it`() {
        val messages = listOf(
            message("e1", event("monitor_a", "CI 1/3")),
            message("e2", event("monitor_a", "required check lint failed")),
            message("e3", event("monitor_a", "recovered after 1 failed poll")),
        )

        val folded = collapseMonitorEvents(messages)

        assertEquals(listOf("e2", "e3"), folded.map { it.id })
        assertFalse(folded[0].subagentNotification!!.isMonitorWatcher())
        assertEquals(3, folded[1].subagentNotification!!.monitorEventCount())
    }

    @Test
    fun `the watch's own end marks the chip and keeps its row`() {
        val messages = listOf(
            message("e1", event("monitor_a", "CI 1/2")),
            message("end", ended("monitor_a", "failed", "Monitor \"Watch PR\" script failed (exit 1)")),
        )

        val folded = collapseMonitorEvents(messages)

        assertEquals(listOf("e1", "end"), folded.map { it.id })
        assertEquals("failed", folded[0].subagentNotification!!.status)
        assertTrue(folded[0].subagentNotification!!.isMonitorWatcher())
    }

    @Test
    fun `each watch gets its own chip`() {
        val messages = listOf(
            message("a1", event("monitor_a", "a one")),
            message("b1", event("monitor_b", "b one")),
            message("a2", event("monitor_a", "a two")),
        )

        val folded = collapseMonitorEvents(messages)

        assertEquals(listOf("b1", "a2"), folded.map { it.id })
    }

    @Test
    fun `a list without monitor events is returned as is`() {
        val messages = listOf(UiMessage("m1", "assistant", "hello", TIMESTAMP))

        assertSame(messages, collapseMonitorEvents(messages))
    }

    @Test
    fun `the in-place fold keeps positions for a page`() {
        val messages = listOf(
            message("e1", event("monitor_a", "CI 1/2")),
            UiMessage("m1", "assistant", "hello", TIMESTAMP),
            message("e2", event("monitor_a", "CI 2/2")),
        )

        val folded = foldMonitorEvents(messages)

        assertEquals(3, folded.size)
        assertNull(folded[0])
        assertSame(messages[1], folded[1])
        assertTrue(folded[2]!!.subagentNotification!!.isMonitorWatcher())
    }

    @Test
    fun `the render model draws one chip for a long watch`() {
        val messages = (1..12).map { message("e$it", event("monitor_a", "poll $it")) }

        val items = buildChatRenderModel(messages, ChatDisplayMode.Interactive).renderItems

        assertEquals(1, items.size)
    }

    @Test
    fun `the incremental cache refreshes a chip in committed history`() {
        val cache = IncrementalChatRenderItemsCache()
        val prompt = UiMessage("p", "user", "watch it", TIMESTAMP)
        val first = listOf(message("e1", event("monitor_a", "CI 1/2")), prompt)
        cache.renderItems(first, ChatDisplayMode.Interactive, ChatMessageListChange.Full)

        val second = listOf(message("e1", event("monitor_a", "CI 1/2")), prompt, message("e2", event("monitor_a", "CI 2/2")))
        val items = cache.renderItems(second, ChatDisplayMode.Interactive, ChatMessageListChange.AppendTail)

        val chips = items.flatMap { it.messages() }.mapNotNull { it.subagentNotification?.takeIf { n -> n.isMonitorWatcher() } }
        assertEquals(listOf("CI 2/2"), chips.map { it.monitorEvent() })
        assertEquals(2, chips.single().monitorEventCount())
    }

    private fun ChatRenderItem.messages(): List<UiMessage> = when (this) {
        is ChatRenderItem.Single -> listOf(message)
        is ChatRenderItem.RunBlock -> messages.map { it.first }
    }

    private fun notification(raw: String) = extractSubagentNotification(raw)!!

    private fun message(id: String, raw: String) =
        UiMessage(id, "assistant", "", TIMESTAMP, subagentNotification = notification(raw))

    private fun monitorCall(taskId: String, description: String) = UiMessage(
        id = "call",
        role = "assistant",
        content = "",
        timestamp = TIMESTAMP,
        toolCalls = listOf(
            UiToolCall(
                name = "Monitor",
                arguments = """{"description":"$description","command":"watch-pr 2259"}""",
                result = "Monitor started (task $taskId, timeout 3600000ms). You will be notified on each event.",
            ),
        ),
    )

    // letta-code 0.34.14 formatMonitorEventNotification.
    private fun event(taskId: String, text: String) = """
        <task-notification>
        <task-id>$taskId</task-id>
        <summary>Monitor event: &quot;$text&quot;</summary>
        <result>
        <event>$text</event>
        </result>
        </task-notification>
    """.trimIndent()

    // letta-code 0.34.14 formatTaskNotification.
    private fun ended(taskId: String, status: String, summary: String) = """
        <task-notification>
        <task-id>$taskId</task-id>
        <status>$status</status>
        <summary>$summary</summary>
        <result>Exit code: 1</result>
        </task-notification>
    """.trimIndent()

    private companion object {
        const val TIMESTAMP = "2026-10-29T03:46:00Z"
    }
}
