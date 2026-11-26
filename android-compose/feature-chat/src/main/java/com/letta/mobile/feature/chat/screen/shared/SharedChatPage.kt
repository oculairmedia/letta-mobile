package com.letta.mobile.feature.chat.screen.shared

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.letta.mobile.data.chat.display.ChatDisplayOptions
import com.letta.mobile.data.chat.projection.toChatDisplayMode
import com.letta.mobile.feature.chat.screen.AndroidAgentMessageContext
import com.letta.mobile.feature.chat.screen.LocalAndroidAgentMessageContext
import com.letta.mobile.feature.chat.screen.LocalChatModelPicker
import com.letta.mobile.data.repository.modelcontrol.ModelPickerBinding
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.timeline.CanonicalTimelinePresentation
import com.letta.mobile.data.voice.session.VoiceChatSessions
import com.letta.mobile.feature.chat.screen.LocalSharedNavDrawer
import com.letta.mobile.feature.chat.screen.ChatScreenNavigationCallbacks
import com.letta.mobile.feature.chat.screen.ChatScreenVoiceOverlay
import com.letta.mobile.feature.chat.util.saveAttachment
import com.letta.mobile.feature.chat.util.shareAttachment
import com.letta.mobile.feature.chat.voice.VoiceInputViewModel
import com.letta.mobile.ui.chat.session.ChatDockGeometry
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfaceIntent
import com.letta.mobile.ui.chat.session.ChatSurfaceMode
import com.letta.mobile.ui.chat.session.ChatSurfaceModeReducer
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import com.letta.mobile.ui.chat.surface.ChatCanvasActions
import com.letta.mobile.ui.chat.surface.ChatImageActions
import com.letta.mobile.ui.chat.surface.ChatSurface
import com.letta.mobile.ui.chat.surface.ChatSurfaceAppearance
import com.letta.mobile.ui.chat.surface.ChatSurfacePlatform
import com.letta.mobile.ui.chat.surface.DefaultFontScaleRange
import com.letta.mobile.ui.chat.surface.recents.ChatRecentInteractions
import com.letta.mobile.ui.chat.subagents.SubagentDualViewFrame
import com.letta.mobile.ui.chat.subagents.rememberSubagentDualView
import com.letta.mobile.ui.components.audio.HoldToDictateButton
import com.letta.mobile.ui.theme.rememberReducedMotionEnabled
import com.letta.mobile.ui.haptics.LocalHaptics
import com.letta.mobile.ui.markdown.LocalSharedRichMarkdownRenderer
import com.letta.mobile.ui.theme.LocalReducedMotion
import com.letta.mobile.ui.shell.pages.workspace.WorkspaceFiles
import kotlinx.coroutines.launch

/** The page's workspace files: the viewer its file chips open, and the `@` search its draft drives. */
internal class ChatWorkspaceFiles(val workspace: WorkspaceFiles, val onDraftChanged: (String) -> Unit)

/** The conversation the page shows, as the host binds it; the rows come from [SharedChatPageParams.pagedTimeline]. */
internal data class SharedChatPageSession(
    /** The composer, actions and run state the page binds. */
    val port: ChatSessionPort,
    /** Voice mode's chat; null hides voice mode. */
    val voiceSessions: VoiceChatSessions?,
    /** The file chips' viewer and the `@` file search; null shows neither. */
    val workspaceFiles: ChatWorkspaceFiles?,
    /** The agent and conversation on screen (the docked canvas's board, the subagent dual view's parent). */
    val target: ChatCanvasTarget,
)

/** letta-mobile-bglj6.1: what the Android chat screen hands the shared chat page. */
internal data class SharedChatPageParams(
    val session: SharedChatPageSession,
    val navigation: ChatScreenNavigationCallbacks,
    val chatMode: String,
    val fontScale: Float,
    val hapticsEnabled: Boolean,
    /** The rows the timeline draws: the conversation's canonical presentation. */
    val pagedTimeline: CanonicalTimelinePresentation?,
    /** "Open conversations on the canvas": the initial presentation only. */
    val openOnCanvas: Boolean,
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
    /** letta-mobile-y5q9z: the agent's conversations, for the canvas bubble's "+" (recent interactions). */
    val recentInteractions: ChatRecentInteractions? = null,
)

/**
 * letta-mobile-bglj6.1: Android's binding of the shared KMP chat page (sharedUI [ChatSurface]),
 * drawn in place of the legacy chat layout while the "Shared chat page (preview)" setting is on.
 *
 * With the app's canvas ([LocalChatCanvasSlot]) the conversation opens on its canvas with the
 * chat docked under it; expanding the dock gives the full-screen page, and swipe up on its
 * prompt (or Back) returns to the canvas. A conversation that opened on the full-screen page
 * leaves on Back until the person has been on its canvas: Back never shows a canvas they did not
 * open. Without a canvas slot the page opens full-screen and "open canvas" uses the canvas route.
 */
