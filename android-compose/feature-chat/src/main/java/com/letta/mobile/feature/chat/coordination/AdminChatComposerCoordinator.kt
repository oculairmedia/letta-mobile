package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.data.model.AgentId
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
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    private val projectContextAvailable: Boolean,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    /**
     * letta-mobile-lgns8.19: bumped whenever a NEW turn is dispatched from this
     * composer. The cancel watcher captures the epoch at Stop time and stands
     * down the moment it changes, so a cancel from a previous turn can never
     * clear (or suppress frames of) the turn that replaced it.
     */
    private var sendEpoch = 0
    private var cancelWatchJob: Job? = null

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
                if (uiState.value.isCancellingRun) {
                    // letta-mobile-lgns8.19: sends are REJECTED (not queued)
                    // while a stop is in flight — matching the existing
                    // "no free-form steering during an active run" convention.
                    composerController.setError(STOPPING_SEND_BLOCKED_MESSAGE)
                } else if (isStreaming()) {
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
        sendMessagePayload(payload.text, payload.attachments)
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
        sendEpoch += 1
        cancelWatchJob?.cancel()
        cancelWatchJob = null
        chatBannerController.clearCancelling()
        val context = chatSendContext()
        chatSendStrategySelector.send(text, attachments, context)
    }

    fun chatSendContext() = ChatSendContext(
        isClientModeEnabled = false,
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
        status = status,
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
    fun interruptRun(clearA2uiThinkingOnResponse: () -> Unit) {
        val snapshot = uiState.value
        if (!snapshot.isStreaming) return
        if (snapshot.isCancelling) {
            forceLocalClear(reason = "secondStopPress")
            return
        }
        clearA2uiThinkingOnResponse()
        val context = chatSendContext()
        val requestedAtMs = nowMs()
        chatBannerController.beginCancelling()
        Telemetry.event(
            TELEMETRY_TAG,
            "interrupt.cancelRequested",
            "agentId" to agentId.value,
            "conversationId" to explicitConversationId,
            "transport" to cancelTransportLabel(context),
        )
        watchForCancelTerminal(requestedAtMs)
        scope.launch {
            if (context.usesChannelTransport || context.isLocalRuntime) {
                chatSendStrategySelector.cancel(context)
                return@launch
            }
            val runIds = activeRunIds().takeIf { it.isNotEmpty() }
            runCatching {
                messageRepository.cancelMessage(agentId = agentId, runIds = runIds)
            }.onFailure { e ->
                // The abort never reached the server: holding "stopping…" would
                // wedge the composer, so drop to the local escape hatch.
                chatBannerController.showMappedError(e.asException(), "Failed to stop run")
                forceLocalClear(reason = "cancelDispatchFailed")
            }
        }
    }

    /**
     * Waits for the turn's authoritative terminal — `isStreaming` going false is
     * emitted only by a terminal lifecycle frame (or the transport's synthetic
     * fallback) — then retires the cancel marker and guards the immediate
     * aftermath against ghost resume. Bounded so a terminal that never arrives
     * cannot wedge the composer forever.
     */
    private fun watchForCancelTerminal(requestedAtMs: Long) {
        val epoch = sendEpoch
        cancelWatchJob?.cancel()
        cancelWatchJob = scope.launch {
            val terminal = withTimeoutOrNull(CANCEL_TERMINAL_TIMEOUT_MS) {
                uiState.first { !it.isStreaming }
            }
            if (epoch != sendEpoch) return@launch
            if (terminal == null) {
                Telemetry.event(
                    TELEMETRY_TAG,
                    "interrupt.terminalTimeout",
                    "agentId" to agentId.value,
                    "conversationId" to explicitConversationId,
                    durationMs = CANCEL_TERMINAL_TIMEOUT_MS,
                    level = Telemetry.Level.WARN,
                )
                chatBannerController.forceClearStreamingAfterInterrupt()
                return@launch
            }
            Telemetry.event(
                TELEMETRY_TAG,
                "interrupt.terminalAfterCancel",
                "agentId" to agentId.value,
                "conversationId" to explicitConversationId,
                durationMs = nowMs() - requestedAtMs,
            )
            chatBannerController.clearCancelling()
            suppressLateFramesAfterTerminal(epoch)
        }
    }

    /**
     * Ghost-resume guard: for a short window after a CANCELLED turn's terminal,
     * any frame that tries to re-open the streaming UI without a new user send
     * belongs to the turn that was just killed. Drop it back to idle and
     * telemeter instead of letting the dead turn's tail render as a live reply.
     * A real new turn bumps [sendEpoch] and stands this watcher down.
     */
    private suspend fun suppressLateFramesAfterTerminal(epoch: Int) {
        withTimeoutOrNull(LATE_FRAME_SUPPRESSION_WINDOW_MS) {
            uiState.takeWhile { epoch == sendEpoch }.collect { state ->
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

    private fun forceLocalClear(reason: String) {
        cancelWatchJob?.cancel()
        cancelWatchJob = null
        chatBannerController.forceClearStreamingAfterInterrupt()
        Telemetry.event(
            TELEMETRY_TAG,
            "interrupt.forcedLocalClear",
            "agentId" to agentId.value,
            "conversationId" to explicitConversationId,
            "reason" to reason,
            level = Telemetry.Level.WARN,
        )
    }

    private fun cancelTransportLabel(context: ChatSendContext): String = when {
        context.isLocalRuntime -> "localRuntime"
        context.backendKind == BackendKind.IROH -> "iroh"
        context.backendKind == BackendKind.SHIM_WS -> "shim"
        else -> "appServer"
    }

    private fun Throwable.asException(): Exception = this as? Exception ?: Exception(this)

    private fun activeRunIds(): List<String> = uiState.value.messages
        .asReversed()
        .mapNotNull { it.runId }
        .distinct()
        .take(1)

    internal companion object {
        const val STOPPING_SEND_BLOCKED_MESSAGE =
            "Stopping the current run — wait for it to finish before sending another message."

        /** Upper bound on holding "stopping…" before falling back to a local clear. */
        const val CANCEL_TERMINAL_TIMEOUT_MS = 30_000L

        /** Ghost-resume guard window after a cancelled turn's terminal frame. */
        const val LATE_FRAME_SUPPRESSION_WINDOW_MS = 5_000L

        private const val TELEMETRY_TAG = "AdminChatVM"
    }
}
