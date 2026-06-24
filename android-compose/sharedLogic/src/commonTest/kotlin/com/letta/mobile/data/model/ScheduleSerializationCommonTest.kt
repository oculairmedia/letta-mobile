package com.letta.mobile.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScheduleSerializationCommonTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Test
    fun `parse schedule with numeric fields`() {
        val jsonStr = """
            {
              "id": "s1",
              "agent_id": "a1",
              "message": {
                "messages": [
                  {
                    "content": "hello",
                    "role": "user"
                  }
                ],
                "max_steps": 10
              },
              "schedule": {
                "type": "one-time",
                "scheduled_at": 1700000000
              }
            }
        """.trimIndent()

        val parsed = json.decodeFromString<ScheduledMessage>(jsonStr)
        assertEquals(10.0, parsed.message.maxSteps)
        assertEquals(1700000000.0, parsed.schedule.scheduledAt)
    }

    @Test
    fun `parse schedule with missing optional fields`() {
        val jsonStr = """
            {
              "id": "s2",
              "agent_id": "a2",
              "message": {
                "messages": [
                  {
                    "content": "hello",
                    "role": "user"
                  }
                ]
              },
              "schedule": {
                "type": "recurring",
                "cron_expression": "0 0 * * *"
              }
            }
        """.trimIndent()

        val parsed = json.decodeFromString<ScheduledMessage>(jsonStr)
        assertNull(parsed.message.maxSteps)
        assertNull(parsed.message.callbackUrl)
        assertEquals("recurring", parsed.schedule.type)
        assertEquals("0 0 * * *", parsed.schedule.cronExpression)
        assertNull(parsed.schedule.scheduledAt)
        assertNull(parsed.nextScheduledTime)
    }

    @Test
    fun `parse schedule with empty messages array and return message types`() {
        val jsonStr = """
            {
              "id": "s3",
              "agent_id": "a3",
              "message": {
                "messages": [],
                "include_return_message_types": ["text", "image"]
              },
              "schedule": {
                "type": "cron"
              }
            }
        """.trimIndent()

        val parsed = json.decodeFromString<ScheduledMessage>(jsonStr)
        assertEquals(emptyList(), parsed.message.messages)
        assertEquals(listOf("text", "image"), parsed.message.includeReturnMessageTypes)
    }

    @Test
    fun `parse schedule with stringified numeric fields`() {
        val jsonStr = """
            {
              "id": "s4",
              "agent_id": "a4",
              "message": {
                "messages": [],
                "max_steps": "25"
              },
              "schedule": {
                "type": "one-time",
                "scheduled_at": "1700000000.5"
              }
            }
        """.trimIndent()

        val parsed = json.decodeFromString<ScheduledMessage>(jsonStr)
        assertEquals(25.0, parsed.message.maxSteps)
        assertEquals(1700000000.5, parsed.schedule.scheduledAt)
    }
}