@Composable
internal fun SharedChatPage(params: SharedChatPageParams, modifier: Modifier = Modifier) {
    val port = params.session.port
    val canvasSlot = LocalChatCanvasSlot.current
    val presentationState = rememberSaveable(stateSaver = PresentationSaver) {
        mutableStateOf(ChatSurfacePresentation.initial(params.openOnCanvas, hasCanvas = canvasSlot != null))
    }
    val presentation by presentationState
    // Whether the person has been on the canvas: only then does Back from the full page return to it.
    val canvasSeen = rememberSaveable { mutableStateOf(presentationState.value.canvasVisible) }
    // Where the docked panel sits; it opens as a full-width bottom panel on a phone.
    var dockGeometry by rememberSaveable(stateSaver = DockGeometrySaver) { mutableStateOf(ChatDockGeometry.Default) }
    val subagentSheet = rememberSharedChatSubagentSheetState(params.subagents.source)
    // Read live by the rings overlay, whose slot lambda is remembered with the platform.
    val currentSubagents by rememberUpdatedState(params.subagents)
    val agentMessages = LocalAndroidAgentMessageContext.current
    val modelPicker = LocalChatModelPicker.current
    val host = remember(params.navigation, subagentSheet, agentMessages, modelPicker) {
        params.navigation.toSurfaceHost(subagentSheet, modelPicker).withAgentMessages(agentMessages)
    }
    val hasCanvasSlot = canvasSlot != null
    val onIntent = rememberSurfaceIntentHandler(presentationState, hasCanvasSlot, host)
    LaunchedEffect(presentation.canvasVisible) { if (presentation.canvasVisible) canvasSeen.value = true }
    // Back from the full-screen page returns to the canvas the person came from; otherwise the host takes it.
    BackHandler(enabled = hasCanvasSlot && backCollapsesToCanvas(presentation.mode, canvasSeen.value)) {
        onIntent(ChatSurfaceIntent.Collapse)
    }
    ReportHostHeaderHidden(
        report = params.onHostHeaderHiddenChange,
        headerHidden = hasCanvasSlot && presentation.mode != ChatSurfaceMode.FullScreen,
    )
    val target = params.session.target
    val canvas = canvasContent(canvasSlot, target, params.topChromeInset)
    val appearance = rememberSharedChatAppearance(params, rememberSharedChatDisplayOptions())
    // letta-mobile-bglj6.1.19: the shared page reads the OS "Remove animations" setting through
    // sharedUI's LocalReducedMotion, the same preference the legacy chat honours.
    val dualView = rememberSubagentDualView(target.conversationId?.let(::ConversationId), params.viewSessions, params.fullSubagentView())
    val platform = rememberAndroidChatSurfacePlatform(
        pageBackground = params.pageBackground,
        onComposerHeightChange = params.onComposerHeightChange,
        // The rings show on the canvas too: subagent activity stays in sight in canvas mode.
        subagentRings = { SharedChatSubagentRings(subagentSheet, currentSubagents, params.dualNavigation(dualView)) },
        topChromeInset = params.topChromeInset,
    )
    // letta-mobile-bglj6.1.16: the shared rows render markdown through the designsystem
    // renderer the legacy chat used (highlighted code fences + copy, KaTeX, Mermaid,
    // autolinks, editorial padding); without a provider desktop and web keep the default.
    // letta-mobile-bglj6.1.17: the Android haptics backend behind the shared seam — the
    // chat page's cues (send flight, disclosures, approvals, scroll glide) route through
    // HapticPolicy to the designsystem Android realization, gated by the haptics setting.
    SharedChatPageLocals(hapticsEnabled = params.hapticsEnabled) {
        SharedChatWorkspaceFilesBox(params.session.workspaceFiles, port, modifier) {
            SubagentDualViewFrame(dualView, Modifier.fillMaxSize(), params.dualViewOptions(appearance, host)) { parentModifier ->
                ChatSurface(
                    port = port,
                    presentation = presentation,
                    onIntent = onIntent,
                    host = host,
                    modifier = parentModifier.fillMaxSize(),
                    appearance = appearance,
                    platform = platform,
                    pagedTimeline = params.pagedTimeline,
                    canvas = canvas,
                    dockGeometry = dockGeometry,
                    onDockGeometryChange = { dockGeometry = it },
                    recents = params.recentInteractions,
                    voiceSessions = params.session.voiceSessions,
                )
            }
            SharedChatSubagentSheet(
                state = subagentSheet,
                inputs = params.subagents,
                currentConversationId = target.conversationId,
                navigation = params.dualNavigation(dualView),
            )
            SharedChatSubagentBanner(subagentSheet)
            ChatScreenVoiceOverlay(modifier = Modifier.fillMaxSize())
        }
    }
}

