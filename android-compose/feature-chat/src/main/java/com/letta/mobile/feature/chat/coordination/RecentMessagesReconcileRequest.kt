package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal enum class ConversationAccessMode { Client, Timeline }

internal enum class ResolutionAttempt { Initial, Subsequent }

internal enum class DuplicateInitialMessagePolicy { StageFreshClientDuplicate, SuppressDuplicate }

internal enum class HydrationAvailability { Hydrated, NeedsLoading }

internal data class RecentMessagesReconcileRequest(
    val conversationId: String,
    val reason: String,
    val connectionGeneration: Long,
)

internal data class ConversationOpenReconcileRequest(
    val conversationId: String,
)

internal class RecentMessagesReconcileLauncher(
    private val scope: CoroutineScope,
    private val reconcile: suspend (RecentMessagesReconcileRequest) -> Unit,
) {
    fun launch(openRequest: ConversationOpenReconcileRequest) {
        scope.launch { execute(openRequest) }
    }

    private suspend fun execute(openRequest: ConversationOpenReconcileRequest) {
        val generation = ChatHydrationTrace.current(openRequest.conversationId)
        traceStarted(generation)
        val result = runCatching {
            reconcile(reconcileRequest(openRequest, generation))
        }
        val error = result.exceptionOrNull()
        when {
            error == null -> traceCompleted(generation)
            error is CancellationException -> throw error
            else -> reportFailure(openRequest, error)
        }
    }

    private fun reconcileRequest(
        openRequest: ConversationOpenReconcileRequest,
        generation: ChatHydrationTrace.Generation?,
    ) = RecentMessagesReconcileRequest(
        conversationId = openRequest.conversationId,
        reason = "open",
        connectionGeneration = generation?.id ?: 0L,
    )

    private fun traceStarted(generation: ChatHydrationTrace.Generation?) {
        generation?.let { ChatHydrationTrace.reconcileStarted(it, reason = "open") }
    }

    private fun traceCompleted(generation: ChatHydrationTrace.Generation?) {
        generation?.let { ChatHydrationTrace.reconcileCompleted(it, reason = "open") }
    }

    private fun reportFailure(openRequest: ConversationOpenReconcileRequest, error: Throwable) {
        Telemetry.error(
            "AdminChatVM", "loadMessages.reconcileOnOpenFailed", error,
            "conversationId" to openRequest.conversationId,
        )
    }
}
