package com.letta.mobile.channel

import androidx.test.core.app.ApplicationProvider
import com.letta.mobile.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChannelNotificationPublisherTest {

    @Test
    fun `single-character preview resolves to fallback content`() {
        val publisher = ChannelNotificationPublisher(ApplicationProvider.getApplicationContext())

        val content = publisher.resolveContent(notification(messagePreview = "I"))

        assertEquals(
            ApplicationProvider.getApplicationContext<android.content.Context>()
                .getString(R.string.channel_notifications_fallback_text),
            content.messageText,
        )
        assertEquals(NotificationTextFallbackReason.TooShort, content.preview.fallbackReason)
    }

    @Test
    fun `title and body are normalized before notification rendering`() {
        val publisher = ChannelNotificationPublisher(ApplicationProvider.getApplicationContext())

        val content = publisher.resolveContent(
            notification(
                agentName = "\u0000 Ada  ",
                messagePreview = "  Hello\nthere\u0000 ",
            ),
        )

        assertEquals("Ada", content.title)
        assertEquals("Hello there", content.messageText)
        assertEquals(NotificationTextFallbackReason.None, content.preview.fallbackReason)
    }

    private fun notification(
        agentName: String = "Ada",
        messagePreview: String = "Hello",
    ) = ChannelNotification(
        agentId = "agent-1",
        agentName = agentName,
        conversationId = "conversation-1",
        conversationSummary = "Main thread",
        messageId = "message-1",
        messagePreview = messagePreview,
    )
}
