package com.letta.mobile.feature.chat.screen.shared

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.chat.projection.toChatDisplayMode
import com.letta.mobile.feature.chat.screen.AdminChatViewModel
import com.letta.mobile.feature.chat.screen.ChatPagingPresentation
import com.letta.mobile.feature.chat.screen.ChatScreenNavigationCallbacks
import com.letta.mobile.feature.chat.screen.ChatScreenVoiceOverlay
import com.letta.mobile.feature.chat.voice.VoiceInputViewModel
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfaceIntent
import com.letta.mobile.ui.chat.session.ChatSurfaceMode
import com.letta.mobile.ui.chat.session.ChatSurfaceModeReducer
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import com.letta.mobile.ui.chat.surface.ChatCanvasActions
import com.letta.mobile.ui.chat.surface.ChatSurface
import com.letta.mobile.ui.chat.surface.ChatSurfaceAppearance
import com.letta.mobile.ui.chat.surface.ChatSurfacePlatform
import com.letta.mobile.ui.components.audio.HoldToDictateButton

/** letta-mobile-bglj6.1: what the Android chat screen hands the shared chat page. */
internal data class SharedChatPageParams(
    val viewModel: AdminChatViewModel,
    val navigation: ChatScreenNavigationCallbacks,
    val chatMode: String,
    val fontScale: Float,
    val hapticsEnabled: Boolean,
    val pagingPresentation: ChatPagingPresentation?,
    /** "Open conversations on the canvas": the initial presentation only. */
    val openOnCanvas: Boolean = true,
    /** The ambient agent glow, drawn behind the full-screen page. */
    val pageBackground: (@Composable (content: @Composable () -> Unit) -> Unit)? = null,
)

/**
 * letta-mobile-bglj6.1: Android's binding of the shared KMP chat page (sharedUI [ChatSurface]),
 * drawn in place of the legacy chat layout while the "Shared chat page (preview)" setting is on.
 *
 * With the app's canvas ([LocalChatCanvasSlot]) the conversation opens on its canvas with the
 * chat docked under it; expanding the dock gives the full-screen page, and swipe up on its
 * prompt (or Back) returns to the canvas. Without a canvas slot the page opens full-screen and
 * "open canvas" uses the canvas route.
 */
@Composable
internal fun SharedChatPage(params: SharedChatPageParams, modifier: Modifier = Modifier) {
    val port = rememberAdminChatSessionPort(params.viewModel, params.navigation.onBugCommand)
    val canvasSlot = LocalChatCanvasSlot.current
    var presentation by rememberSaveable(stateSaver = PresentationSaver) {
        mutableStateOf(ChatSurfacePresentation.initial(params.openOnCanvas, hasCanvas = canvasSlot != null))
    }
    val host = remember(params.navigation) { params.navigation.toSurfaceHost() }
    val onIntent: (ChatSurfaceIntent) -> Unit = { intent ->
        if (canvasSlot == null && routesToCanvasNavigation(presentation, intent)) {
            host.openCanvas?.invoke()
        } else {
            presentation = ChatSurfaceModeReducer.reduce(presentation, intent)
        }
    }
    // Back from the full-screen page returns to the canvas, the default view.
    BackHandler(enabled = canvasSlot != null && presentation.mode == ChatSurfaceMode.FullScreen) {
        onIntent(ChatSurfaceIntent.Collapse)
    }
    val target = ChatCanvasTarget(
        agentId = params.viewModel.agentId.value,
        conversationId = params.viewModel.conversationId?.value,
    )
    val canvas: (@Composable (ChatCanvasActions) -> Unit)? =
        canvasSlot?.let { slot -> { actions -> slot.content(target, actions) } }
    val appearance = remember(params.chatMode, params.fontScale, params.hapticsEnabled) {
        ChatSurfaceAppearance(
            displayMode = params.chatMode.toChatDisplayMode(),
            fontScale = params.fontScale,
            hapticsEnabled = params.hapticsEnabled,
        )
    }
    Box(modifier) {
        ChatSurface(
            port = port,
            presentation = presentation,
            onIntent = onIntent,
            host = host,
            modifier = Modifier.fillMaxSize(),
            appearance = appearance,
            platform = rememberAndroidChatSurfacePlatform(params.pageBackground),
            pagedTimeline = params.pagingPresentation?.canonical,
            canvas = canvas,
        )
        ChatScreenVoiceOverlay(modifier = Modifier.fillMaxSize())
    }
}

/** Full-screen "open canvas" leaves for the canvas screen until the docked mode ships. */
internal fun routesToCanvasNavigation(
    presentation: ChatSurfacePresentation,
    intent: ChatSurfaceIntent,
): Boolean = intent == ChatSurfaceIntent.OpenCanvas && presentation.mode == ChatSurfaceMode.FullScreen

@Composable
private fun rememberAdminChatSessionPort(
    viewModel: AdminChatViewModel,
    onBugCommand: (() -> Unit)?,
): AdminChatSessionPort {
    val currentOnBugCommand by rememberUpdatedState(onBugCommand)
    return remember(viewModel) {
        AdminChatSessionPort(viewModel, viewModel.viewModelScope) { currentOnBugCommand?.invoke() }
    }
}

private fun ChatScreenNavigationCallbacks.toSurfaceHost(): ChatSurfaceHost {
    val openPane = onOpenAgentPane
    return ChatSurfaceHost(
        openCanvas = onOpenCanvas,
        openAgent = openPane?.let { { _: String -> it() } },
        viewSubagentConversation = onViewSubagentConversation,
        openModelPicker = null,
        // The composer companion mascot opens the agent drawer, as the legacy page's does.
        openAgentPane = openPane,
    )
}

/** Dictation needs the Hilt-provided recognizer; previews and tests without one get no mic. */
@Composable
private fun rememberAndroidChatSurfacePlatform(
    pageBackground: (@Composable (content: @Composable () -> Unit) -> Unit)?,
): ChatSurfacePlatform {
    val activity = LocalContext.current as? android.app.Activity
    val isHiltHost = activity is dagger.hilt.internal.GeneratedComponentManager<*>
    return remember(isHiltHost, pageBackground) {
        ChatSurfacePlatform(
            voiceInput = if (isHiltHost) { onDictated -> DictationButton(onDictated) } else null,
            pageBackground = pageBackground,
        )
    }
}

@Composable
private fun DictationButton(onDictated: (String) -> Unit) {
    val voice: VoiceInputViewModel = hiltViewModel()
    val state by voice.uiState.collectAsStateWithLifecycle()
    HoldToDictateButton(
        isRecognizing = state.recognizing,
        onStart = {
            voice.startSpeechRecognition { dictated -> if (dictated.isNotBlank()) onDictated(dictated) }
        },
        onStop = voice::stopSpeechRecognition,
        onCancel = voice::cancelSpeechRecognition,
    )
}

/** Only the mode survives process death; floating stays disabled until in-app floating ships. */
private val PresentationSaver = Saver<ChatSurfacePresentation, String>(
    save = { it.mode.name },
    restore = { name -> ChatSurfacePresentation(mode = ChatSurfaceMode.valueOf(name)) },
)
