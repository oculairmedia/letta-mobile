package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalUriHandler
import com.letta.mobile.data.runtime.PermissionModeSettings
import com.letta.mobile.data.settings.BackendConnectionTester
import com.letta.mobile.data.settings.ChatSettings
import com.letta.mobile.data.settings.NotificationSettings
import com.letta.mobile.data.settings.SettingValue
import com.letta.mobile.data.settings.SettingsContribution
import com.letta.mobile.data.settings.SettingsGroup
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.desktop.chat.runDesktopConnectionTest
import com.letta.mobile.desktop.chat.toSettingsStatus
import com.letta.mobile.desktop.data.desktopConfigIdFor
import com.letta.mobile.ui.notify.LocalAttentionPrefs
import com.letta.mobile.ui.settings.BackendSettingsActions
import com.letta.mobile.ui.settings.SettingsPageServices
import com.letta.mobile.ui.settings.SettingsPageState
import com.letta.mobile.ui.shell.ShellPageContent
import com.letta.mobile.ui.shell.shellSettingsPage
import com.letta.mobile.ui.shell.pages.plugins.rememberPluginsContributions
import dev.nucleusframework.darkmodedetector.isSystemInDarkMode
import dev.nucleusframework.systemcolor.systemAccentColor
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.compose.rememberDirectoryPickerLauncher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * Container (letta-mobile-ckmub.3.12, hard cut S10): the desktop's Settings destination is the shared
 * SettingsPage. This only binds the desktop's services to it: the backend store, the bundled runtime
 * controllers, the directory pickers, Nucleus (updates, start at login, notifications, telemetry)
 * and the chat/permission/notification preferences. Every row is drawn by sharedUI.
 */
internal fun desktopSettingsPage(
    inputs: DesktopSettingsInputs,
    actions: DesktopSettingsActions,
): ShellPageContent<ShellDestination.Settings> = ShellPageContent { destination, modifier ->
    DesktopSettingsPage(destination, inputs, actions, modifier)
}

@Composable
private fun DesktopSettingsPage(
    destination: ShellDestination.Settings,
    inputs: DesktopSettingsInputs,
    actions: DesktopSettingsActions,
    modifier: Modifier,
) {
    val services = SettingsPageServices(
        backend = desktopBackendActions(actions),
        pairing = inputs.pairing,
        chat = rememberDesktopChatSettings(),
        notifications = NotificationSettings(LocalAttentionPrefs.current),
        onOpenProviders = actions.onOpenProviders,
        contributions = desktopContributions(inputs, actions),
    )
    shellSettingsPage(SettingsPageState(inputs.state.config), services).Content(destination, modifier)
}

/** Saves under the desktop's per-address config id, as the desktop store expects. */
private fun desktopBackendActions(actions: DesktopSettingsActions) = BackendSettingsActions(
    onConfigSaved = { config -> actions.onConfigSaved(config.copy(id = desktopConfigIdFor(config.serverUrl))) },
    onTokenCleared = actions.onTokenCleared,
    onIrohIdentityReset = actions.onIrohIdentityReset,
    testConnection = BackendConnectionTester { url, token -> runDesktopConnectionTest(url, token).toSettingsStatus() },
)

@Composable
private fun rememberDesktopChatSettings(): ChatSettings {
    // The shared chat page is the desktop's only chat (S11): no shared-page switch here.
    val openOnCanvas = LocalDesktopOpenChatsOnCanvas.current
    val scope = rememberCoroutineScope()
    val canvasFirst = remember(openOnCanvas) { SettingValue(openOnCanvas.enabled) { on -> scope.launch { openOnCanvas.setEnabled(on) } } }
    return ChatSettings(openChatsOnCanvas = canvasFirst, permissionMode = LocalDesktopPermissionModeSettings.current)
}

@Composable
private fun desktopContributions(inputs: DesktopSettingsInputs, actions: DesktopSettingsActions): List<SettingsContribution> {
    val backend = rememberLocalRuntimeContributions(inputs, actions)
    LiveSystemInfo(actions.nucleus.onRefreshSystemInfo)
    val nucleus = inputs.nucleus
    val about = desktopAboutSections(nucleus, actions.nucleus, inputs.state.featureReadiness)
    return backend + listOf(
        SettingsContribution(SettingsGroup.Appearance, railSection(inputs.railRecencyDays, actions.onRailRecencyDaysChange)),
        SettingsContribution(SettingsGroup.Appearance, rememberSystemAppearance()),
        SettingsContribution(SettingsGroup.Notifications, desktopNotificationsSection(nucleus, actions.nucleus.onTestNotification)),
    ) + about.map { SettingsContribution(SettingsGroup.About, it) } + rememberPluginsContributions(actions.pluginViews, actions.onOpenPlugins)
}

