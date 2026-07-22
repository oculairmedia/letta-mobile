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
import java.awt.Window
import java.awt.event.KeyEvent
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

internal fun activateDesktopWindow(window: Window) {
    SwingUtilities.invokeLater {
        window.isVisible = true
        window.toFront()
        window.requestFocus()
    }
}

internal data class DesktopNucleusEffectBindings(
    val applicationScope: NucleusApplicationScope,
    val window: Window,
    val controller: DesktopNucleusController,
)

internal data class DesktopNucleusEffectState(
    val isAgentWorking: Boolean,
    val agentName: String,
    val errorMessage: String?,
)

internal data class DesktopNucleusEffectActions(
    val onOpenCommandPalette: () -> Unit,
    val onOpenSettings: () -> Unit,
)

internal fun desktopNucleusEffectState(
    thinkingConversationId: String?,
    isStreamingReply: Boolean,
    agentName: String,
    errorMessage: String?,
): DesktopNucleusEffectState = DesktopNucleusEffectState(
    isAgentWorking = hasActiveAgentWork(thinkingConversationId, isStreamingReply),
    agentName = agentName,
    errorMessage = errorMessage,
)

private data class AgentCompletionBindings(
    val window: Window,
    val controller: DesktopNucleusController,
    val onActivate: () -> Unit,
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
        onShow = activate,
        onOpenSettings = {
            activate()
            actions.onOpenSettings()
        },
        onCheckForUpdates = bindings.controller::checkForUpdates,
    )

    DesktopIntegrationLifecycleEffect(
        window = window,
        agentName = state.agentName,
        onActivate = activate,
        actions = actions,
    )
    AgentWorkEffect(window, state)
    AgentCompletionEffect(
        bindings = AgentCompletionBindings(window, bindings.controller, activate),
        state = state,
    )
    AgentFailureEffect(window, bindings.controller, state.errorMessage, activate)
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
        val hotKey = registerQuickSwitcher(onActivate, actions.onOpenCommandPalette)
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
    LaunchedEffect(state.isAgentWorking, state.agentName) {
        if (state.isAgentWorking) {
            TaskbarProgress.showIndeterminate(window)
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
    LaunchedEffect(state.isAgentWorking) {
        if (shouldNotifyCompletion(wasWorking, state.isAgentWorking, bindings.window.isFocused)) {
            bindings.controller.notifyAgentFinished(state.agentName, bindings.onActivate)
            TaskbarProgress.requestAttention(bindings.window)
            setWindowsCompletionBadge()
        }
        wasWorking = state.isAgentWorking
    }
}

private fun hasActiveAgentWork(thinkingConversationId: String?, isStreamingReply: Boolean): Boolean {
    if (thinkingConversationId != null) return true
    return isStreamingReply
}

private fun shouldNotifyCompletion(wasWorking: Boolean, isWorking: Boolean, isWindowFocused: Boolean): Boolean {
    if (!wasWorking) return false
    if (isWorking) return false
    return !isWindowFocused
}

private fun setWindowsCompletionBadge() {
    if (Platform.Current != Platform.Windows) return
    if (!WindowsBadgeManager.isAvailable) return
    WindowsBadgeManager.setCount(1)
}

@Composable
private fun AgentFailureEffect(
    window: Window,
    controller: DesktopNucleusController,
    errorMessage: String?,
    onActivate: () -> Unit,
) {
    var previousError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(errorMessage) {
        val current = errorMessage
        if (current != null && current != previousError && !window.isFocused) {
            controller.notifyFailure(current, onActivate)
            TaskbarProgress.showError(window)
            TaskbarProgress.requestAttention(window, TaskbarProgress.AttentionType.CRITICAL)
        }
        previousError = current
    }
}

private fun registerQuickSwitcher(onActivate: () -> Unit, onOpenCommandPalette: () -> Unit): Long =
    GlobalHotKeyManager.register(
        keyCode = KeyEvent.VK_SPACE,
        modifiers = HotKeyModifier.CONTROL + HotKeyModifier.SHIFT,
        description = "Open Letta quick switcher",
    ) { _, _ ->
        SwingUtilities.invokeLater {
            onActivate()
            onOpenCommandPalette()
        }
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
    onShow: () -> Unit,
    onOpenSettings: () -> Unit,
    onCheckForUpdates: () -> Unit,
) {
    applicationScope.Tray(
        icon = Icons.Outlined.SmartToy,
        tooltip = if (isAgentWorking) "Letta Desktop — agent working" else "Letta Desktop",
        primaryAction = onShow,
    ) {
        Item(label = "Show Letta Desktop", onClick = onShow)
        Item(label = "Settings", onClick = onOpenSettings)
        Item(label = "Check for updates", onClick = onCheckForUpdates)
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
                    2 -> onOpenSettings()
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
