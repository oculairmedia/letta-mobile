package com.letta.mobile.desktop

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.application.NucleusApplicationScope
import dev.nucleusframework.energymanager.EnergyManager
import dev.nucleusframework.globalhotkey.GlobalHotKeyManager
import dev.nucleusframework.globalhotkey.HotKeyModifier
import dev.nucleusframework.launcher.macos.DockMenuItem
import dev.nucleusframework.launcher.macos.MacOsDockMenu
import dev.nucleusframework.launcher.windows.JumpListItem
import dev.nucleusframework.launcher.windows.WindowsBadgeManager
import dev.nucleusframework.launcher.windows.WindowsJumpListManager
import dev.nucleusframework.media.control.MediaControlEvent
import dev.nucleusframework.media.control.MediaControlService
import dev.nucleusframework.media.control.MediaMetadata
import dev.nucleusframework.media.control.MediaPlaybackState
import dev.nucleusframework.media.control.MediaPlaybackStatus
import dev.nucleusframework.taskbarprogress.TaskbarProgress
import dev.nucleusframework.composenativetray.tray.api.Tray
import com.letta.mobile.data.model.SubagentStatus
import com.letta.mobile.data.model.UiMessage
import java.awt.Frame
import java.awt.Window
import java.awt.event.KeyEvent
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

internal fun activateDesktopWindow(window: Window) {
    SwingUtilities.invokeLater {
        window.isVisible = true
        if (window is Frame) {
            window.extendedState = window.extendedState and Frame.ICONIFIED.inv()
        }
        window.toFront()
        window.requestFocus()
    }
}

internal data class DesktopNucleusEffectBindings(
    val applicationScope: NucleusApplicationScope,
    val window: Window,
    val controller: DesktopNucleusController,
    /** Latest assistant reply preview for a conversation, for the toast body. */
    val replyPreviewFor: (String) -> String?,
    /** Bring the given conversation on screen (toast body click / Reply button). */
    val onOpenConversation: (String) -> Unit,
    /** Send inline-typed toast text into the conversation without activating. */
    val onReplyToConversation: (String, String) -> Unit,
)

internal data class DesktopNucleusEffectState(
    val isAgentWorking: Boolean,
    val agentName: String,
    val errorMessage: String?,
    /** Conversation the in-flight agent work belongs to, when known. */
    val workingConversationId: String?,
    /** Determinate work progress (0..1) when subagent steps are countable. */
    val workProgress: Double? = null,
)

/**
 * Deterministic taskbar progress from subagent steps: once at least one
 * subagent of the active batch has finished, expose completed/total; while
 * everything is still running (or there are no subagents) return null so the
 * taskbar shows the indeterminate pulse instead. Statuses are the wire
 * strings from [SubagentStatus].
 */
internal fun subagentWorkProgress(statuses: List<String>): Double? {
    if (statuses.isEmpty()) return null
    val done = statuses.count { it != SubagentStatus.RUNNING }
    if (done == 0) return null
    return done.toDouble() / statuses.size
}

internal data class DesktopNucleusEffectActions(
    val onOpenCommandPalette: () -> Unit,
    val onOpenSettings: () -> Unit,
    /** Summon the floating quick-query bar (global hotkey), without raising the main window. */
    val onQuickQuery: () -> Unit,
)

internal data class DesktopNucleusRuntimeState(
    val thinkingConversationId: String?,
    val isStreamingReply: Boolean,
    val selectedConversationId: String?,
    val agentName: String,
    val errorMessage: String?,
    val workProgress: Double? = null,
)

internal fun desktopNucleusEffectState(
    runtime: DesktopNucleusRuntimeState,
): DesktopNucleusEffectState = DesktopNucleusEffectState(
    isAgentWorking = runtime.thinkingConversationId != null || runtime.isStreamingReply,
    agentName = runtime.agentName,
    errorMessage = runtime.errorMessage,
    // A streaming reply without a thinking marker belongs to the selected
    // conversation (streaming presence is selected-conversation scoped).
    workingConversationId = runtime.thinkingConversationId
        ?: runtime.selectedConversationId.takeIf { runtime.isStreamingReply },
    workProgress = runtime.workProgress,
)