/** The bundled runtime's sections, with the two folder pickers they open. */
@Composable
private fun rememberLocalRuntimeContributions(inputs: DesktopSettingsInputs, actions: DesktopSettingsActions): List<SettingsContribution> {
    val runtime = inputs.localRuntime
    LaunchedEffect(runtime) { runtime.load() }
    LaunchedEffect(Unit) {
        actions.localRuntimeProvider.onLoad()
        actions.localBackendDirectory.onLoad()
    }
    val health by runtime.health.collectAsState()
    val launchState by runtime.launch.collectAsState()
    val git by runtime.git.collectAsState()
    val gitInstall by runtime.gitInstall.collectAsState()
    val uriHandler = LocalUriHandler.current
    var pendingDirectory by remember { mutableStateOf<String?>(null) }
    val workingDirectoryPicker = rememberDirectoryPickerLauncher(
        dialogSettings = FileKitDialogSettings(title = "Choose the runtime's default working directory"),
    ) { directory -> directory?.let { runtime.changeWorkingDirectory(it.file.absolutePath) } }
    val backendDirectoryPicker = rememberDirectoryPickerLauncher(
        dialogSettings = FileKitDialogSettings(title = "Choose local backend data directory"),
    ) { directory -> directory?.let { pendingDirectory = it.file.absolutePath } }
    val runtimeActions = LocalRuntimeSectionActions(
        setRunning = runtime::setRunning,
        forceRestart = runtime::forceRestart,
        git = LocalRuntimeGitActions(runtime::recheckGit, runtime::installGit, uriHandler::openUri),
        directory = LocalRuntimeDirectoryActions({ workingDirectoryPicker.launch() }, runtime::resetWorkingDirectory),
    )
    val directoryActions = LocalBackendDirectorySectionActions(
        pick = { backendDirectoryPicker.launch() },
        confirm = { path ->
            pendingDirectory = null
            actions.localBackendDirectory.onChangeDirectory(path)
        },
        dismiss = { pendingDirectory = null },
        reset = actions.localBackendDirectory.onResetToDefault,
    )
    return listOf(
        localRuntimeSection(LocalRuntimeSectionState(health, git, launchState, gitInstall), runtimeActions),
        localRuntimeProviderSection(inputs.localRuntimeProvider, actions.localRuntimeProvider),
        localBackendDirectorySection(inputs.localBackendDirectory, pendingDirectory, directoryActions),
    ).map { SettingsContribution(SettingsGroup.Backend, it) }
}

/**
 * Live telemetry while Settings is on screen: the loop ends when the page leaves, so there is no
 * background sampling cost. The callback is read through rememberUpdatedState so the long-lived loop
 * never pins the first composition's lambda.
 */
@Composable
private fun LiveSystemInfo(onRefresh: () -> Unit) {
    val currentRefresh by rememberUpdatedState(onRefresh)
    LaunchedEffect(Unit) {
        while (true) {
            delay(SYSTEM_INFO_REFRESH_INTERVAL_MS.milliseconds)
            currentRefresh()
        }
    }
}

/** The OS theme, accent and contrast, read reactively from Nucleus (no polling). */
@Composable
private fun rememberSystemAppearance() = systemAppearanceSection(
    dark = isSystemInDarkMode(),
    // sRGB toArgb() packs ARGB; the low 24 bits are the RGB channels.
    accentHex = systemAccentColor()?.let { "#%06X".format(it.toArgb() and RGB_MASK) },
)

/** The persisted default permission mode (letta-mobile-bzvro.13); Settings leaves its row out where none is provided. */
internal val LocalDesktopPermissionModeSettings = compositionLocalOf<PermissionModeSettings?> { null }

/** Telemetry sampling cadence while the Settings page is visible. */
private const val SYSTEM_INFO_REFRESH_INTERVAL_MS = 3_000L
private const val RGB_MASK = 0xFFFFFF
