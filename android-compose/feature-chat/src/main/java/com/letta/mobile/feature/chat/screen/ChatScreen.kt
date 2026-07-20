package com.letta.mobile.feature.chat.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.feature.chat.subagent.ActiveSubagentSource
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
    val fontScale by viewModel.chatFontScale.collectAsStateWithLifecycle()
    val hapticsEnabled by viewModel.hapticsEnabled.collectAsStateWithLifecycle()

    var activeFontScale by remember { mutableFloatStateOf(fontScale) }
    LaunchedEffect(fontScale) { activeFontScale = fontScale }

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

    LettaChatTheme(fontScale = activeFontScale) {
        var floatingBannerMessage by remember { mutableStateOf("") }
        val density = LocalDensity.current
        val currentConversationId = viewModel.conversationId?.value
        val subagentBarState = rememberChatScreenSubagentBarState(
            resolvedSubagentSource = resolvedSubagentSource,
            resolvedSelfTodoSource = resolvedSelfTodoSource,
            currentConversationId = currentConversationId,
        )
        val imeBottomPx = WindowInsets.ime.getBottom(density)
        val navBottomPx = WindowInsets.navigationBars.getBottom(density)
        val bottomInsetDp = with(density) { max(imeBottomPx, navBottomPx).toDp() }
        val ambient = rememberChatScreenAmbientState()
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

        AmbientShaderAgentBackground(
            agentStatus = ambient.status,
            modifier = modifier
                .fillMaxSize()
                .then(backgroundModifier),
        ) {
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
                    activeFontScale = activeFontScale,
                    onActiveFontScaleChange = { activeFontScale = it },
                    bottomInsetDp = bottomInsetDp,
                    floatingBannerMessage = floatingBannerMessage,
                    onFloatingBannerMessageChange = { floatingBannerMessage = it },
                    streamingRevealPulse = streamingRevealPulse,
                ),
            )
        }
    }
}

// NoConversationContent (the prior placeholder for ConversationState.
// NoConversation showing only "Start a conversation / Send a message to
// create a new conversation.") was removed when the empty-state for the
// in-chat "New Conversation" path was unified with the chat-list FAB
// path — both now render StarterPrompts. The strings
// screen_chat_empty_title and screen_chat_empty_subtitle remain in
// res/values/strings.xml in case a future surface needs them.
