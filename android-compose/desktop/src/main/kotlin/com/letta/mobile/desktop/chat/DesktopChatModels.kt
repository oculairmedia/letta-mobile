package com.letta.mobile.desktop.chat

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.chat.projection.ChatDisplayMode
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.chat.projection.buildChatRenderModel
import com.letta.mobile.data.chat.runtime.ChatComposerPolicy
import com.letta.mobile.data.chat.runtime.ChatComposerState
import com.letta.mobile.data.chat.runtime.ChatConnectionState
import com.letta.mobile.data.chat.runtime.ChatConversationSummary
import com.letta.mobile.data.chat.runtime.ChatScreenStatus
import com.letta.mobile.data.chat.runtime.ChatSessionReducer
import com.letta.mobile.data.chat.runtime.ChatSessionState
import com.letta.mobile.data.chat.runtime.chatScreenStatusOf
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiApprovalToolCall
import com.letta.mobile.data.model.UiGeneratedComponent
import com.letta.mobile.data.model.UiImageAttachment
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.desktop.DesktopBootstrapState

typealias DesktopConversationSummary = ChatConversationSummary
typealias DesktopChatConnectionState = ChatConnectionState

@Immutable
data class DesktopChatSurfaceState(
    val conversations: List<DesktopConversationSummary>,
    val selectedConversationId: String?,
    val messagesByConversationId: Map<String, List<UiMessage>>,
    val composerText: String,
    val pendingImageAttachments: List<MessageContentPart.Image> = emptyList(),
    val isSending: Boolean,
    val isLoading: Boolean = false,
    val isRemoteBacked: Boolean = false,
    val connectionState: DesktopChatConnectionState = DesktopChatConnectionState.Demo,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val backendLabel: String,
    val sessionGraphId: Long,
    val selectionGeneration: Long = 0L,
) {
    val composer: ChatComposerState
        get() = ChatComposerState(
            text = composerText,
            pendingImageAttachments = pendingImageAttachments,
        )

    val runtimeState: ChatSessionState
        get() = ChatSessionState(
            conversations = conversations,
            selectedConversationId = selectedConversationId,
            messagesByConversationId = messagesByConversationId,
            composer = composer,
            connectionState = connectionState,
            isRemoteBacked = isRemoteBacked,
            isLoading = isLoading,
            isSending = isSending,
            statusMessage = statusMessage,
            errorMessage = errorMessage,
            selectionGeneration = selectionGeneration,
        )

    val selectedConversation: DesktopConversationSummary?
        get() = conversations.firstOrNull { it.id == selectedConversationId }

    val selectedMessages: List<UiMessage>
        get() = selectedConversationId?.let { messagesByConversationId[it] }.orEmpty()

    val renderItems: List<ChatRenderItem>
        get() = buildDesktopChatRenderItems(selectedMessages)

    val canSend: Boolean
        get() = ChatSessionReducer.canSend(runtimeState)

    val hasComposerPayload: Boolean
        get() = composer.hasPayload

    val shouldShowStatePanel: Boolean
        get() = ChatSessionReducer.shouldShowStatePanel(runtimeState)

    /**
     * Shared platform-neutral descriptor for the current chat screen condition.
     * Desktop UI composables switch on this to decide which panel/icon/copy to
     * show, instead of re-deriving the meaning from [connectionState] directly.
     */
    val chatScreenStatus: ChatScreenStatus
        get() = chatScreenStatusOf(runtimeState)
}

fun defaultDesktopChatSurfaceState(
    bootstrapState: DesktopBootstrapState,
): DesktopChatSurfaceState {
    val conversations = listOf(
        DesktopConversationSummary(
            id = "desktop-readiness",
            title = "Windows readiness",
            agentName = "Desktop architect",
            updatedAtLabel = "Just now",
            lastMessagePreview = "The desktop surface can render shared chat projection items.",
            unreadCount = 1,
        ),
        DesktopConversationSummary(
            id = "research-notes",
            title = "Research notes",
            agentName = "Research agent",
            updatedAtLabel = "18 min",
            lastMessagePreview = "I staged the findings as an A2UI summary card.",
        ),
        DesktopConversationSummary(
            id = "tool-approval",
            title = "Tool approval",
            agentName = "Ops agent",
            updatedAtLabel = "Yesterday",
            lastMessagePreview = "Approve the local filesystem read before continuing.",
        ),
    )

    return DesktopChatSurfaceState(
        conversations = conversations,
        selectedConversationId = conversations.first().id,
        messagesByConversationId = sampleDesktopMessagesByConversation(),
        composerText = "",
        pendingImageAttachments = emptyList(),
        isSending = false,
        isLoading = false,
        isRemoteBacked = false,
        connectionState = DesktopChatConnectionState.Demo,
        statusMessage = "Demo preview",
        errorMessage = null,
        backendLabel = "${bootstrapState.config.serverUrl} - graph ${bootstrapState.sessionGraphId}",
        sessionGraphId = bootstrapState.sessionGraphId,
    )
}