private data class AgentCompletionBindings(
    val window: Window,
    val controller: DesktopNucleusController,
    val onActivate: () -> Unit,
    val replyPreviewFor: (String) -> String?,
    val onOpenConversation: (String) -> Unit,
    val onReplyToConversation: (String, String) -> Unit,
)

private data class AgentFailureBindings(
    val window: Window,
    val controller: DesktopNucleusController,
    val onActivate: () -> Unit,
)

private data class DesktopTrayActions(
    val onShow: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onCheckForUpdates: () -> Unit,
)

internal fun destinationNucleusActions(
    controller: DesktopNucleusController,
    window: Window,
): DestinationNucleusActions = DestinationNucleusActions(
    onCheckForUpdates = controller::checkForUpdates,
    onDownloadUpdate = controller::downloadUpdate,
    onInstallUpdate = controller::installUpdateAndRestart,
    onRefreshSystemInfo = controller::refreshSystemInfo,
    onAutoLaunchChanged = controller::setAutoLaunch,
    onOpenAutoLaunchSettings = controller::openAutoLaunchSettings,
    onTestNotification = {
        controller.sendTestNotification { activateDesktopWindow(window) }
    },
)

@Composable
internal fun DesktopNucleusEffects(
    bindings: DesktopNucleusEffectBindings,
    state: DesktopNucleusEffectState,
    actions: DesktopNucleusEffectActions,
) {
    val applicationScope = bindings.applicationScope
    val window = bindings.window
    val activate = remember(window) { { activateDesktopWindow(window) } }

    DesktopTray(
        applicationScope = applicationScope,
        isAgentWorking = state.isAgentWorking,
        actions = DesktopTrayActions(
            onShow = activate,
            onOpenSettings = {
                activate()
                actions.onOpenSettings()
            },
            onCheckForUpdates = bindings.controller::checkForUpdates,
        ),
    )

    DesktopIntegrationLifecycleEffect(
        window = window,
        agentName = state.agentName,
        onActivate = activate,
        actions = actions,
    )
    AgentWorkEffect(window, state)
    AgentCompletionEffect(
        bindings = AgentCompletionBindings(
            window = window,
            controller = bindings.controller,
            onActivate = activate,
            replyPreviewFor = bindings.replyPreviewFor,
            onOpenConversation = bindings.onOpenConversation,
            onReplyToConversation = bindings.onReplyToConversation,
        ),
        state = state,
    )
    AgentFailureEffect(
        bindings = AgentFailureBindings(window, bindings.controller, activate),
        state = state,
    )
}

@Composable
private fun DesktopIntegrationLifecycleEffect(
    window: Window,
    agentName: String,
    onActivate: () -> Unit,
    actions: DesktopNucleusEffectActions,
) {
    DisposableEffect(window) {
        GlobalHotKeyManager.initialize()
        val hotKey = registerQuickSwitcher(actions.onQuickQuery)
        configureLauncherMenus(onShow = onActivate, onOpenSettings = actions.onOpenSettings)
        configureMediaControls(agentName = agentName, onActivate = onActivate)
        val focusListener = desktopFocusListener(window)
        window.addWindowFocusListener(focusListener)

        onDispose {
            disposeDesktopIntegrations(window, focusListener, hotKey)
        }
    }
}

@Composable
private fun AgentWorkEffect(window: Window, state: DesktopNucleusEffectState) {
    LaunchedEffect(state.isAgentWorking, state.agentName, state.workProgress) {
        if (state.isAgentWorking) {
            // Countable subagent steps → precise taskbar progress; otherwise
            // the indeterminate pulse.
            val progress = state.workProgress
            if (progress != null) {
                TaskbarProgress.showProgress(window, progress)
            } else {
                TaskbarProgress.showIndeterminate(window)
            }
            EnergyManager.disableLightEfficiencyMode()
            EnergyManager.keepScreenAwake()
            MediaControlService.setMetadata(
                MediaMetadata(title = "${state.agentName} is working", artist = "Letta Desktop"),
            )
            MediaControlService.setPlaybackState(MediaPlaybackState(MediaPlaybackStatus.PLAYING))
        } else {
            TaskbarProgress.hideProgress(window)
            EnergyManager.releaseScreenAwake()
            EnergyManager.enableLightEfficiencyMode()
            MediaControlService.setMetadata(
                MediaMetadata(title = state.agentName, artist = "Letta Desktop"),
            )
            MediaControlService.setPlaybackState(MediaPlaybackState(MediaPlaybackStatus.PAUSED))
        }
    }
}

