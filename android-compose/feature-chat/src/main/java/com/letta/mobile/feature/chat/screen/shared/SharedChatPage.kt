package com.letta.mobile.feature.chat.screen.shared

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.chat.projection.toChatDisplayMode
import com.letta.mobile.feature.chat.screen.AdminChatViewModel
import com.letta.mobile.feature.chat.screen.ChatPagingPresentation
import com.letta.mobile.feature.chat.screen.ChatScreenNavigationCallbacks
import com.letta.mobile.feature.chat.screen.ChatScreenVoiceOverlay
import com.letta.mobile.feature.chat.screen.saveAttachment
import com.letta.mobile.feature.chat.screen.shareAttachment
import com.letta.mobile.feature.chat.voice.VoiceInputViewModel
import com.letta.mobile.ui.chat.session.ChatDockGeometry
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfaceIntent
import com.letta.mobile.ui.chat.session.ChatSurfaceMode
import com.letta.mobile.ui.chat.session.ChatSurfaceModeReducer
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import com.letta.mobile.ui.chat.surface.ChatCanvasActions
import com.letta.mobile.ui.chat.surface.ChatImageActions
import com.letta.mobile.ui.chat.surface.ChatPlatformStyle
import com.letta.mobile.ui.chat.surface.ChatSurface
import com.letta.mobile.ui.chat.surface.ChatSurfaceAppearance
import com.letta.mobile.ui.chat.surface.ChatSurfacePlatform
import com.letta.mobile.ui.chat.surface.ChatToolDetails
import com.letta.mobile.ui.chat.surface.DefaultFontScaleRange
import com.letta.mobile.ui.components.ChatLoadingIndicator
import com.letta.mobile.ui.components.LocalChatLoadingIndicator
import com.letta.mobile.ui.components.audio.HoldToDictateButton
import com.letta.mobile.ui.components.rememberReducedMotionEnabled
import com.letta.mobile.ui.haptics.LocalHaptics
import com.letta.mobile.ui.markdown.LocalSharedRichMarkdownRenderer
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlinx.coroutines.launch

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
    /** The full-screen composer's measured height, for [pageBackground] to keep the glow above it. */
    val onComposerHeightChange: ((Dp) -> Unit)? = null,
    /**
     * The status bar and the chat screen's floating header, which the page draws under: the
     * timeline and the canvas run edge to edge behind them; their content and chrome rest below.
     */
    val topChromeInset: Dp = 0.dp,
    /**
     * Told whether the host's floating header should hide: on a phone the canvas mode keeps the
     * top of the board clear (the head and the bar lead to the agent and the chat, and the board's
     * menu carries the agent switcher and menu). The full-screen page keeps the header.
     */
    val onHostHeaderHiddenChange: ((Boolean) -> Unit)? = null,
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
    val port = rememberAdminChatSessionPort(params.viewModel, params.navigation)
    val canvasSlot = LocalChatCanvasSlot.current
    val presentationState = rememberSaveable(stateSaver = PresentationSaver) {
        mutableStateOf(ChatSurfacePresentation.initial(params.openOnCanvas, hasCanvas = canvasSlot != null))
    }
    val presentation by presentationState
    // Where the docked panel sits; it opens as a full-width bottom panel on a phone.
    var dockGeometry by rememberSaveable(stateSaver = DockGeometrySaver) { mutableStateOf(ChatDockGeometry.Default) }
    val subagentSheet = rememberSharedChatSubagentSheetState(params.subagents.source)
    // Read live by the rings overlay, whose slot lambda is remembered with the platform.
    val currentSubagents by rememberUpdatedState(params.subagents)
    val host = remember(params.navigation, subagentSheet) {
        params.navigation.toSurfaceHost(openSubagent = subagentSheet::openDispatch)
    }
    val hasCanvasSlot = canvasSlot != null
    val onIntent = rememberSurfaceIntentHandler(presentationState, hasCanvasSlot, host)
    // Back from the full-screen page returns to the canvas, the default view.
    BackHandler(enabled = hasCanvasSlot && presentation.mode == ChatSurfaceMode.FullScreen) {
        onIntent(ChatSurfaceIntent.Collapse)
    }
    ReportHostHeaderHidden(
        report = params.onHostHeaderHiddenChange,
        headerHidden = hasCanvasSlot && presentation.mode != ChatSurfaceMode.FullScreen,
    )
    val target = ChatCanvasTarget(
        agentId = params.viewModel.agentId.value,
        conversationId = params.viewModel.conversationId?.value,
    )
    val topChromeInset = params.topChromeInset
    val canvas: (@Composable (ChatCanvasActions) -> Unit)? =
        canvasSlot?.let { slot -> { actions -> slot.content(target, actions, topChromeInset) } }
    val appearance = rememberSharedChatAppearance(params)
    // letta-mobile-bglj6.1.19: the shared page reads the OS "Remove animations" setting through
    // sharedUI's LocalReducedMotion, the same preference the legacy chat honours.
    val platform = rememberAndroidChatSurfacePlatform(
        pageBackground = params.pageBackground,
        onComposerHeightChange = params.onComposerHeightChange,
        // The rings show on the canvas too: subagent activity stays in sight in canvas mode.
        subagentRings = { SharedChatSubagentRings(subagentSheet, currentSubagents, params.navigation) },
        topChromeInset = topChromeInset,
    )
    // letta-mobile-bglj6.1.16: the shared rows render markdown through the designsystem
    // renderer the legacy chat used (highlighted code fences + copy, KaTeX, Mermaid,
    // autolinks, editorial padding); without a provider desktop and web keep the default.
    // letta-mobile-bglj6.1.17: the Android haptics backend behind the shared seam — the
    // chat page's cues (send flight, disclosures, approvals, scroll glide) route through
    // HapticPolicy to the designsystem Android realization, gated by the haptics setting.
    CompositionLocalProvider(
        LocalReducedMotion provides rememberReducedMotionEnabled(),
        LocalChatLoadingIndicator provides ExpressiveChatLoadingIndicator,
        LocalSharedRichMarkdownRenderer provides SharedChatRichMarkdown,
        LocalHaptics provides rememberSharedChatHaptics(params.hapticsEnabled),
    ) {
        Box(modifier) {
            ChatSurface(
                port = port,
                presentation = presentation,
                onIntent = onIntent,
                host = host,
                modifier = Modifier.fillMaxSize(),
                appearance = appearance,
                platform = platform,
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
            SharedChatSubagentBanner(subagentSheet)
            ChatScreenVoiceOverlay(modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * The page's intent handler. One instance for the page's lifetime: ChatScreen recomposes per
 * keystroke, and a fresh lambda here would reach every timeline row (letta-mobile-bglj6.1).
 */
@Composable
private fun rememberSurfaceIntentHandler(
    presentationState: MutableState<ChatSurfacePresentation>,
    hasCanvasSlot: Boolean,
    host: ChatSurfaceHost,
): (ChatSurfaceIntent) -> Unit {
    val currentHost by rememberUpdatedState(host)
    return remember(hasCanvasSlot) {
        { intent ->
            var presentation by presentationState
            if (!hasCanvasSlot && routesToCanvasNavigation(presentation, intent)) {
                currentHost.openCanvas?.invoke()
            } else {
                presentation = ChatSurfaceModeReducer.reduce(presentation, intent)
            }
        }
    }
}

/** Tells the host whether its floating header should hide, and shows it again on leaving. */
@Composable
@NonRestartableComposable
private fun ReportHostHeaderHidden(report: ((Boolean) -> Unit)?, headerHidden: Boolean) {
    if (report == null) return
    val currentReport by rememberUpdatedState(report)
    // After every composition, so the header is gone by the next frame; an unchanged value is a no-op.
    SideEffect { currentReport(headerHidden) }
    DisposableEffect(Unit) { onDispose { currentReport(false) } }
}

@Composable
private fun rememberSharedChatAppearance(params: SharedChatPageParams): ChatSurfaceAppearance =
    remember(params.chatMode, params.fontScale, params.hapticsEnabled) {
        ChatSurfaceAppearance(
            displayMode = params.chatMode.toChatDisplayMode(),
            fontScale = params.fontScale,
            hapticsEnabled = params.hapticsEnabled,
            // CachedSettingsRepository.setChatFontScale clamps to this range.
            fontScaleRange = DefaultFontScaleRange,
            // Touch idiom: a tool summary opens its calls in a bottom sheet.
            toolDetails = ChatToolDetails.Sheet,
            // The phone's idiom: the legacy composer bar, sheets, and the canvas's chat head.
            platformStyle = ChatPlatformStyle.Touch,
        )
    }

/** Full-screen "open canvas" leaves for the canvas screen until the docked mode ships. */
internal fun routesToCanvasNavigation(
    presentation: ChatSurfacePresentation,
    intent: ChatSurfaceIntent,
): Boolean = intent == ChatSurfaceIntent.OpenCanvas && presentation.mode == ChatSurfaceMode.FullScreen

@Composable
private fun rememberAdminChatSessionPort(
    viewModel: AdminChatViewModel,
    navigation: ChatScreenNavigationCallbacks,
): AdminChatSessionPort {
    val currentOnBugCommand by rememberUpdatedState(navigation.onBugCommand)
    val currentOpenConversation by rememberUpdatedState(navigation.onOpenConversation)
    val canOpenConversation = navigation.onOpenConversation != null
    return remember(viewModel, canOpenConversation) {
        AdminChatSessionPort(
            viewModel = viewModel,
            scope = viewModel.viewModelScope,
            onOpenBugReport = { currentOnBugCommand?.invoke() },
            onOpenConversation = if (canOpenConversation) {
                { agentId, conversationId -> currentOpenConversation?.invoke(agentId, conversationId) }
            } else {
                null
            },
        )
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
        // The header's agent pill, which the canvas mode's board menu stands in for.
        openAgentSwitcher = onOpenAgentSwitcher,
    )
}

/** Dictation needs the Hilt-provided recognizer; previews and tests without one get no mic. */
@Composable
private fun rememberAndroidChatSurfacePlatform(
    pageBackground: (@Composable (content: @Composable () -> Unit) -> Unit)?,
    onComposerHeightChange: ((Dp) -> Unit)?,
    subagentRings: @Composable () -> Unit,
    topChromeInset: Dp,
): ChatSurfacePlatform {
    val currentOnComposerHeight by rememberUpdatedState(onComposerHeightChange)
    val reportsComposerHeight = onComposerHeightChange != null
    val currentOverlay by rememberUpdatedState(subagentRings)
    // ChatScreen hands a fresh glow lambda per recomposition; forward to the latest one.
    val currentBackground by rememberUpdatedState(pageBackground)
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val isHiltHost = activity is dagger.hilt.internal.GeneratedComponentManager<*>
    val hasBackground = pageBackground != null
    val imageActions = rememberAndroidImageActions(context)
    return remember(isHiltHost, hasBackground, reportsComposerHeight, topChromeInset, imageActions) {
        ChatSurfacePlatform(
            voiceInput = if (isHiltHost) { onDictated -> DictationButton(onDictated) } else null,
            pageBackground = if (hasBackground) {
                { content -> currentBackground?.invoke(content) ?: content() }
            } else {
                null
            },
            // Touch first: the composer's keyboard-shortcut strip is desktop chrome.
            showKeyboardHints = false,
            topChromeInset = topChromeInset,
            timelineOverlay = { currentOverlay() },
            canvasOverlay = { currentOverlay() },
            onComposerHeightChange = if (reportsComposerHeight) {
                { height -> currentOnComposerHeight?.invoke(height) }
            } else {
                null
            },
            imageActions = imageActions,
        )
    }
}

/**
 * letta-mobile-bglj6.1.23: the shared image viewer's Save and Share, as the legacy viewer did
 * them (MediaStore under Pictures/Letta; the share sheet through the chat-image FileProvider).
 */
@Composable
private fun rememberAndroidImageActions(context: android.content.Context): ChatImageActions {
    val scope = rememberCoroutineScope()
    return remember(context, scope) {
        ChatImageActions(
            save = { image -> scope.launch { saveAttachment(context, image) } },
            share = { image -> scope.launch { shareAttachment(context, image) } },
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

/**
 * letta-mobile-bglj6.1.19: the expressive Material 3 LoadingIndicator the legacy reasoning header
 * shows, for the shared rows (Compose Multiplatform's material3 does not expose it). Still under
 * reduced motion: determinate at rest, so the shape never morphs.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val ExpressiveChatLoadingIndicator = ChatLoadingIndicator { color, still, modifier ->
    if (still) {
        LoadingIndicator(progress = { 0f }, modifier = modifier, color = color)
    } else {
        LoadingIndicator(modifier = modifier, color = color)
    }
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