/** Android's bindings behind the page's seams; the row parity harness (ckmub.1) draws inside them too. */
@Composable
internal fun SharedChatPageLocals(hapticsEnabled: Boolean, content: @Composable () -> Unit) = CompositionLocalProvider(
    LocalReducedMotion provides rememberReducedMotionEnabled(),
    LocalSharedRichMarkdownRenderer provides SharedChatRichMarkdown,
    LocalHaptics provides rememberSharedChatHaptics(hapticsEnabled),
    content = content,
)

/** The canvas the page docks the chat under, or null without the app's canvas slot. */
private fun canvasContent(
    canvasSlot: ChatCanvasSlot?,
    target: ChatCanvasTarget,
    topChromeInset: Dp,
): (@Composable (ChatCanvasActions) -> Unit)? =
    canvasSlot?.let { slot -> { actions -> slot.content(target, actions, topChromeInset) } }

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
private fun rememberSharedChatAppearance(params: SharedChatPageParams, displayOptions: ChatDisplayOptions): ChatSurfaceAppearance =
    remember(params.chatMode, params.fontScale, params.hapticsEnabled, displayOptions) {
        ChatSurfaceAppearance(
            displayMode = params.chatMode.toChatDisplayMode(),
            // letta-mobile-zxk2n.5: the drawer's Chat options (reasoning, compactions, usage).
            displayOptions = displayOptions,
            fontScale = params.fontScale,
            hapticsEnabled = params.hapticsEnabled,
            // CachedSettingsRepository.setChatFontScale clamps to this range.
            fontScaleRange = DefaultFontScaleRange,
        )
    }

/** The shared drawer's display options; the defaults where the scaffold has no shared drawer. */
@Composable
private fun rememberSharedChatDisplayOptions(): ChatDisplayOptions {
    val drawer = LocalSharedNavDrawer.current ?: return ChatDisplayOptions.Default
    return drawer.displayOptions.collectAsStateWithLifecycle().value
}

/** Full-screen "open canvas" leaves for the canvas screen until the docked mode ships. */
/**
 * Whether the system Back on the page goes back to its canvas: only from the full-screen page, and
 * only once the person has been on the canvas ([canvasSeen]). A conversation opened straight onto
 * the full page leaves it on Back instead, so Back never passes through a canvas nobody opened.
 */
internal fun backCollapsesToCanvas(mode: ChatSurfaceMode, canvasSeen: Boolean): Boolean =
    mode == ChatSurfaceMode.FullScreen && canvasSeen

internal fun routesToCanvasNavigation(
    presentation: ChatSurfacePresentation,
    intent: ChatSurfaceIntent,
): Boolean = intent == ChatSurfaceIntent.OpenCanvas && presentation.mode == ChatSurfaceMode.FullScreen

private fun ChatScreenNavigationCallbacks.toSurfaceHost(
    subagents: SharedChatSubagentSheetState,
    modelPicker: ModelPickerBinding?,
): ChatSurfaceHost {
    val openPane = onOpenAgentPane
    return ChatSurfaceHost(
        openCanvas = onOpenCanvas,
        openAgent = openPane?.let { { _: String -> it() } },
        openSubagent = subagents::openDispatch,
        modelPicker = modelPicker,
        // The composer companion mascot opens the agent drawer, as the legacy page's does.
        openAgentPane = openPane,
        // The header's agent pill, which the canvas mode's board menu stands in for.
        openAgentSwitcher = onOpenAgentSwitcher,
    )
}

/** An inter-agent label names its agent and opens that agent's latest conversation, as the legacy bubbles did. */
private fun ChatSurfaceHost.withAgentMessages(agentMessages: AndroidAgentMessageContext): ChatSurfaceHost =
    copy(openAgent = agentMessages.onAgentClick ?: openAgent, resolveAgentName = agentMessages.resolveName)

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
    // letta-mobile-7a1vl: the page collects transcripts while composed; the view model never holds
    // onDictated (it captures the page's send actions, and the recognizer's binder outlives us).
    val currentOnDictated by rememberUpdatedState(onDictated)
    LaunchedEffect(voice) {
        voice.dictations.collect { dictated -> currentOnDictated(dictated) }
    }
    HoldToDictateButton(
        isRecognizing = state.recognizing,
        onStart = voice::startSpeechRecognition,
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