@Composable
private fun AgentCompletionEffect(
    bindings: AgentCompletionBindings,
    state: DesktopNucleusEffectState,
) {
    var wasWorking by remember { mutableStateOf(false) }
    // The working conversation clears from state before the completion
    // transition is observed, so remember the last one seen while working.
    var lastWorkingConversationId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.isAgentWorking, state.workingConversationId) {
        state.workingConversationId?.let { lastWorkingConversationId = it }
        if (shouldNotifyCompletion(wasWorking, state, bindings.window)) {
            val conversationId = lastWorkingConversationId
            bindings.controller.notifyAgentFinished(
                agentName = state.agentName,
                replyPreview = conversationId?.let(bindings.replyPreviewFor),
                onReply = { typedText ->
                    if (typedText != null && conversationId != null) {
                        // Inline toast reply: send in the background without
                        // stealing focus, like Google Messages.
                        bindings.onReplyToConversation(conversationId, typedText)
                    } else {
                        bindings.onActivate()
                        conversationId?.let(bindings.onOpenConversation)
                    }
                },
            )
            TaskbarProgress.requestAttention(bindings.window)
            setWindowsCompletionBadge()
        }
        wasWorking = state.isAgentWorking
    }
}

/**
 * Body text for the completion toast: the last substantive assistant reply,
 * whitespace-collapsed and truncated to toast size.
 */
internal fun notificationReplyPreview(messages: List<UiMessage>?): String? =
    messages
        ?.lastOrNull { it.role == "assistant" && !it.isReasoning && !it.isError && !it.isPending && it.content.isNotBlank() }
        ?.content
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.let { if (it.length > NOTIFICATION_PREVIEW_MAX_CHARS) it.take(NOTIFICATION_PREVIEW_MAX_CHARS - 1) + "…" else it }

private const val NOTIFICATION_PREVIEW_MAX_CHARS = 180

private fun shouldNotifyCompletion(wasWorking: Boolean, state: DesktopNucleusEffectState, window: Window): Boolean {
    if (!wasWorking) return false
    if (state.isAgentWorking) return false
    return !window.isFocused
}

private fun setWindowsCompletionBadge() {
    if (Platform.Current != Platform.Windows) return
    if (!WindowsBadgeManager.isAvailable) return
    WindowsBadgeManager.setCount(1)
}

@Composable
private fun AgentFailureEffect(
    bindings: AgentFailureBindings,
    state: DesktopNucleusEffectState,
) {
    var previousError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.errorMessage) {
        when (failureProgressAction(state, previousError, bindings.window)) {
            FailureProgressAction.Notify -> {
                bindings.controller.notifyFailure(state.errorMessage.orEmpty(), bindings.onActivate)
                TaskbarProgress.showError(bindings.window)
                TaskbarProgress.requestAttention(bindings.window, TaskbarProgress.AttentionType.CRITICAL)
            }
            FailureProgressAction.ClearProgress -> TaskbarProgress.hideProgress(bindings.window)
            FailureProgressAction.None -> Unit
        }
        previousError = state.errorMessage
    }
}

private enum class FailureProgressAction { Notify, ClearProgress, None }

/**
 * Notify on a fresh background error; clear the red error progress once the
 * error state resolves (the focus listener only clears attention/badge, and
 * active agent work owns its own progress indicator).
 */
private fun failureProgressAction(
    state: DesktopNucleusEffectState,
    previousError: String?,
    window: Window,
): FailureProgressAction {
    val current = state.errorMessage
    if (current != null) {
        if (current == previousError) return FailureProgressAction.None
        return if (window.isFocused) FailureProgressAction.None else FailureProgressAction.Notify
    }
    if (previousError == null) return FailureProgressAction.None
    return if (state.isAgentWorking) FailureProgressAction.None else FailureProgressAction.ClearProgress
}

