package com.letta.mobile.data.chat.projection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SubagentNotificationParserTest {

    @Test
    fun testParseWellFormed() {
        val raw = """
            <task-notification>
                <tool_call_id>call_123</tool_call_id>
                <status>completed</status>
                <summary>Processed data</summary>
                <result>Success</result>
                <usage>
                    duration_ms: 1500
                    tokens: 500
                </usage>
                <task_id>task_456</task_id>
                <agent_id>agent_789</agent_id>
                <transcript>file:///foo.txt</transcript>
            </task-notification>
            Full transcript available at: file:///foo2.txt
        """.trimIndent()
        
        val notification = extractSubagentNotification(raw)
        assertEquals("call_123", notification?.toolCallId)
        assertEquals("completed", notification?.status)
        assertEquals("Processed data", notification?.summary)
        assertEquals("Success", notification?.result)
        assertEquals("duration_ms: 1500\n        tokens: 500", notification?.usage)
        assertEquals(1500L, notification?.durationMs)
        assertEquals("task_456", notification?.taskId)
        assertEquals("agent_789", notification?.subagentAgentId)
        assertEquals("file:///foo.txt", notification?.transcriptUri)
    }

    @Test
    fun testParseMalformedEmpty() {
        val raw = "some random text"
        val notification = extractSubagentNotification(raw)
        assertNull(notification)
    }

    @Test
    fun testParseNoTags() {
        val raw = """
            <task-notification>
            </task-notification>
        """.trimIndent()
        val notification = extractSubagentNotification(raw)
        assertEquals(null, notification?.toolCallId)
        assertEquals("completed", notification?.status)
        assertEquals(null, notification?.summary)
        assertEquals(null, notification?.result)
        assertEquals(null, notification?.usage)
        assertEquals(null, notification?.durationMs)
        assertEquals(null, notification?.taskId)
        assertEquals(null, notification?.subagentAgentId)
        assertEquals(null, notification?.transcriptUri)
    }

    @Test
    fun testParseEdgeCases() {
        val raw = """
            <task-notification>
                <toolCallId>call_abc</toolCallId>
                <state>failed</state>
                <taskId>task_xyz</taskId>
                <agentId>agent_uvw</agentId>
            </task-notification>
            Full transcript available at: http://test.com
        """.trimIndent()
        val notification = extractSubagentNotification(raw)
        assertEquals("call_abc", notification?.toolCallId)
        assertEquals("failed", notification?.status)
        assertEquals("task_xyz", notification?.taskId)
        assertEquals("agent_uvw", notification?.subagentAgentId)
        assertEquals("http://test.com", notification?.transcriptUri)
    }
}
