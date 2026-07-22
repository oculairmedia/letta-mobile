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

@Composable
internal fun DesktopNucleusEffects(
    applicationScope: NucleusApplicationScope,
    window: Window,
    controller: DesktopNucleusController,
    isAgentWorking: Boolean,
    agentName: String,
    errorMessage: String?,
    onOpenCommandPalette: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val activate = remember(window) { { activateDesktopWindow(window) } }

    DesktopTray(
        applicationScope = applicationScope,
        isAgentWorking = isAgentWorking,
        onShow = activate,
        onOpenSettings = {
            activate()
            onOpenSettings()
        },
        onCheckForUpdates = controller::checkForUpdates,
    )

    DisposableEffect(window) {
        GlobalHotKeyManager.initialize()
        val hotKey = GlobalHotKeyManager.register(
            keyCode = KeyEvent.VK_SPACE,
            modifiers = HotKeyModifier.CONTROL + HotKeyModifier.SHIFT,
            description = "Open Letta quick switcher",
        ) { _, _ ->
            SwingUtilities.invokeLater {
                activate()
                onOpenCommandPalette()
            }
        }

        configureLauncherMenus(onShow = activate, onOpenSettings = onOpenSettings)
        configureMediaControls(agentName = agentName, onActivate = activate)
        val focusListener = object : WindowFocusListener {
            override fun windowGainedFocus(event: WindowEvent?) {
                TaskbarProgress.stopAttention(window)
                if (Platform.Current == Platform.Windows && WindowsBadgeManager.isAvailable) {
                    WindowsBadgeManager.clear()
                }
            }

            override fun windowLostFocus(event: WindowEvent?) = Unit
        }
        window.addWindowFocusListener(focusListener)

        onDispose {
            window.removeWindowFocusListener(focusListener)
            if (hotKey >= 0) GlobalHotKeyManager.unregister(hotKey)
            GlobalHotKeyManager.shutdown()
            MediaControlService.detach()
            if (Platform.Current == Platform.MacOS) {
                MacOsDockMenu.clearDockMenu()
                MacOsDockMenu.listener = null
            }
            if (Platform.Current == Platform.Windows) WindowsBadgeManager.uninitialize()
            TaskbarProgress.hideProgress(window)
            EnergyManager.releaseScreenAwake()
            EnergyManager.disableLightEfficiencyMode()
        }
    }

    LaunchedEffect(isAgentWorking, agentName) {
        if (isAgentWorking) {
            TaskbarProgress.showIndeterminate(window)
            EnergyManager.disableLightEfficiencyMode()
            EnergyManager.keepScreenAwake()
            MediaControlService.setMetadata(
                MediaMetadata(title = "$agentName is working", artist = "Letta Desktop"),
            )
            MediaControlService.setPlaybackState(MediaPlaybackState(MediaPlaybackStatus.PLAYING))
        } else {
            TaskbarProgress.hideProgress(window)
            EnergyManager.releaseScreenAwake()
            EnergyManager.enableLightEfficiencyMode()
            MediaControlService.setMetadata(
                MediaMetadata(title = agentName, artist = "Letta Desktop"),
            )
            MediaControlService.setPlaybackState(MediaPlaybackState(MediaPlaybackStatus.PAUSED))
        }
    }

    var wasWorking by remember { mutableStateOf(false) }
    LaunchedEffect(isAgentWorking) {
        if (wasWorking && !isAgentWorking && !window.isFocused) {
            controller.notifyAgentFinished(agentName, activate)
            TaskbarProgress.requestAttention(window)
            if (Platform.Current == Platform.Windows && WindowsBadgeManager.isAvailable) {
                WindowsBadgeManager.setCount(1)
            }
        }
        wasWorking = isAgentWorking
    }

    var previousError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(errorMessage) {
        val current = errorMessage
        if (current != null && current != previousError && !window.isFocused) {
            controller.notifyFailure(current, activate)
            TaskbarProgress.showError(window)
            TaskbarProgress.requestAttention(window, TaskbarProgress.AttentionType.CRITICAL)
        }
        previousError = current
    }
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
