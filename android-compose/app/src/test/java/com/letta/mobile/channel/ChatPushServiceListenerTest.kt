package com.letta.mobile.channel

import com.letta.mobile.data.api.MessageApi
import com.letta.mobile.data.timeline.IngestedMessageListener
import com.letta.mobile.data.timeline.NoOpConversationCursorStore
import com.letta.mobile.data.timeline.NoOpPendingLocalStore
import com.letta.mobile.data.timeline.TimelineRepository
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPushServiceListenerTest {

    @Test
    fun `clear active installed listener releases repository reference`() {
        val repository = newRepository()
        val installedListener = testListener()

        val installed = installIngestedListener(repository, installedListener)
        val cleared = clearIngestedListenerIfActive(repository, installed)

        assertTrue(cleared)
        assertNull(repository.ingestedListener)
    }

    @Test
    fun `clear active listener leaves replacement listener installed`() {
        val repository = newRepository()
        val destroyedServiceListener = testListener()
        val replacementListener = testListener()

        installIngestedListener(repository, destroyedServiceListener)
        repository.ingestedListener = replacementListener

        val cleared = clearIngestedListenerIfActive(repository, destroyedServiceListener)

        assertFalse(cleared)
        assertSame(replacementListener, repository.ingestedListener)
    }

    private fun newRepository(): TimelineRepository =
        TimelineRepository(
            messageApi = mockk<MessageApi>(relaxed = true),
            pendingLocalStore = NoOpPendingLocalStore,
            conversationCursorStore = NoOpConversationCursorStore,
            sessionManager = null,
        )

    private fun testListener(): IngestedMessageListener = object : IngestedMessageListener {
        override suspend fun onMessageIngested(
            conversationId: String,
            serverId: String,
            messageType: String?,
            contentPreview: String?,
        ) = Unit
    }
}