private fun registerQuickSwitcher(onQuickQuery: () -> Unit): Long =
    GlobalHotKeyManager.register(
        keyCode = KeyEvent.VK_SPACE,
        modifiers = HotKeyModifier.CONTROL + HotKeyModifier.SHIFT,
        description = "Open Letta quick query",
    ) { _, _ ->
        // Deliberately does NOT activate the main window: the quick-query bar
        // floats over the user's current context (which it also captures —
        // the foreground app must still be frontmost at this point).
        SwingUtilities.invokeLater(onQuickQuery)
    }

private fun desktopFocusListener(window: Window): WindowFocusListener = object : WindowFocusListener {
    override fun windowGainedFocus(event: WindowEvent?) {
        TaskbarProgress.stopAttention(window)
        if (Platform.Current == Platform.Windows && WindowsBadgeManager.isAvailable) {
            WindowsBadgeManager.clear()
        }
    }

    override fun windowLostFocus(event: WindowEvent?) = Unit
}

private fun disposeDesktopIntegrations(window: Window, focusListener: WindowFocusListener, hotKey: Long) {
    window.removeWindowFocusListener(focusListener)
    hotKey.takeIf { it >= 0 }?.let(GlobalHotKeyManager::unregister)
    GlobalHotKeyManager.shutdown()
    MediaControlService.detach()
    when (Platform.Current) {
        Platform.MacOS -> {
            MacOsDockMenu.clearDockMenu()
            MacOsDockMenu.listener = null
        }
        Platform.Windows -> WindowsBadgeManager.uninitialize()
        else -> Unit
    }
    TaskbarProgress.hideProgress(window)
    EnergyManager.releaseScreenAwake()
    EnergyManager.disableLightEfficiencyMode()
}

@Composable
private fun DesktopTray(
    applicationScope: NucleusApplicationScope,
    isAgentWorking: Boolean,
    actions: DesktopTrayActions,
) {
    applicationScope.Tray(
        icon = Icons.Outlined.SmartToy,
        tooltip = if (isAgentWorking) "Letta Desktop — agent working" else "Letta Desktop",
        primaryAction = actions.onShow,
    ) {
        Item(label = "Show Letta Desktop", onClick = actions.onShow)
        Item(label = "Settings", onClick = actions.onOpenSettings)
        Item(label = "Check for updates", onClick = actions.onCheckForUpdates)
        Divider()
        Item(label = "Quit", onClick = { exitProcess(0) })
    }
}

private fun configureMediaControls(agentName: String, onActivate: () -> Unit) {
    if (!MediaControlService.isAvailable()) return
    MediaControlService.configure(displayName = "Letta Desktop")
    MediaControlService.setMetadata(MediaMetadata(title = agentName, artist = "Letta Desktop"))
    MediaControlService.attach { event ->
        when (event) {
            MediaControlEvent.Play,
            MediaControlEvent.Toggle,
            MediaControlEvent.Raise,
            -> onActivate()
            else -> Unit
        }
    }
}

private fun configureLauncherMenus(onShow: () -> Unit, onOpenSettings: () -> Unit) {
    when (Platform.Current) {
        Platform.Windows -> {
            WindowsJumpListManager.setJumpList(
                tasks = listOf(
                    JumpListItem(
                        title = "Open conversations",
                        arguments = "meridian://conversations",
                        description = "Show Letta Desktop conversations",
                    ),
                    JumpListItem(
                        title = "Open settings",
                        arguments = "meridian://settings",
                        description = "Show Letta Desktop settings",
                    ),
                ),
            )
            WindowsBadgeManager.initialize(LETTA_WINDOWS_AUMID)
        }
        Platform.MacOS -> {
            MacOsDockMenu.listener = { itemId ->
                when (itemId) {
                    1 -> onShow()
                    2 -> {
                        // Match the tray Settings action: bring the (possibly
                        // hidden) window forward before switching destination.
                        onShow()
                        onOpenSettings()
                    }
                }
            }
            MacOsDockMenu.setDockMenu(
                listOf(
                    DockMenuItem(id = 1, title = "Show Letta Desktop"),
                    DockMenuItem(id = 2, title = "Settings"),
                ),
            )
        }
        else -> Unit
    }
}
