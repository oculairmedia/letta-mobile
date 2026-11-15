package com.letta.mobile.data.chat.routing

import com.letta.mobile.data.commands.api.SlashCommandQuery
import com.letta.mobile.data.chat.branch.BranchSlashCommand
import com.letta.mobile.data.commands.ClientSlashCommand
import com.letta.mobile.data.runtime.CommandActivity
import com.letta.mobile.data.runtime.CommandState
import com.letta.mobile.data.runtime.RuntimeLiveStatus
import com.letta.mobile.data.transport.appserver.AppServerSlashCommandApi
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatSessionPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Runs advertised host commands before composer text can become an agent prompt.
 * Captured graph and runtime identities guard asynchronous work against backend or target switches.
 * Confirmed output uses the existing command-status view; failures preserve the draft for retry.
 */
class SlashCommandSessionPort(
    private val delegate: ChatSessionPort,
    private val scope: CoroutineScope,
    private val binding: SlashCommandBinding,
) : ChatSessionPort by delegate {
    private var submission: Job? = null
    private var activityTarget: Pair<AppServerSlashCommandApi, AppServerRuntimeScope>? = null
    private val activity = MutableStateFlow<CommandActivity?>(null)
    private val clientRoutedCommands = setOf(BranchSlashCommand.FORK, BranchSlashCommand.BTW, "bug", "goal")

    override val liveStatus: StateFlow<RuntimeLiveStatus> = combine(
        delegate.liveStatus, activity, delegate.uiState,
    ) { live, command, _ ->
        if (command == null || activityTarget != binding.capture()) live else {
            live.copy(commands = live.commands.filterNot { it.commandId == command.commandId } + command)
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(), delegate.liveStatus.value)

    override val actions: ChatActions = object : ChatActions by delegate.actions {
        override fun send() = submit(delegate.composer.value.text, clearDraft = true)
        override fun sendText(text: String) = submit(text, clearDraft = false)
    }

    private fun submit(text: String, clearDraft: Boolean) {
        val id = text.trimStart().takeIf { it.startsWith("/") }?.drop(1)?.takeWhile { !it.isWhitespace() }
        if (id.isNullOrBlank() || isLocal(id)) {
            forward(text, clearDraft)
            return
        }
        val captured = binding.capture()
        if (captured == null) {
            delegate.actions.reportComposerError("Open a connected conversation to run a host command")
            return
        }
        if (submission?.isActive == true && activityTarget == captured) return
        submission?.cancel()
        activityTarget = captured
        val request = HostCommandSubmission(captured, id, text, clearDraft, delegate.composer.value.attachments.isNotEmpty())
        submission = scope.launch { runCommand(request) }
    }

    private fun isLocal(id: String): Boolean =
        ClientSlashCommand.fromId(id) != null || id in clientRoutedCommands ||
            delegate.composer.value.commands.any { !it.fillsComposer && it.id.removePrefix("/") == id }

    // This UI boundary reports transport failures; cancellation still escapes to its owner.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun runCommand(request: HostCommandSubmission) {
        try {
            dispatchCommand(request)
        } catch (cancelled: CancellationException) {
            clearActivity(request)
            throw cancelled
        } catch (failure: Exception) {
            reportFailure(request, failure)
        }
    }

    private suspend fun dispatchCommand(request: HostCommandSubmission) {
        val advertised = request.binding.first.listAgentSlashCommands(SlashCommandQuery(request.binding.second.agentId))
        if (!isCurrent(request)) {
            clearActivity(request)
            return
        }
        if (advertised.none { it.command == request.id }) {
            forwardUneditedDraft(request)
            return
        }
        require(!request.clearDraft || !request.hasAttachments) { "Host commands do not accept image attachments" }
        execute(request)
    }

    private fun forwardUneditedDraft(request: HostCommandSubmission) {
        if (!request.clearDraft || delegate.composer.value.text == request.text) forward(request.text, request.clearDraft)
    }

    private fun reportFailure(request: HostCommandSubmission, failure: Exception) {
        if (!isCurrent(request)) {
            clearActivity(request)
            return
        }
        val message = failure.message ?: "Host command failed"
        publish(request, CommandState.Failed, message)
        delegate.actions.reportComposerError(message)
    }

    private suspend fun execute(request: HostCommandSubmission) {
        publish(request, CommandState.Running)
        val args = request.text.trimStart().drop(request.id.length + 1).trimStart().ifEmpty { null }
        val response = request.binding.first.executeCommand(request.id, args, request.binding.second)
        if (!isCurrent(request)) {
            clearActivity(request)
            return
        }
        check(response.success) { response.error ?: "Host command failed" }
        publish(request, CommandState.Succeeded, response.output ?: response.result?.toString())
        delegate.actions.clearComposerError()
        clearSubmittedDraft(request)
    }

    private fun clearSubmittedDraft(request: HostCommandSubmission) {
        if (!request.clearDraft) return
        val current = delegate.composer.value
        if (current.text == request.text && current.attachments.isEmpty()) delegate.actions.updateComposerText("")
    }

    private fun publish(request: HostCommandSubmission, state: CommandState, output: String? = null) {
        activityTarget = request.binding
        activity.value = CommandActivity(request.activityId, request.text, slash = true, state = state, output = output)
    }

    private fun clearActivity(request: HostCommandSubmission) {
        if (activityTarget == request.binding) activity.value = null
    }

    private fun isCurrent(request: HostCommandSubmission): Boolean = binding.capture() == request.binding

    private fun forward(text: String, clearDraft: Boolean) {
        if (clearDraft) delegate.actions.send() else delegate.actions.sendText(text)
    }
}

/** Captures the graph adapter and runtime currently displayed by a platform host. */
fun interface SlashCommandBinding {
    fun capture(): Pair<AppServerSlashCommandApi, AppServerRuntimeScope>?
}

@OptIn(ExperimentalUuidApi::class)
private data class HostCommandSubmission(
    val binding: Pair<AppServerSlashCommandApi, AppServerRuntimeScope>,
    val id: String,
    val text: String,
    val clearDraft: Boolean,
    val hasAttachments: Boolean,
    val activityId: String = "slash-${Uuid.random()}",
)
