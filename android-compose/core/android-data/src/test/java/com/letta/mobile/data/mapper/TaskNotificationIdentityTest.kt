package com.letta.mobile.data.mapper

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskNotificationIdentityTest {
    @Test
    fun backgroundCommandRetainsTransportIdentityAndTranscript() {
        val notification = extractSubagentNotification(
            """
            <task-notification>
                <task-id>exec_73</task-id>
                <status>completed</status>
                <summary>Install dev APK</summary>
                <usage>duration_ms: 37432</usage>
            </task-notification>
            Full transcript available at: /tmp/install.log
            """.trimIndent(),
        )
        assertEquals("exec_73", notification?.taskId)
        assertEquals(37432L, notification?.durationMs)
        assertEquals("/tmp/install.log", notification?.transcriptUri)
    }
}
