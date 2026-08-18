package com.letta.mobile.desktop.chat

import com.letta.mobile.data.model.MessageContentPart
import kotlinx.coroutines.CancellationException

internal data class RemoteSendAttempt(
    val loop: DesktopTimelineLoop,
    val text: String,
    val attachments: List<MessageContentPart.Image>,
    val conversationId: String?,
    val titleToPersist: String?,
    val streamGen: Int,
)

/**
 * Handles executing a remote send attempt over the active [DesktopTimelineLoop],
 * persisting conversation titles, and invoking completion/error callbacks.
 */
internal class DesktopChatRemoteSender(
    private val onSendSuccess: (attempt: RemoteSendAttempt) -> Unit,
    private val onSendFailed: (attempt: RemoteSendAttempt, errorMessage: String) -> Unit,
    private val persistConversationTitle: (conversationId: String, title: String) -> Unit,
    private val onAttemptCompleted: (attempt: RemoteSendAttempt) -> Unit,
) {
    suspend fun runRemoteSendAttempt(attempt: RemoteSendAttempt) {
        try {
            attempt.loop.send(
                DesktopTimelineSendRequest(
                    content = MessageBody(attempt.text),
                    attachments = attempt.attachments,
                ),
            )
            val conversationId = attempt.conversationId
            val title = attempt.titleToPersist
            if (conversationId != null && title != null) {
                persistConversationTitle(conversationId, title)
            }
            onSendSuccess(attempt)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            onSendFailed(
                attempt,
                t.message ?: t::class.simpleName ?: "Send failed",
            )
        } finally {
            onAttemptCompleted(attempt)
        }
    }
}
