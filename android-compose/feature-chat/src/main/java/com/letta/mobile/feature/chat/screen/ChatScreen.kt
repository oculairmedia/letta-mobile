package com.letta.mobile.feature.chat.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.feature.chat.screen.shared.SharedChatPage
import com.letta.mobile.feature.chat.screen.shared.SharedChatPageParams
import com.letta.mobile.feature.chat.screen.shared.SharedChatSubagentInputs
import com.letta.mobile.feature.chat.subagent.ActiveSubagentSource
import com.letta.mobile.ui.ambient.VisibleAssistantStreamPulseState
import com.letta.mobile.ui.ambient.reduceVisibleAssistantStreamPulse
import com.letta.mobile.ui.components.AmbientShaderAgentBackground
import com.letta.mobile.ui.theme.ChatBackground
import com.letta.mobile.ui.theme.LettaChatTheme


@Composable
internal fun ChatScreen(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    chatBackground: ChatBackground = ChatBackground.Default,
    chatMode: String = "simple",
    onBugCommand: (() -> Unit)? = null,
    onViewSubagentConversation: ((String, String) -> Unit)? = null,
    onOpenAgentPane: (() -> Unit)? = null,
    onOpenCanvas: (() -> Unit)? = null,
    activeSubagentSource: ActiveSubagentSource? = null,
    selfTodoSource: com.letta.mobile.feature.chat.subagent.SelfTodoSource? = null,
    viewModel: AdminChatViewModel = hiltViewModel(),
) {
    val resolvedSubagentSource = activeSubagentSource ?: viewModel.activeSubagentSource
    val resolvedSelfTodoSource = selfTodoSource ?: viewModel.selfTodoSource
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val timelinePresentation by viewModel.timelinePresentation.collectAsStateWithLifecycle()
    val activeFontScale by viewModel.chatFontScale.collectAsStateWithLifecycle()
    val hapticsEnabled by viewModel.hapticsEnabled.collectAsStateWithLifecycle()
    val openChatsOnCanvas by viewModel.openChatsOnCanvas.collectAsStateWithLifecycle()

    val backgroundModifier = when (chatBackground) {
        is ChatBackground.Default -> Modifier
        is ChatBackground.SolidColor -> Modifier.background(chatBackground.color)
        is ChatBackground.Gradient -> Modifier.background(chatBackground.toBrush())
    }

    val navigation = remember(onBugCommand, onViewSubagentConversation, onOpenAgentPane, onOpenCanvas) {
        ChatScreenNavigationCallbacks(
            onBugCommand = onBugCommand,
            onViewSubagentConversation = onViewSubagentConversation,
            onOpenAgentPane = onOpenAgentPane,
            onOpenCanvas = onOpenCanvas,
        )
    }

    // The page waits for the persisted font scale so it never lays out at the wrong size.
    val committedFontScale = activeFontScale ?: return
    LettaChatTheme(fontScale = committedFontScale) {
        val subagentBarState = rememberChatScreenSubagentBarState(
            resolvedSubagentSource = resolvedSubagentSource,
            resolvedSelfTodoSource = resolvedSelfTodoSource,
            currentConversationId = viewModel.conversationId?.value,
        )
        val ambient = rememberChatScreenAmbientState()
        val streamActivityPulse = rememberVisibleAssistantStreamPulse(state)

        ChatScreenEffects(
            params = ChatScreenEffectsParams(
                state = state,
                hapticsEnabled = hapticsEnabled,
                ambient = ambient,
            ),
        )

        // letta-mobile-bglj6.1: the shared page draws the glow behind its own full-screen layer
        // (it is opaque over the docked canvas), and its composer handles the IME.
        SharedChatPage(
            params = SharedChatPageParams(
                viewModel = viewModel,
                navigation = navigation,
                chatMode = chatMode,
                fontScale = committedFontScale,
                hapticsEnabled = hapticsEnabled,
                timeline = timelinePresentation?.timeline,
                openOnCanvas = openChatsOnCanvas,
                subagents = SharedChatSubagentInputs(
                    source = resolvedSubagentSource,
                    selfTodoSource = resolvedSelfTodoSource,
                    barState = subagentBarState,
                ),
                pageBackground = { content ->
                    AmbientShaderAgentBackground(
                        agentStatus = ambient.status,
                        streamActivityPulse = streamActivityPulse,
                        modifier = Modifier.fillMaxSize().then(backgroundModifier),
                    ) { content() }
                },
            ),
            modifier = modifier.fillMaxSize().padding(contentPadding),
        )
    }
}

@Composable
private fun rememberVisibleAssistantStreamPulse(state: com.letta.mobile.ui.chat.render.ChatUiState): Long {
    var pulseState by remember { mutableStateOf(VisibleAssistantStreamPulseState()) }
    val tail = state.messages.lastOrNull { it.role == "assistant" && !it.isReasoning }
    LaunchedEffect(state.isStreaming, tail?.id, tail?.content?.length) {
        pulseState = reduceVisibleAssistantStreamPulse(
            previous = pulseState,
            isStreaming = state.isStreaming,
            tailId = tail?.id,
            contentLength = tail?.content?.length ?: 0,
        )
    }
    return pulseState.pulse
}

// NoConversationContent (the prior placeholder for ConversationState.
// NoConversation showing only "Start a conversation / Send a message to
// create a new conversation.") was removed when the empty-state for the
// in-chat "New Conversation" path was unified with the chat-list FAB
// path — both now render StarterPrompts. The strings
// screen_chat_empty_title and screen_chat_empty_subtitle remain in
// res/values/strings.xml in case a future surface needs them.