fun initialLiveDesktopChatSurfaceState(
    bootstrapState: DesktopBootstrapState,
): DesktopChatSurfaceState =
    DesktopChatSurfaceState(
        conversations = emptyList(),
        selectedConversationId = null,
        messagesByConversationId = emptyMap(),
        composerText = "",
        pendingImageAttachments = emptyList(),
        isSending = false,
        isLoading = true,
        isRemoteBacked = true,
        connectionState = DesktopChatConnectionState.Loading,
        statusMessage = "Connecting to ${bootstrapState.config.serverUrl}",
        errorMessage = null,
        backendLabel = "${bootstrapState.config.serverUrl} - graph ${bootstrapState.sessionGraphId}",
        sessionGraphId = bootstrapState.sessionGraphId,
    )

fun DesktopChatSurfaceState.selectConversation(
    conversationId: String,
): DesktopChatSurfaceState {
    val next = ChatSessionReducer.selectConversation(
        state = runtimeState,
        conversationId = conversationId,
        remoteBacked = isRemoteBacked,
    )
    return withRuntimeState(next)
}

fun DesktopChatSurfaceState.withComposerText(text: String): DesktopChatSurfaceState =
    withRuntimeState(ChatSessionReducer.updateComposerText(runtimeState, text))

fun DesktopChatSurfaceState.withImageAttachment(image: MessageContentPart.Image): DesktopChatSurfaceState =
    withRuntimeState(ChatSessionReducer.attachImage(runtimeState, image))

fun DesktopChatSurfaceState.withoutImageAttachment(index: Int): DesktopChatSurfaceState =
    withRuntimeState(ChatSessionReducer.removeImageAttachment(runtimeState, index))

fun DesktopChatSurfaceState.withComposer(composer: ChatComposerState): DesktopChatSurfaceState =
    copy(
        composerText = composer.text,
        pendingImageAttachments = composer.pendingImageAttachments,
    )

fun DesktopChatSurfaceState.withRuntimeState(runtimeState: ChatSessionState): DesktopChatSurfaceState =
    copy(
        conversations = runtimeState.conversations,
        selectedConversationId = runtimeState.selectedConversationId,
        messagesByConversationId = runtimeState.messagesByConversationId,
        composerText = runtimeState.composer.text,
        pendingImageAttachments = runtimeState.composer.pendingImageAttachments,
        isSending = runtimeState.isSending,
        isLoading = runtimeState.isLoading,
        isRemoteBacked = runtimeState.isRemoteBacked,
        connectionState = runtimeState.connectionState,
        statusMessage = runtimeState.statusMessage,
        errorMessage = runtimeState.errorMessage,
        selectionGeneration = runtimeState.selectionGeneration,
    )

fun DesktopChatSurfaceState.sendLocalMessage(): DesktopChatSurfaceState {
    val conversationId = selectedConversationId ?: return this
    val draft = ChatComposerPolicy.beginSend(composer) ?: return this
    val text = draft.text
    val attachments = draft.attachments

    val currentMessages = messagesByConversationId[conversationId].orEmpty()
    val localMessage = UiMessage(
        id = "desktop-local-${conversationId}-${currentMessages.size + 1}",
        role = "user",
        content = text,
        timestamp = "2026-06-07T16:${(currentMessages.size + 10).toString().padStart(2, '0')}:00Z",
        isPending = true,
        attachments = attachments.map { UiImageAttachment(base64 = it.base64, mediaType = it.mediaType) },
    )

    return withRuntimeState(
        ChatSessionReducer.queueLocalMessage(
            state = runtimeState,
            draft = draft,
            message = localMessage,
            preview = text,
        ),
    )
}

private fun buildDesktopChatRenderItems(
    messages: List<UiMessage>,
): List<ChatRenderItem> =
    buildChatRenderModel(
        messages = messages,
        mode = ChatDisplayMode.Interactive,
    ).renderItems.asReversed()

