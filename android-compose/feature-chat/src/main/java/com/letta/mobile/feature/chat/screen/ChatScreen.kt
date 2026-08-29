package com.letta.mobile.feature.chat.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.feature.chat.subagent.ActiveSubagentSource
import com.letta.mobile.ui.ambient.VisibleAssistantStreamPulseState
import com.letta.mobile.ui.ambient.reduceVisibleAssistantStreamPulse
import com.letta.mobile.ui.components.AmbientShaderAgentBackground
import com.letta.mobile.ui.theme.ChatBackground
import com.letta.mobile.ui.theme.LettaChatTheme
import kotlin.math.max

@Composable
internal fun ChatScreen(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    chatBackground: ChatBackground = ChatBackground.Default,
    chatMode: String = "simple",
    onBugCommand: (() -> Unit)? = null,
    onViewSubagentConversation: ((String, String) -> Unit)? = null,
    activeSubagentSource: ActiveSubagentSource? = null,
    selfTodoSource: com.letta.mobile.feature.chat.subagent.SelfTodoSource? = null,
    viewModel: AdminChatViewModel = hiltViewModel(),
) {
    val resolvedSubagentSource = activeSubagentSource ?: viewModel.activeSubagentSource
    val resolvedSelfTodoSource = selfTodoSource ?: viewModel.selfTodoSource
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val composerState by viewModel.composerState.collectAsStateWithLifecycle()
    val activeFontScale by viewModel.chatFontScale.collectAsStateWithLifecycle()
    val hapticsEnabled by viewModel.hapticsEnabled.collectAsStateWithLifecycle()

    val backgroundModifier = when (chatBackground) {
        is ChatBackground.Default -> Modifier
        is ChatBackground.SolidColor -> Modifier.background(chatBackground.color)
        is ChatBackground.Gradient -> Modifier.background(chatBackground.toBrush())
    }

    val navigation = remember(onBugCommand, onViewSubagentConversation) {
        ChatScreenNavigationCallbacks(
            onBugCommand = onBugCommand,
            onViewSubagentConversation = onViewSubagentConversation,
        )
    }

    val committedFontScale = activeFontScale
    LettaChatTheme(fontScale = committedFontScale ?: 1f) {
        var floatingBannerMessage by remember { mutableStateOf("") }
        val density = LocalDensity.current
        val currentConversationId = viewModel.conversationId?.value
        val subagentBarState = rememberChatScreenSubagentBarState(
            resolvedSubagentSource = resolvedSubagentSource,
            resolvedSelfTodoSource = resolvedSelfTodoSource,
            currentConversationId = currentConversationId,
        )
        // letta-mobile-6237v.2: outer .imePadding() (line 103) shrinks the layout
        // to the top of the keyboard when IME is open, so the composer
        // Column has no bottom-padding work to do. Hardcoding 0.dp here
        // makes the composer fill to the screen bottom edge when the
        // keyboard is down — the home indicator gesture bar overlays the
        // composer's bottom region, which is intended (composer bg is
        // opaque, so the gesture bar is still visually distinct).
        val bottomInsetDp = 0.dp
        val ambient = rememberChatScreenAmbientState()
        val streamActivityPulse = rememberVisibleAssistantStreamPulse(state)
        val streamingRevealPulse = rememberStreamingRevealHapticPulse(hapticsEnabled)

        ChatScreenEffects(
            params = ChatScreenEffectsParams(
                state = state,
                composerState = composerState,
                hapticsEnabled = hapticsEnabled,
                viewModel = viewModel,
                floatingBannerMessage = floatingBannerMessage,
                onFloatingBannerMessageChange = { floatingBannerMessage = it },
                ambient = ambient,
            ),
        )

        // letta-mobile-6237v.2: outer .imePadding() binds BOTH the
        // shader canvas and the ChatScreenLayout to keyboard height so
        // the shader shrinks with the keyboard. The Column inside still
        // receives `bottomInsetDp` for navbar-clearance.
        AmbientShaderAgentBackground(
            agentStatus = ambient.status,
            streamActivityPulse = streamActivityPulse,
            modifier = modifier
                .fillMaxSize()
                .imePadding()
                .then(backgroundModifier),
        ) {
            if (committedFontScale != null) {
                ChatScreenLayout(
                    params = ChatScreenLayoutParams(
                        state = state,
                        composerState = composerState,
                        viewModel = viewModel,
                        contentPadding = contentPadding,
                        chatBackground = chatBackground,
                        chatMode = chatMode,
                        navigation = navigation,
                        resolvedSubagentSource = resolvedSubagentSource,
                        subagentBarState = subagentBarState,
                        activeFontScale = committedFontScale,
                        onActiveFontScaleChange = viewModel::setChatFontScale,
                        bottomInsetDp = bottomInsetDp,
                        floatingBannerMessage = floatingBannerMessage,
                        onFloatingBannerMessageChange = { floatingBannerMessage = it },
                        streamingRevealPulse = streamingRevealPulse,
                    ),
                )
            }
        }
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
