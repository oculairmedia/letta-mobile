package com.letta.mobile.data.chat.runtime

import com.letta.mobile.data.attachment.AttachmentLimits
import com.letta.mobile.data.model.MessageContentPart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * bead letta-mobile-6oxlf acceptance (b): verifies that the shared [ChatComposerError]
 * enum values and their triggering conditions are stable and complete so platform
 * adapters (Android's ChatComposerController, Desktop's toDesktopMessage) can map
 * them to user-facing strings without risk of a missing-case crash.
 *
 * Acceptance (c): also covers send-state behaviour when pending attachments are
 * present — in particular, that [ChatComposerPolicy.beginSend] includes
 * attachments and that the SendFailed path restores both text AND attachments
 * so the user can retry without re-picking images.
 */
class ChatComposerErrorMessagesTest {

    // ---- (b) error enum completeness and triggering conditions ----

    @Test
    fun allThreeErrorVariantsAreDistinctAndExhaustive() {
        // Compile-time guarantee: when() is exhaustive on sealed enum — if a
        // new variant is added without updating both platform adapters this
        // test will fail to compile, surfacing the gap immediately.
        val allErrors = ChatComposerError.entries
        val names = allErrors.map { error ->
            when (error) {
                ChatComposerError.MaxAttachmentCountExceeded -> "MaxAttachmentCountExceeded"
                ChatComposerError.MaxTotalBase64BytesExceeded -> "MaxTotalBase64BytesExceeded"
                ChatComposerError.AttachmentLoadFailed -> "AttachmentLoadFailed"
            }
        }
        assertEquals(3, allErrors.size, "Expected exactly three ChatComposerError variants; update platform adapters if this grows")
        assertTrue(names.contains("MaxAttachmentCountExceeded"))
        assertTrue(names.contains("MaxTotalBase64BytesExceeded"))
        assertTrue(names.contains("AttachmentLoadFailed"))
    }

    @Test
    fun countCapErrorTriggeredByOneImageOverLimit() {
        val limits = AttachmentLimits(maxAttachmentCount = 1)
        val withOne = ChatComposerPolicy.attachImage(ChatComposerState(), image("A"), limits)
        assertNull(withOne.error, "First attachment within limit should have no error")

        val rejected = ChatComposerPolicy.attachImage(withOne, image("B"), limits)
        assertEquals(ChatComposerError.MaxAttachmentCountExceeded, rejected.error)
        assertEquals(1, rejected.pendingImageAttachments.size, "Rejected image must not be added")
    }

    @Test
    fun sizeCapErrorTriggeredWhenTotalBase64ExceedsCap() {
        val limits = AttachmentLimits(maxTotalBase64Bytes = 4)
        val accepted = ChatComposerPolicy.attachImage(ChatComposerState(), image("AAAA"), limits)
        assertNull(accepted.error)

        val rejected = ChatComposerPolicy.attachImage(accepted, image("B"), limits)
        assertEquals(ChatComposerError.MaxTotalBase64BytesExceeded, rejected.error)
        assertEquals(1, rejected.pendingImageAttachments.size)
    }

    @Test
    fun loadFailedErrorSetDirectlyByShowAttachmentLoadFailed() {
        val withError = ChatComposerPolicy.showAttachmentLoadFailed(ChatComposerState())
        assertEquals(ChatComposerError.AttachmentLoadFailed, withError.error)
    }

    @Test
    fun removingAttachmentClearsAnyExistingError() {
        val state = ChatComposerState(
            pendingImageAttachments = listOf(image("X")),
            error = ChatComposerError.AttachmentLoadFailed,
        )
        val afterRemove = ChatComposerPolicy.removeImageAttachment(state, 0)
        assertNull(afterRemove.error)
        assertTrue(afterRemove.pendingImageAttachments.isEmpty())
    }

    // ---- (c) send-state behaviour with pending attachments ----

    @Test
    fun beginSendIncludesAllPendingAttachmentsInDraft() {
        val images = listOf(image("img1"), image("img2"))
        val state = ChatComposerState(
            text = "look at these",
            pendingImageAttachments = images,
        )
        val draft = ChatComposerPolicy.beginSend(state)!!

        assertEquals("look at these", draft.text)
        assertEquals(images, draft.attachments)
        assertEquals(ChatComposerState(), draft.nextState, "Composer must be cleared after send")
    }

    @Test
    fun beginSendWithAttachmentsOnlyAndBlankTextProducesDraft() {
        val state = ChatComposerState(
            text = "   ",
            pendingImageAttachments = listOf(image("imgA")),
        )
        val draft = ChatComposerPolicy.beginSend(state)!!

        assertEquals("", draft.text, "Trimmed blank text must be empty string in draft")
        assertEquals(listOf(image("imgA")), draft.attachments)
    }

    @Test
    fun sendFailureRestoresBothTextAndAttachmentsEnablingRetry() {
        val images = listOf(image("retry-img"))
        val restored = ChatComposerPolicy.restoreAfterSendFailure(
            text = "pending retry",
            attachments = images,
        )

        assertEquals("pending retry", restored.text)
        assertEquals(images, restored.pendingImageAttachments)
        assertNull(restored.error)
        assertTrue(restored.hasPayload, "Restored composer must report hasPayload so the send button stays enabled")
    }

    @Test
    fun sendFailedSessionStateAllowsRetryViaSendEnabledStates() {
        // Verify that ChatSessionReducer.canSend returns true in SendFailed
        // state so the user can retry after a transient network failure without
        // re-picking attachments.
        val state = ChatSessionState(
            selectedConversationId = "conv-x",
            isRemoteBacked = true,
            isSending = false,
            isLoading = false,
            connectionState = ChatConnectionState.SendFailed,
            composer = ChatComposerState(
                text = "retry",
                pendingImageAttachments = listOf(image("img")),
            ),
        )
        assertTrue(ChatSessionReducer.canSend(state), "canSend must be true in SendFailed to allow retry")
        assertTrue(state.composer.hasPayload, "Composer must report payload so send button renders enabled")
    }

    private fun image(base64: String): MessageContentPart.Image =
        MessageContentPart.Image(base64 = base64, mediaType = "image/png")
}
