package com.letta.mobile.channel

import com.letta.mobile.data.timeline.IngestedMessageListener
import com.letta.mobile.data.timeline.NoOpConversationCursorStore
import com.letta.mobile.data.timeline.NoOpPendingLocalStore
import com.letta.mobile.data.timeline.TimelineRepository
import com.letta.mobile.data.timeline.TimelineTransport
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPushServiceListenerTest {

    @Test
    fun `clear active installed listener releases repository reference`() = runTest {
        val repository = newRepository(backgroundScope)
        val installedListener = testListener()

        val installed = installIngestedListener(repository, installedListener)
        val cleared = clearIngestedListenerIfActive(repository, installed)

        assertTrue(cleared)
        assertNull(repository.ingestedListener)
    }

    @Test
    fun `clear active listener leaves replacement listener installed`() = runTest {
        val repository = newRepository(backgroundScope)
        val destroyedServiceListener = testListener()
        val replacementListener = testListener()

        installIngestedListener(repository, destroyedServiceListener)
        repository.ingestedListener = replacementListener

        val cleared = clearIngestedListenerIfActive(repository, destroyedServiceListener)

        assertFalse(cleared)
        assertSame(replacementListener, repository.ingestedListener)
    }

    private fun newRepository(repositoryScope: CoroutineScope): TimelineRepository =
        TimelineRepository(
            timelineTransport = mockk<TimelineTransport>(relaxed = true),
            pendingLocalStore = NoOpPendingLocalStore,
            conversationCursorStore = NoOpConversationCursorStore,
            repositoryScope = repositoryScope,
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
