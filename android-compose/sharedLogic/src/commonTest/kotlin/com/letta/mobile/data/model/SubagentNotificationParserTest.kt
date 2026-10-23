package com.letta.mobile.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SubagentNotificationParserTest {

    @Test
    fun resultHeaderAgentIdIgnoresLaterProseAndPreservesTagPrecedence() {
        val child = "codex_01a1686b-8ec9-7f72-8e79-bd42f1ff3237"
        val raw = """
            <task-notification>
            <task-id>task_15</task-id>
            <status>completed</status>
            <summary>Finished background task</summary>
            <result>subagent_type=codex subagent_id=subagent-1792753239807-15 subagent_status=success agent_id=$child runtime_session_id=session-15
            Result prose mentions agent_id=claude-code-older-child as an example.
            </result>
            </task-notification>
        """.trimIndent()

        assertEquals(child, extractSubagentNotification(raw)?.subagentAgentId)
        assertNull(extractSubagentNotification(raw.replace(" agent_id=$child", ""))?.subagentAgentId)
        assertNull(extractSubagentNotification(raw.replace(" agent_id=$child", " parent_agent_id=$child"))?.subagentAgentId)
        for (tag in listOf("agent_id", "agentId")) {
            val tagged = raw.replace("<task-id>", "<$tag>tagged-child</$tag>\n<task-id>")
            assertEquals("tagged-child", extractSubagentNotification(tagged)?.subagentAgentId)
        }
    }

    @Test
    fun transcriptLineInsideLegacyNotificationIsPreserved() {
        val raw = """
            <task-notification>
                <status>success</status>
                <task_id>task-123</task_id>
                Full transcript at: task://task-123
            </task-notification>
        """.trimIndent()
        assertEquals("task://task-123", extractSubagentNotification(raw)?.transcriptUri)
    }

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