private fun sampleDesktopMessagesByConversation(): Map<String, List<UiMessage>> = mapOf(
    "desktop-readiness" to listOf(
        UiMessage(
            id = "desktop-readiness-user-1",
            role = "user",
            content = "Where does the Windows desktop port stand after the KMP extraction work?",
            timestamp = "2026-06-07T15:22:00Z",
        ),
        UiMessage(
            id = "desktop-readiness-reasoning-1",
            role = "assistant",
            content = "Checking the desktop shell, shared timeline projection, and repository-session contracts.",
            timestamp = "2026-06-07T15:22:07Z",
            runId = "run-desktop-readiness",
            stepId = "step-reasoning",
            isReasoning = true,
        ),
        UiMessage(
            id = "desktop-readiness-tool-1",
            role = "assistant",
            content = "",
            timestamp = "2026-06-07T15:22:11Z",
            runId = "run-desktop-readiness",
            stepId = "step-tool",
            toolCalls = listOf(
                UiToolCall(
                    name = "desktop_bootstrap_status",
                    arguments = """{"target":"windows","module":"desktop"}""",
                    result = "Compose Desktop boots with a shared session graph, no-op transport, and JVM settings adapters.",
                    status = "completed",
                    executionTimeMs = 47L,
                ),
            ),
        ),
        UiMessage(
            id = "desktop-readiness-a2ui-1",
            role = "assistant",
            content = "Desktop can preserve generated UI payloads without depending on the Android renderer.",
            timestamp = "2026-06-07T15:22:14Z",
            runId = "run-desktop-readiness",
            stepId = "step-a2ui",
            generatedUi = UiGeneratedComponent(
                name = "DesktopReadinessCard",
                propsJson = """{"catalog":"basic","status":"preview","items":["shared render model","tool call contracts","A2UI payload surface"]}""",
                fallbackText = "Shared render model, tool call contracts, and A2UI payload surface are available.",
            ),
        ),
        UiMessage(
            id = "desktop-readiness-assistant-1",
            role = "assistant",
            content = "The desktop chat shell now has a persistent conversation list, chronological detail pane, shared run-block rendering, tool cards, A2UI fallback cards, and a local composer queue. Live remote send/receive remains behind the desktop transport/repository follow-up.",
            timestamp = "2026-06-07T15:22:19Z",
            runId = "run-desktop-readiness",
            stepId = "step-final",
        ),
    ),
    "research-notes" to listOf(
        UiMessage(
            id = "research-user-1",
            role = "user",
            content = "Summarize the extraction beads that are still relevant for Windows.",
            timestamp = "2026-06-07T14:41:00Z",
        ),
        UiMessage(
            id = "research-assistant-1",
            role = "assistant",
            content = "Transport/runtime interfaces and session graph contracts are now enough for a desktop shell to boot. The next shared layer is projection parity fixtures and real repository implementations.",
            timestamp = "2026-06-07T14:41:12Z",
            latencyMs = 12_000L,
        ),
        UiMessage(
            id = "research-assistant-2",
            role = "assistant",
            content = "I also kept the A2UI payload as a contract-level card so desktop can display the generated surface even before the Android renderer is extracted.",
            timestamp = "2026-06-07T14:41:16Z",
            generatedUi = UiGeneratedComponent(
                name = "ExtractionSummary",
                propsJson = """{"remaining":["repository implementations","projection fixtures","desktop renderer"]}""",
                fallbackText = "Remaining: repository implementations, projection fixtures, desktop renderer.",
            ),
        ),
    ),
    "tool-approval" to listOf(
        UiMessage(
            id = "approval-user-1",
            role = "user",
            content = "Before reading local runtime logs, ask for approval.",
            timestamp = "2026-06-06T21:03:00Z",
        ),
        UiMessage(
            id = "approval-assistant-1",
            role = "assistant",
            content = "The agent requested approval for a filesystem read.",
            timestamp = "2026-06-06T21:03:09Z",
            runId = "run-tool-approval",
            stepId = "step-approval",
            approvalRequest = UiApprovalRequest(
                requestId = "approval-local-logs",
                toolCalls = listOf(
                    UiApprovalToolCall(
                        toolCallId = "tool-read-logs",
                        name = "read_file",
                        arguments = """{"path":"%LOCALAPPDATA%\\Letta\\logs\\desktop.log"}""",
                    ),
                ),
            ),
        ),
    ),
)
