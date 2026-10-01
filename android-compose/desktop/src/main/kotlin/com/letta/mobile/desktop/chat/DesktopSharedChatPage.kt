package com.letta.mobile.desktop.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.timeline.CanonicalTimelinePresentation
import com.letta.mobile.desktop.LocalDesktopChatFontScale
import com.letta.mobile.desktop.LocalDesktopChatFontScaleSetter
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfaceIntent
import com.letta.mobile.ui.chat.session.ChatSurfaceMode
import com.letta.mobile.ui.chat.session.ChatSurfaceModeReducer
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import com.letta.mobile.ui.chat.surface.ChatSurface
import com.letta.mobile.ui.chat.surface.ChatSurfaceAppearance
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.compose.rememberDirectoryPickerLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/*
 * letta-mobile-bglj6.1 (stage 3, desktop): the Conversations destination rendered with the shared
 * KMP chat page instead of ChatDetailPane, behind DesktopSharedChatPageFlag.
 */

/**
 * The one [DesktopChatSessionPort] for [controller], alive as long as the controller is. Its flows
 * run in a child of the composition's scope that is cancelled when the controller changes.
 */
@Composable
internal fun rememberDesktopChatSessionPort(
    controller: DesktopChatController,
    onA2uiAction: (A2uiAction) -> Unit,
): DesktopChatSessionPort {
    val latestA2uiAction by rememberUpdatedState(onA2uiAction)
    val latestFontScaleSetter by rememberUpdatedState(LocalDesktopChatFontScaleSetter.current)
    val parentScope = rememberCoroutineScope()
    val portScope = remember(controller) {
        CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    }
    DisposableEffect(portScope) { onDispose { portScope.cancel() } }
    return remember(controller, portScope) {
        DesktopChatSessionPort(
            controller = controller,
            scope = portScope,
            bindings = DesktopChatSessionBindings(
                onA2uiAction = { latestA2uiAction(it) },
                onSetFontScale = { latestFontScaleSetter(it) },
                canSubmitApprovals = { controller.canSubmitApprovals.value },
            ),
        )
    }
}

/** Where the page's host affordances go; each is the old page's existing destination. */
internal data class DesktopSharedChatPageNavigation(
    /** Opens the canvas beside the conversation (the composer's canvas button today). */
    val openCanvas: () -> Unit,
    val openAgent: (agentId: String) -> Unit,
    val openModelPicker: () -> Unit,
)

internal data class DesktopSharedChatPageState(
    val port: DesktopChatSessionPort,
    val pagedTimeline: CanonicalTimelinePresentation?,
    val hostInputs: DesktopChatComposerHostInputs,
    /** Drives the ambient glow, exactly as on the old page. */
    val isThinking: Boolean,
    val errorMessage: String?,
)

/**
 * The page's presentation step. Opening the canvas from full screen opens the existing canvas side
 * pane and keeps the page full screen: docking the chat into the canvas is stage 4.
 */
internal fun reduceDesktopChatSurfaceIntent(
    current: ChatSurfacePresentation,
    intent: ChatSurfaceIntent,
    openCanvas: () -> Unit,
): ChatSurfacePresentation {
    if (intent == ChatSurfaceIntent.OpenCanvas && current.mode == ChatSurfaceMode.FullScreen) {
        openCanvas()
        return current
    }
    return ChatSurfaceModeReducer.reduce(current, intent)
}

@Composable
internal fun DesktopSharedChatPage(
    state: DesktopSharedChatPageState,
    navigation: DesktopSharedChatPageNavigation,
    modifier: Modifier = Modifier,
) {
    val port = state.port
    SideEffect { port.updateHostInputs(state.hostInputs) }
    var presentation by remember { mutableStateOf(ChatSurfacePresentation.ChatFirst) }
    val host = rememberDesktopChatSurfaceHost(port, navigation)
    DesktopAmbientChatBackground(
        status = rememberDesktopAmbientStatus(state.isThinking, state.errorMessage),
        modifier = modifier
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.background),
    ) {
        ChatSurface(
            port = port,
            presentation = presentation,
            onIntent = { intent ->
                presentation = reduceDesktopChatSurfaceIntent(presentation, intent, navigation.openCanvas)
            },
            host = host,
            modifier = Modifier.fillMaxSize(),
            appearance = ChatSurfaceAppearance(fontScale = LocalDesktopChatFontScale.current),
            pagedTimeline = state.pagedTimeline,
        )
    }
}

/** The page's host navigation, with the same folder picker the old working-directory row used. */
@Composable
private fun rememberDesktopChatSurfaceHost(
    port: DesktopChatSessionPort,
    navigation: DesktopSharedChatPageNavigation,
): ChatSurfaceHost {
    val directoryPicker = rememberDirectoryPickerLauncher(
        dialogSettings = FileKitDialogSettings(title = "Choose working directory"),
    ) { directory ->
        directory?.let { port.actions.changeWorkingDirectory(it.file.absolutePath) }
    }
    val supportsWorkingDirectory = port.capabilities.workingDirectory
    return remember(navigation, directoryPicker, supportsWorkingDirectory) {
        ChatSurfaceHost(
            openCanvas = navigation.openCanvas,
            openAgent = navigation.openAgent,
            openModelPicker = navigation.openModelPicker,
            pickWorkingDirectory = if (supportsWorkingDirectory) ({ directoryPicker.launch() }) else null,
        )
    }
}
