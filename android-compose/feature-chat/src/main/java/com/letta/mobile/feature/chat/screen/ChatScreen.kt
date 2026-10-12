package com.letta.mobile.feature.chat.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
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
import com.letta.mobile.feature.chat.subagent.SelfTodoSource
import com.letta.mobile.feature.chat.voice.VoiceInputViewModel
import com.letta.mobile.ui.ambient.VisibleAssistantStreamPulseState
import com.letta.mobile.ui.ambient.reduceVisibleAssistantStreamPulse
import com.letta.mobile.ui.components.AmbientShaderAgentBackground
import com.letta.mobile.ui.components.audio.VoiceRecognizerOverlay
import com.letta.mobile.ui.theme.ChatBackground
import com.letta.mobile.ui.theme.LettaChatTheme

/**
 * The Android chat route's binding: the shared KMP chat page (sharedUI ChatSurface through
 * [SharedChatPage]) over the agent's ambient glow. Hard cut S9 (letta-mobile-ckmub.2.2): this is
 * the only chat page; the legacy Android layout, its rows, list and composer are gone.
 */
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
    onOpenAgentSwitcher: (() -> Unit)? = null,
    /** letta-mobile-bzvro.15/.16: opens a conversation (agent id, conversation id); a fork opens here. */
    onOpenConversation: ((String, String) -> Unit)? = null,
    /** The shared page's canvas mode wants the board's top clear: true while the host's header should hide. */
    onHostHeaderHiddenChange: ((Boolean) -> Unit)? = null,
    /** letta-mobile-y5q9z: the agent's conversations for the shared page's canvas bubble "+". */
    recentInteractions: com.letta.mobile.ui.chat.surface.recents.ChatRecentInteractions? = null,
    activeSubagentSource: ActiveSubagentSource? = null,
    selfTodoSource: SelfTodoSource? = null,
    viewModel: AdminChatViewModel = hiltViewModel(),
) {
    val resolvedSubagentSource = activeSubagentSource ?: viewModel.activeSubagentSource
    val resolvedSelfTodoSource = selfTodoSource ?: viewModel.selfTodoSource
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pagingPresentation by viewModel.pagingPresentation.collectAsStateWithLifecycle()
    val activeFontScale by viewModel.chatFontScale.collectAsStateWithLifecycle()
    val hapticsEnabled by viewModel.hapticsEnabled.collectAsStateWithLifecycle()
    val openChatsOnCanvas by viewModel.openChatsOnCanvas.collectAsStateWithLifecycle()

    val backgroundModifier = when (chatBackground) {
        is ChatBackground.Default -> Modifier
        is ChatBackground.SolidColor -> Modifier.background(chatBackground.color)
        is ChatBackground.Gradient -> Modifier.background(chatBackground.toBrush())
    }

    val navigation = remember(
        onBugCommand, onViewSubagentConversation, onOpenAgentPane, onOpenCanvas, onOpenAgentSwitcher, onOpenConversation,
    ) {
        ChatScreenNavigationCallbacks(
            onBugCommand = onBugCommand,
            onViewSubagentConversation = onViewSubagentConversation,
            onOpenAgentPane = onOpenAgentPane,
            onOpenCanvas = onOpenCanvas,
            onOpenAgentSwitcher = onOpenAgentSwitcher,
            onOpenConversation = onOpenConversation,
        )
    }

    // The font scale loads from settings; the page waits for it so its rows never re-lay out on arrival.
    val fontScale = activeFontScale ?: return
    LettaChatTheme(fontScale = fontScale) {
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

        // The composer's height, as the page measures it, so the glow can stay above it.
        var composerHeight by remember { mutableStateOf(androidx.compose.ui.unit.Dp.Unspecified) }
        // letta-mobile-bglj6.1: the shared page draws the glow behind its own full-screen
        // layer (it is opaque over the docked canvas), and its scaffold owns the IME inset.
        SharedChatPage(
            params = SharedChatPageParams(
                viewModel = viewModel,
                navigation = navigation,
                chatMode = chatMode,
                fontScale = fontScale,
                hapticsEnabled = hapticsEnabled,
                pagingPresentation = pagingPresentation,
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
                        composerHeight = { composerHeight },
                        modifier = Modifier.fillMaxSize().then(backgroundModifier),
                    ) { content() }
                },
                // The shared composer reports its height so the glow keeps clear of it.
                onComposerHeightChange = { composerHeight = it },
                // Edge to edge: the page draws under the status bar and the floating header, and
                // only rests its content (and the canvas's chrome) below them.
                topChromeInset = contentPadding.calculateTopPadding(),
                onHostHeaderHiddenChange = onHostHeaderHiddenChange,
                recentInteractions = recentInteractions,
            ),
            modifier = modifier.fillMaxSize(),
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

/** The dictation overlay over the page while the recognizer listens (Hilt hosts only). */
@Composable
internal fun ChatScreenVoiceOverlay(modifier: Modifier = Modifier) {
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    if (activity !is dagger.hilt.internal.GeneratedComponentManager<*>) return
    val voice: VoiceInputViewModel = hiltViewModel()
    val voiceState by voice.uiState.collectAsStateWithLifecycle()
    VoiceRecognizerOverlay(
        visible = voiceState.recognizing,
        recognizedText = voiceState.recognizedText,
        amplitude = voiceState.amplitude,
        modifier = modifier,
    )
}
