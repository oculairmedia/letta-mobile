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
import com.letta.mobile.ui.chat.session.ChatDockGeometry
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfaceIntent
import com.letta.mobile.ui.chat.session.ChatSurfaceMode
import com.letta.mobile.ui.chat.session.ChatSurfaceModeReducer
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import com.letta.mobile.ui.chat.surface.ChatCanvasActions
import com.letta.mobile.ui.chat.surface.ChatSurface
import com.letta.mobile.ui.chat.surface.ChatSurfaceAppearance
import com.letta.mobile.ui.chat.surface.ChatSurfacePlatform
import com.letta.mobile.ui.chat.surface.DefaultFontScaleRange
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
    /** Subagent dispatches open their todo sheet from these. */
    val subagents: SharedChatSubagentInputs,
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
    // Where the docked panel sits; it opens as a full-width bottom panel on a phone.
    var dockGeometry by rememberSaveable(stateSaver = DockGeometrySaver) { mutableStateOf(ChatDockGeometry.Default) }
    val subagentSheet = rememberSharedChatSubagentSheetState(params.subagents.source)
    // Read live by the rings overlay, whose slot lambda is remembered with the platform.
    val currentSubagents by rememberUpdatedState(params.subagents)
    val host = remember(params.navigation, subagentSheet) {
        params.navigation.toSurfaceHost(openSubagent = subagentSheet::openDispatch)
    }
    // One instance for the page's lifetime: ChatScreen recomposes per keystroke, and a fresh
    // lambda here would reach every timeline row (letta-mobile-bglj6.1).
    val currentHost by rememberUpdatedState(host)
    val hasCanvasSlot = canvasSlot != null
    val onIntent: (ChatSurfaceIntent) -> Unit = remember(hasCanvasSlot) {
        { intent ->
            if (!hasCanvasSlot && routesToCanvasNavigation(presentation, intent)) {
                currentHost.openCanvas?.invoke()
            } else {
                presentation = ChatSurfaceModeReducer.reduce(presentation, intent)
            }
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
            // CachedSettingsRepository.setChatFontScale clamps to this range.
            fontScaleRange = DefaultFontScaleRange,
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
            platform = rememberAndroidChatSurfacePlatform(
                pageBackground = params.pageBackground,
                timelineOverlay = { SharedChatSubagentRings(subagentSheet, currentSubagents, params.navigation) },
            ),
            pagedTimeline = params.pagingPresentation?.canonical,
            canvas = canvas,
            dockGeometry = dockGeometry,
            onDockGeometryChange = { dockGeometry = it },
        )
        SharedChatSubagentSheet(
            state = subagentSheet,
            inputs = params.subagents,
            currentConversationId = target.conversationId,
            navigation = params.navigation,
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

private fun ChatScreenNavigationCallbacks.toSurfaceHost(
    openSubagent: (toolCallId: String, subagentAgentId: String?, description: String) -> Unit,
): ChatSurfaceHost {
    val openPane = onOpenAgentPane
    return ChatSurfaceHost(
        openCanvas = onOpenCanvas,
        openAgent = openPane?.let { { _: String -> it() } },
        openSubagent = openSubagent,
        openModelPicker = null,
        // The composer companion mascot opens the agent drawer, as the legacy page's does.
        openAgentPane = openPane,
    )
}

/** Dictation needs the Hilt-provided recognizer; previews and tests without one get no mic. */
@Composable
private fun rememberAndroidChatSurfacePlatform(
    pageBackground: (@Composable (content: @Composable () -> Unit) -> Unit)?,
    timelineOverlay: @Composable () -> Unit,
): ChatSurfacePlatform {
    val currentOverlay by rememberUpdatedState(timelineOverlay)
    // ChatScreen hands a fresh glow lambda per recomposition; forward to the latest one.
    val currentBackground by rememberUpdatedState(pageBackground)
    val activity = LocalContext.current as? android.app.Activity
    val isHiltHost = activity is dagger.hilt.internal.GeneratedComponentManager<*>
    val hasBackground = pageBackground != null
    return remember(isHiltHost, hasBackground) {
        ChatSurfacePlatform(
            voiceInput = if (isHiltHost) { onDictated -> DictationButton(onDictated) } else null,
            pageBackground = if (hasBackground) {
                { content -> currentBackground?.invoke(content) ?: content() }
            } else {
                null
            },
            // Touch first: the composer's keyboard-shortcut strip is desktop chrome.
            showKeyboardHints = false,
            timelineOverlay = { currentOverlay() },
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

/** The docked panel's placement across configuration changes and process death. */
private val DockGeometrySaver = Saver<ChatDockGeometry, String>(
    save = { kotlinx.serialization.json.Json.encodeToString(ChatDockGeometry.serializer(), it) },
    restore = { saved ->
        runCatching { kotlinx.serialization.json.Json.decodeFromString(ChatDockGeometry.serializer(), saved) }.getOrNull()
    },
)
