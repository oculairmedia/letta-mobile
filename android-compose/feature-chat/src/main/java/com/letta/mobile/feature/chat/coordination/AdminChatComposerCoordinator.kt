package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.data.chat.runtime.ChatRunInterruptTelemetry
import com.letta.mobile.data.chat.runtime.ChatRunInterrupter
import com.letta.mobile.data.chat.runtime.ChatStopRequest
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AskUserQuestion
import com.letta.mobile.data.model.BackendKind
import com.letta.mobile.data.model.GoalStatus
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.repository.api.IMessageRepository
import com.letta.mobile.data.repository.api.ISlashCommandRepository
import com.letta.mobile.data.session.SessionManager
import com.letta.mobile.feature.chat.send.ChatSendContext
import com.letta.mobile.feature.chat.send.ChatSendStrategySelector
import com.letta.mobile.feature.chat.send.LocalRuntimeRouting
import com.letta.mobile.feature.chat.state.ChatBannerController
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.GoalStatusUi
import com.letta.mobile.ui.chat.render.ConversationState


internal class AdminChatComposerCoordinator(
    private val scope: CoroutineScope,
    private val composerController: ChatComposerController,
    private val chatSendStrategySelector: ChatSendStrategySelector,
    private val chatBannerController: ChatBannerController,
    private val uiState: MutableStateFlow<ChatUiState>,
    private val agentId: AgentId,
    private val explicitConversationId: String?,
    private val backendKind: () -> BackendKind,
    private val sessionManager: SessionManager,
    private val messageRepository: IMessageRepository,
    private val slashCommandRepository: ISlashCommandRepository,
    private val isStreaming: () -> Boolean,
    private val pendingUserInput: () -> PendingUserInput? = { null },
    private val submitUserInput: (PendingUserInput, String) -> Unit = { _, _ -> },
    private val projectContextAvailable: Boolean,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    /**
     * letta-mobile-lgns8.19, by4vm.14: Stop's lifecycle (cancelling, abort, terminal watch, local
     * escape hatch) lives in the shared [ChatRunInterrupter]; this screen only binds it to its
     * banner state and its send strategies.
     */
    private val interrupter = ChatRunInterrupter(
        scope = scope,
        host = AdminChatInterruptHost(chatBannerController, ::guardAgainstLateFrames),
        telemetry = ChatRunInterruptTelemetry(TELEMETRY_TAG, listOf("agentId" to agentId.value)),
        nowMs = nowMs,
    )
    private var lateFrameJob: Job? = null

    val state: StateFlow<ChatComposerState> = composerController.state

    fun addAttachment(image: MessageContentPart.Image): Boolean =
        composerController.addAttachment(image)

    fun removeAttachment(index: Int) {
        composerController.removeAttachment(index)
    }

    fun updateInputText(text: String) {
        composerController.updateText(text)
    }

    fun setSlashCommands(commands: List<com.letta.mobile.data.model.SlashCommand>) {
        composerController.setSlashCommands(commands)
    }

    fun insertSlashCommand(command: com.letta.mobile.data.model.SlashCommand) {
        composerController.insertSlashCommand(command)
    }

    fun reportComposerError(message: String) {
        chatBannerController.showComposerError(message)
    }

    fun clearComposerError() {
        chatBannerController.clearComposerError()
    }

    fun handleComposerTextChanged(newText: String): ChatComposerEffect? {
        val composer = state.value
        return if (newText.endsWith("\n") && composer.hasSendableContent) {
            submitComposer(composer.inputText)
        } else {
            updateInputText(newText)
            null
        }
    }

    fun submitComposer(text: String = state.value.inputText): ChatComposerEffect? {
        val trimmed = text.trim()
        val firstToken = trimmed.substringBefore(' ').substringBefore('\n')
        if (firstToken == "/goal" && backendKind().usesChannelTransport) {
            composerController.clearText()
            scope.launch {
                slashCommandRepository.executeGoalCommand(agentId.value, trimmed)
                    .onSuccess { message ->
                        chatBannerController.showComposerError("Goal: $message")
                        slashCommandRepository.getGoalStatus(agentId.value).onSuccess { status ->
                            uiState.update { it.copy(goalStatus = status.goal?.toUi(), isGoalStatusLoading = false) }
                        }.onFailure {
                            uiState.update { it.copy(isGoalStatusLoading = false) }
                        }
                    }
                    .onFailure { err -> chatBannerController.showComposerError(err.message ?: "Goal command failed") }
            }
            return null
        }
        return when (ChatSlashCommandParser.parse(text, projectContextAvailable = projectContextAvailable)) {
            ChatSlashCommand.Bug -> {
                composerController.clearText()
                ChatComposerEffect.OpenBugReport
            }
            null -> {
                val pending = pendingUserInput()
                if (pending != null && trimmed.isNotBlank()) {
                    submitUserInput(pending, pending.encodeAnswer(trimmed))
                    composerController.clearText()
                } else if (uiState.value.isCancellingRun) {
                    // letta-mobile-lgns8.19: sends are REJECTED (not queued)
                    // while a stop is in flight — matching the existing
                    // "no free-form steering during an active run" convention.
                    composerController.setError(STOPPING_SEND_BLOCKED_MESSAGE)
                } else if (isStreaming() && !canQueueWhileStreaming()) {
                    composerController.setError(
                        "Letta does not support free-form steering during an active run yet. Stop the run before sending another message."
                    )
                } else {
                    sendMessage(text)
                }
                null
            }
        }
    }

    internal data class PendingUserInput(
        val requestId: String,
        val toolCallId: String,
        val arguments: String?,
        val question: String,
    ) {
        fun encodeAnswer(answer: String): String = AskUserQuestion.encodeAnswerReason(
            AskUserQuestion.buildUpdatedInput(arguments, mapOf(question to listOf(answer))),
        )
    }

    fun sendMessage(text: String) {
        // letta-mobile-lgns8.19: the stop is not confirmed yet — the server turn
        // is still live, so a send now is exactly the interleaving the bead
        // reports. Reject with a specific message rather than queueing.
        if (uiState.value.isCancellingRun) {
            chatBannerController.showComposerError(STOPPING_SEND_BLOCKED_MESSAGE)
            return
        }
        when (uiState.value.conversationState) {
            ConversationState.Loading -> {
                chatBannerController.showConversationStillLoading()
                return
            }
            is ConversationState.Error -> {
                chatBannerController.showRetryConversationLoadBeforeSend()
                return
            }
            ConversationState.NoConversation,
            is ConversationState.Ready,
            -> Unit
        }

        val payload = composerController.payloadForSend(text) ?: return
        if (isStreaming() && canQueueWhileStreaming()) {
            queueMessagePayload(payload)
        } else {
            sendMessagePayload(payload.text, payload.attachments)
        }
    }

    /**
     * letta-mobile-1n5py: the Iroh route queues a message sent during a turn instead of refusing
     * it. The other routes still have no queue, so they keep the "stop first" rule.
     */
    fun canQueueWhileStreaming(): Boolean {
        val context = chatSendContext()
        return !context.isLocalRuntime && context.backendKind == BackendKind.IROH
    }

    /**
     * The turn ahead keeps running, so none of the new-turn bookkeeping below applies: its cancel
     * watcher, banners and send epoch belong to it until its terminal.
     */
    private fun queueMessagePayload(payload: ComposerSendPayload) {
        chatSendStrategySelector.send(payload.text, payload.attachments, chatSendContext())
    }

    /**
     * letta-mobile-1n5py: a queued message is about to become a new turn outside [sendMessage]
     * (Send now, Resume). Retire the previous turn's cancel watcher first, or its ghost-resume
     * guard would read the new turn's first frames as the stopped turn's tail and clear them.
     */
    fun beginQueuedTurn() = retireCancel()

    /** A new turn starts: the previous one's stop bookkeeping must never touch it. */
    private fun retireCancel() {
        interrupter.retire()
        lateFrameJob?.cancel()
        lateFrameJob = null
        chatBannerController.clearCancelling()
    }

    fun rerunMessage(message: UiMessage) {
        val text = message.content.trim()
        if (message.role != "user" || text.isBlank()) return
        sendMessagePayload(text, emptyList())
    }

    private fun sendMessagePayload(
        text: String,
        attachments: List<MessageContentPart.Image>,
    ) {
        // letta-mobile-lgns8.19: a new turn starts here — retire any cancel
        // bookkeeping from the previous one so "stopping…" can never leak.
        retireCancel()
        // letta-mobile-aujte: a failed-turn banner from a PRIOR turn must not
        // survive into a new one — without this, ChatUiState.error is sticky
        // forever (TurnFailureNotices only ever SETS it; nothing else in the
        // send path cleared it), so a successful turn still rendered under a
        // stale red error banner from turns ago.
        chatBannerController.clearError()
        val context = chatSendContext()
        chatSendStrategySelector.send(text, attachments, context)
    }

    fun chatSendContext() = ChatSendContext(
        explicitConversationId = explicitConversationId,
        backendKind = backendKind(),
        isLocalRuntime = LocalRuntimeRouting.shouldUseLocalRuntime(
            sessionHasLocalRuntimeBackend = sessionManager.current.localRuntimeBackend != null,
            agentId = agentId.value,
            conversationId = explicitConversationId,
        ),
    )

    private fun GoalStatus.toUi() = GoalStatusUi(
        objective = objective,
        status = status.wireValue,
        activeTimeSeconds = activeTimeSeconds,
        tokensUsed = tokensUsed,
        tokenBudget = tokenBudget,
    )

    /**
     * letta-mobile-lgns8.19: Stop.
     *
     * The UI no longer goes idle when the cancel is REQUESTED. It enters an
     * explicit CANCELLING state ([ChatUiState.isCancellingRun]) that holds the
     * composer blocked and shows "stopping…", and resolves to idle only when the
     * authoritative terminal frame lands (or the transport's bounded
     * synthetic-terminal fallback fires). A SECOND Stop press while cancelling is
     * the escape hatch: it force-clears locally and is telemetered, because the
     * server turn may still be running at that point.
     */
    fun interruptRun() {
        if (!uiState.value.isStreaming) return
        val key = explicitConversationId.orEmpty()
        val context = chatSendContext()
        interrupter.request(
            ChatStopRequest(
                key = key,
                transport = cancelTransportLabel(context),
                abort = { dispatchCancel(context) },
                // isStreaming goes false only on a terminal lifecycle frame (or the transport's
                // synthetic fallback), so that is the run's terminal.
                awaitTerminal = { uiState.first { !it.isStreaming } },
            ),
        )
    }

    /** Asks the transport to stop the turn: true once the request went out, throws when it could not. */
    private suspend fun dispatchCancel(context: ChatSendContext): Boolean {
        if (context.usesChannelTransport || context.isLocalRuntime) {
            chatSendStrategySelector.cancel(context)
        } else {
            messageRepository.cancelMessage(agentId = agentId, runIds = activeRunIds().takeIf { it.isNotEmpty() })
        }
        return true
    }

    /**
     * Ghost-resume guard: for a short window after a CANCELLED turn's terminal,
     * any frame that tries to re-open the streaming UI without a new user send
     * belongs to the turn that was just killed. Drop it back to idle and
     * telemeter instead of letting the dead turn's tail render as a live reply.
     * A real new turn ([retireCancel]) stands this watcher down.
     */
    private fun guardAgainstLateFrames() {
        lateFrameJob?.cancel()
        lateFrameJob = scope.launch {
            withTimeoutOrNull(LATE_FRAME_SUPPRESSION_WINDOW_MS.milliseconds) {
                uiState.collect { state ->
                    if (!state.isStreaming) return@collect
                    Telemetry.event(
                        TELEMETRY_TAG,
                        "interrupt.lateFrameSuppressed",
                        "agentId" to agentId.value,
                        "conversationId" to explicitConversationId,
                        level = Telemetry.Level.WARN,
                    )
                    chatBannerController.forceClearStreamingAfterInterrupt()
                }
            }
        }
    }

    private fun cancelTransportLabel(context: ChatSendContext): String = when {
        context.isLocalRuntime -> "localRuntime"
        context.backendKind == BackendKind.IROH -> "iroh"
        else -> "appServer"
    }

    private fun activeRunIds(): List<String> = uiState.value.messages
        .asReversed()
        .mapNotNull { it.runId }
        .distinct()
        .take(1)

    internal companion object {
        const val STOPPING_SEND_BLOCKED_MESSAGE =
            "Stopping the current run — wait for it to finish before sending another message."

        /** Ghost-resume guard window after a cancelled turn's terminal frame. */
        const val LATE_FRAME_SUPPRESSION_WINDOW_MS = 5_000L

        private const val TELEMETRY_TAG = "AdminChatVM"
    }
}
