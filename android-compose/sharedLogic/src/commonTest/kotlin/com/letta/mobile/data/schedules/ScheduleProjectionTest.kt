package com.letta.mobile.data.schedules

import com.letta.mobile.data.model.ScheduleDefinition
import com.letta.mobile.data.model.ScheduleMessage
import com.letta.mobile.data.model.SchedulePayload
import com.letta.mobile.data.model.ScheduledMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScheduleProjectionTest {

    private fun createMessage(
        type: String,
        cronExpression: String? = null,
        scheduledAt: Double? = null,
        nextScheduledTime: String? = null,
        messages: List<ScheduleMessage> = emptyList()
    ): ScheduledMessage {
        return ScheduledMessage(
            id = "test-id",
            agentId = "test-agent",
            message = SchedulePayload(messages = messages),
            schedule = ScheduleDefinition(
                type = type,
                cronExpression = cronExpression,
                scheduledAt = scheduledAt
            ),
            nextScheduledTime = nextScheduledTime
        )
    }

    @Test
    fun `test recurring schedule projection`() {
        val msg = createMessage(
            type = "recurring",
            cronExpression = "0 0 * * *",
            messages = listOf(ScheduleMessage(content = "recurring msg", role = "user"))
        )

        val item = msg.toScheduleLibraryItem()

        assertEquals("recurring msg", item.messagePreview)
        assertTrue(item.timing is ScheduleTiming.Recurring)
        assertEquals("0 0 * * *", (item.timing as ScheduleTiming.Recurring).cronExpression)
    }

    @Test
    fun `test one time schedule projection with nextScheduledTime`() {
        val msg = createMessage(
            type = "one_time",
            nextScheduledTime = "2024-01-01T00:00:00Z",
            messages = listOf(ScheduleMessage(content = "one time msg", role = "user"))
        )

        val item = msg.toScheduleLibraryItem()

        assertEquals("one time msg", item.messagePreview)
        assertTrue(item.timing is ScheduleTiming.OneTime)
        assertEquals("2024-01-01T00:00:00Z", (item.timing as ScheduleTiming.OneTime).displayTime)
    }

    @Test
    fun `test one time schedule projection fallback to scheduledAt`() {
        val msg = createMessage(
            type = "one_time",
            scheduledAt = 1704067200.0,
            messages = listOf(ScheduleMessage(content = "one time msg", role = "user"))
        )

        val item = msg.toScheduleLibraryItem()

        assertEquals("one time msg", item.messagePreview)
        assertTrue(item.timing is ScheduleTiming.OneTime)
        assertEquals("1.7040672E9", (item.timing as ScheduleTiming.OneTime).displayTime)
    }

    @Test
    fun `test missing messages`() {
        val msg = createMessage(type = "recurring", cronExpression = "0 0 * * *")

        val item = msg.toScheduleLibraryItem()

        assertEquals("", item.messagePreview)
    }
}
