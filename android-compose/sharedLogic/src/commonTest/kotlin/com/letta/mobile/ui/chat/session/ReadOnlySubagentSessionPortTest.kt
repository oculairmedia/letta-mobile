package com.letta.mobile.ui.chat.session

import com.letta.mobile.data.model.UiMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** letta-mobile-zxk2n.8: a subagent's conversation in the dual view is read-only. */
class ReadOnlySubagentSessionPortTest {
    @Test
    fun composerTakesNoInputAndSaysToReplyInTheParent() = runTest {
        val owner = FakeChatSessionPort()
        val port = ReadOnlySubagentSessionPort(owner, NOTICE, backgroundScope)

        val composer = port.composer.value
        assertFalse(composer.acceptsInput)
        assertFalse(composer.canSend)
        assertEquals(NOTICE, composer.placeholder)
    }

    @Test
    fun nothingIsWrittenToTheChildConversation() = runTest {
        val owner = FakeChatSessionPort()
        val port = ReadOnlySubagentSessionPort(owner, NOTICE, backgroundScope)

        port.actions.updateComposerText("hello child")
        port.actions.send()
        port.actions.sendText("starter")
        port.actions.rerun(UiMessage(id = "m1", role = "user", content = "again", timestamp = "2026-10-16T00:00:00Z"))

        assertEquals(emptyList(), owner.sent)
        assertEquals("", owner.composer.value.text)
    }

    @Test
    fun writeCapabilitiesAreOffAndReadingStays() = runTest {
        val owner = FakeChatSessionPort().apply {
            capabilityState.value = ChatSurfaceCapabilities(fork = true, editAndResend = true, rerunInFork = true, search = true)
        }
        val port = ReadOnlySubagentSessionPort(owner, NOTICE, backgroundScope)

        val capabilities = port.capabilities.value
        assertFalse(capabilities.attachImages)
        assertFalse(capabilities.rerun)
        assertFalse(capabilities.modelSwitch)
        assertFalse(capabilities.fork || capabilities.editAndResend || capabilities.rerunInFork)
        assertTrue(capabilities.search)
        assertTrue(capabilities.pagedHistory)
    }

    private companion object {
        const val NOTICE = "Viewing a subagent. Reply in the parent conversation."
    }
}
