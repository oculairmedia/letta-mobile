package com.letta.mobile.ui.chat.session

import com.letta.mobile.data.chat.recompile.RecompileStatus
import com.letta.mobile.data.runtime.RuntimeLiveStatus
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The composer chrome a host's extension points add on top of the owner's draft: the chips, the
 * queue, and the host's commands, mentionables, context usage and placeholder.
 */
data class ChatComposerChrome(
    val canQueueWhileStreaming: Boolean = false,
    val workingDirectory: ChatWorkingDirectoryUiState? = null,
    val model: ChatModelUiState? = null,
    val permissionMode: ChatPermissionModeUiState? = null,
    val host: ChatComposerHostInputs = ChatComposerHostInputs(),
)

/** [this] draft with the extension points' [chrome] laid over it. */
fun ChatComposerUiState.withChrome(chrome: ChatComposerChrome): ChatComposerUiState = copy(
    canQueueWhileStreaming = chrome.canQueueWhileStreaming,
    workingDirectory = chrome.workingDirectory,
    model = chrome.model,
    permissionMode = chrome.permissionMode,
    placeholder = chrome.host.placeholder,
    commands = chrome.host.commands.map(ChatHostCommand::command).toImmutableList(),
    mentionables = chrome.host.mentionables.toImmutableList(),
    contextUsage = chrome.host.contextUsage,
)

/** What a host's extension points turn on. */
data class ChatExtensionFacts(
    val workingDirectory: Boolean = false,
    val modelSwitch: Boolean = false,
    val branching: ChatBranchSupport = ChatBranchSupport(),
)

/** [this] owner's capabilities with what its extension points turn on. */
fun ChatSurfaceCapabilities.withExtensions(facts: ChatExtensionFacts): ChatSurfaceCapabilities = copy(
    workingDirectory = facts.workingDirectory,
    modelSwitch = facts.modelSwitch,
    fork = facts.branching.fork,
    editAndResend = facts.branching.fork,
    rerunInFork = facts.branching.fork,
    nameForks = facts.branching.fork && facts.branching.nameForks,
)

/**
 * letta-mobile-by4vm.98.3: a host's extension points ([TimelineChatSessionOptions]) read together,
 * so every session port composes the same composer chrome, capabilities and status lines from
 * them: [TimelineChatSessionPort] over its own draft, desktop's port over its controller's.
 */
class ChatSessionExtensions(
    private val options: TimelineChatSessionOptions,
    scope: CoroutineScope,
) {
    private val workingDirectory = options.workingDirectory?.current ?: MutableStateFlow(null)
    private val model = options.models?.model ?: MutableStateFlow(null)
    private val host = options.composerHost ?: MutableStateFlow(ChatComposerHostInputs())
    private val branching = options.branching?.support ?: MutableStateFlow(ChatBranchSupport())

    /** A send during a run goes to the host's queue rather than waiting for the run to end. */
    val canQueue: StateFlow<Boolean> = options.sendQueue?.canQueueWhileStreaming ?: MutableStateFlow(false)

    val chrome: StateFlow<ChatComposerChrome> = combine(
        canQueue,
        workingDirectory,
        model,
        options.permissionModes?.permissionMode ?: flowOf(null),
        host,
    ) { queues, directory, chip, permissionMode, inputs ->
        ChatComposerChrome(queues, directory, chip, permissionMode, inputs)
    }.stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), currentChrome())

    val facts: StateFlow<ChatExtensionFacts> = combine(
        workingDirectory.map { it != null }.distinctUntilChanged(),
        branching,
    ) { directory, branches -> ChatExtensionFacts(directory, options.models != null, branches) }
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), currentFacts())

    /** letta-mobile-bzvro.7: the conversation's live status; idle where the host observes none. */
    val liveStatus: StateFlow<RuntimeLiveStatus> = (options.liveStatus ?: flowOf(RuntimeLiveStatus.Idle))
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), RuntimeLiveStatus.Idle)

    /** letta-mobile-zxk2n.5: where the last `/recompile` stands; idle where the host has none. */
    val recompileStatus: StateFlow<RecompileStatus> =
        options.recompile?.status ?: MutableStateFlow(RecompileStatus.Idle)

    private fun currentChrome() = ChatComposerChrome(canQueue.value, workingDirectory.value, model.value, host = host.value)

    private fun currentFacts() = ChatExtensionFacts(workingDirectory.value != null, options.models != null, branching.value)

    private companion object {
        /** The page's flows run only while it collects them. */
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
