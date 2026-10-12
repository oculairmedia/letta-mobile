package com.letta.mobile.desktop

import com.letta.mobile.data.runtime.LocalRuntimeProviderConfig
import com.letta.mobile.data.runtime.LocalRuntimeProviderStatus
import com.letta.mobile.data.runtime.supervisor.RuntimeHealth
import com.letta.mobile.data.settings.SettingsAction
import com.letta.mobile.data.settings.SettingsConfirm
import com.letta.mobile.data.settings.SettingsOption
import com.letta.mobile.data.settings.SettingsRow
import com.letta.mobile.data.settings.SettingsSectionModel
import com.letta.mobile.data.settings.SettingsStatus
import com.letta.mobile.data.settings.SettingsTone
import com.letta.mobile.desktop.runtime.DesktopGitStatus
import dev.nucleusframework.autolaunch.AutoLaunchState
import java.io.File

/*
 * letta-mobile-ckmub.3.12 (hard cut S10): the desktop's own Settings sections as data. The shared
 * SettingsPage draws them with its SettingsSection rows; nothing here draws. Replaces the desktop
 * settings cards (DesktopLocalRuntimeSettingsCard, DesktopLocalRuntimeProviderSettings,
 * DesktopLocalBackendDirectorySettings, DesktopNucleusSettings, DesktopRailSettingsCard,
 * DesktopPermissionModeSettingsCard and the Overview cards of DesktopDestinationSettings).
 */

/**
 * The "Local runtime provider" section's state: the OpenAI-compatible endpoint (vLLM, llama.cpp,
 * Ollama, ...) the BUNDLED letta-code runtime uses as its model provider (see
 * `LocalRuntimeProviderAuthFile.kt` in sharedLogic for how it is stored).
 */
internal data class DesktopLocalRuntimeProviderState(
    val status: LocalRuntimeProviderStatus?,
    val isSaving: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
)

internal data class DesktopLocalRuntimeProviderActions(
    val onLoad: () -> Unit,
    val onSave: (baseUrl: String, apiKey: String?) -> Unit,
)

/** What the bundled-runtime section can ask for; the pickers and the browser belong to the container. */
internal class LocalRuntimeSectionActions(
    val setRunning: (Boolean) -> Unit,
    val forceRestart: () -> Unit,
    val recheckGit: () -> Unit,
    val openUri: (String) -> Unit,
    val pickWorkingDirectory: () -> Unit,
    val resetWorkingDirectory: () -> Unit,
)

/** The bundled letta-code runtime: its health with start/stop/restart (F03), Git (G07) and its launch settings (F05). */
internal fun localRuntimeSection(
    health: RuntimeHealth,
    git: DesktopGitStatus,
    launch: DesktopRuntimeLaunchState,
    actions: LocalRuntimeSectionActions,
): SettingsSectionModel = SettingsSectionModel(
    key = "desktop-local-runtime",
    title = "Local runtime",
    rows = buildList {
        add(SettingsRow.Info("runtime-health", "Health", if (health.active) "Running" else "Not running", healthTone(health)))
        add(SettingsRow.Note("runtime-health-detail", SettingsStatus(health.describe(), healthTone(health))))
        if (health.recentStderr.isNotEmpty()) add(SettingsRow.Code("runtime-stderr", health.recentStderr.joinToString("\n")))
        add(SettingsRow.Actions("runtime-run", runActions(health, actions)))
        addAll(gitRows(git, actions))
        addAll(launchRows(launch, actions))
    },
)

private fun healthTone(health: RuntimeHealth): SettingsTone =
    if (health.gaveUp || health.restartPending) SettingsTone.Error else SettingsTone.Normal

private fun runActions(health: RuntimeHealth, actions: LocalRuntimeSectionActions): List<SettingsAction> =
    if (health.stoppedByUser) {
        listOf(SettingsAction("runtime-start", "Start runtime", primary = true) { actions.setRunning(true) })
    } else {
        listOf(
            SettingsAction("runtime-stop", "Stop runtime") { actions.setRunning(false) },
            SettingsAction("runtime-restart", "Force restart") { actions.forceRestart() },
        )
    }

private fun gitRows(git: DesktopGitStatus, actions: LocalRuntimeSectionActions): List<SettingsRow> {
    val missing = git as? DesktopGitStatus.Missing
    val line = SettingsRow.Note("runtime-git", SettingsStatus("Git: ${git.describe()}", if (missing != null) SettingsTone.Error else SettingsTone.Normal))
    if (missing == null) return listOf(line)
    return listOf(
        line,
        SettingsRow.Actions(
            "runtime-git-actions",
            listOf(
                SettingsAction("runtime-git-get", "Get Git", primary = true) { actions.openUri(missing.platform.installUrl) },
                SettingsAction("runtime-git-recheck", "Check again") { actions.recheckGit() },
            ),
        ),
    )
}

private fun launchRows(launch: DesktopRuntimeLaunchState, actions: LocalRuntimeSectionActions): List<SettingsRow> = buildList {
    add(SettingsRow.Info("runtime-working-directory", "Default working directory", launch.effectiveWorkingDirectory.ifEmpty { "…" }))
    launch.message?.let { add(SettingsRow.Note("runtime-launch-message", SettingsStatus(it, if (launch.isError) SettingsTone.Error else SettingsTone.Positive))) }
    add(
        SettingsRow.Actions(
            "runtime-directory-actions",
            buildList {
                add(SettingsAction("runtime-directory-change", "Change…") { actions.pickWorkingDirectory() })
                if (launch.workingDirectory != null) add(SettingsAction("runtime-directory-reset", "Use Documents") { actions.resetWorkingDirectory() })
            },
        ),
    )
    add(
        SettingsRow.Note(
            "runtime-launch-facts",
            SettingsStatus("Node heap ${launch.heapMb / MB_PER_GB} GB (half of this machine's memory, at least 4 GB) · instance ${launch.instanceId.take(INSTANCE_ID_PREVIEW)}"),
        ),
    )
}

/** The OpenAI-compatible endpoint the bundled runtime uses; it writes the runtime's own credential file. */
internal fun localRuntimeProviderSection(
    state: DesktopLocalRuntimeProviderState,
    actions: DesktopLocalRuntimeProviderActions,
): SettingsSectionModel = SettingsSectionModel(
    key = "desktop-local-provider",
    title = "Local runtime provider",
    supporting = "Point the bundled Letta Code runtime at an OpenAI-compatible endpoint (vLLM, llama.cpp, Ollama, " +
        "LM Studio or similar). This writes the runtime's own credential file, so a terminal \"letta setup\" sees the same configuration.",
    rows = listOf(
        SettingsRow.Note("provider-status", providerStatus(state)),
        SettingsRow.Text(
            key = PROVIDER_BASE_URL,
            title = "Base URL",
            value = state.status?.baseUrl.orEmpty(),
            placeholder = "http://localhost:11434/v1",
            problem = { url -> if (url.isBlank() || LocalRuntimeProviderConfig.isValidBaseUrl(url)) null else "Enter an http:// or https:// URL." },
        ),
        SettingsRow.Text(
            key = PROVIDER_API_KEY,
            title = "API key",
            placeholder = if (state.status?.hasApiKey == true) "Optional — saved key hidden" else "Optional",
            secret = true,
        ),
        SettingsRow.Actions(
            "provider-actions",
            listOf(
                SettingsAction(
                    key = "provider-save",
                    label = if (state.isSaving) "Saving…" else "Save",
                    primary = true,
                    enabled = !state.isSaving,
                    enabledFor = { drafts -> LocalRuntimeProviderConfig.isValidBaseUrl(drafts[PROVIDER_BASE_URL]) },
                ) { drafts -> actions.onSave(drafts[PROVIDER_BASE_URL].trim(), drafts.trimmedOrNull(PROVIDER_API_KEY)) },
            ),
        ),
    ),
)

private fun providerStatus(state: DesktopLocalRuntimeProviderState): SettingsStatus {
    state.message?.let { return SettingsStatus(it, if (state.isError) SettingsTone.Error else SettingsTone.Positive) }
    val status = state.status ?: return SettingsStatus("Loading current configuration…")
    if (!status.isConfigured) return SettingsStatus("Not configured — the bundled runtime has no local model provider yet.")
    return SettingsStatus("Configured: ${status.baseUrl}" + if (status.hasApiKey) " (API key set)" else " (no API key)")
}

/**
 * Where the bundled runtime keeps its agents, conversations and provider credentials. Switching it
 * swaps in a whole other set of live data, so a picked folder waits for [pendingPath]'s confirmation.
 */
internal fun localBackendDirectorySection(
    state: DesktopLocalBackendDirectoryState,
    pendingPath: String?,
    actions: LocalBackendDirectorySectionActions,
): SettingsSectionModel = SettingsSectionModel(
    key = "desktop-local-backend-directory",
    title = "Local backend data directory",
    supporting = "Where the bundled runtime keeps its agents, conversations and model provider credentials. It is separate " +
        "from the letta-code CLI's own ~/.letta/lc-local-backend; point it there to see agents you created from a terminal. " +
        "Switching this points the app at a DIFFERENT set of live data, not just a folder rename.",
    rows = listOf(
        SettingsRow.Note("directory-status", directoryStatus(state)),
        SettingsRow.Info("directory-current", "Current directory", state.effectivePath),
        SettingsRow.Actions(
            "directory-actions",
            buildList {
                add(SettingsAction("directory-change", if (state.isSaving) "Saving…" else "Change…", primary = true, enabled = !state.isSaving) { actions.pick() })
                if (!state.isDefault) add(SettingsAction("directory-reset", "Reset to default", enabled = !state.isSaving) { actions.reset() })
            },
        ),
    ),
    confirm = pendingPath?.let { candidate ->
        SettingsConfirm(
            title = "Switch local backend data directory?",
            message = confirmSwitchMessage(candidate),
            confirmLabel = "Use this folder",
            onConfirm = { actions.confirm(candidate) },
            onDismiss = actions.dismiss,
        )
    },
)

/** The data-directory section's actions: pick (then confirm or dismiss) and reset. */
internal class LocalBackendDirectorySectionActions(
    val pick: () -> Unit,
    val confirm: (String) -> Unit,
    val dismiss: () -> Unit,
    val reset: () -> Unit,
)

private fun directoryStatus(state: DesktopLocalBackendDirectoryState): SettingsStatus {
    state.message?.let { return SettingsStatus(it, if (state.isError) SettingsTone.Error else SettingsTone.Positive) }
    return SettingsStatus(if (state.isDefault) "Using the default location." else "Using a custom location.")
}

/**
 * The confirmation for switching to [candidatePath]: whether the folder already holds data (agents
 * will change) or is empty/new (a fresh, empty backend). A top-level entry count only, to stay cheap.
 */
internal fun confirmSwitchMessage(candidatePath: String): String {
    val directory = File(candidatePath)
    val entryCount = runCatching { directory.list()?.size }.getOrNull()
    val contentsNote = when {
        !directory.exists() -> "This folder doesn't exist yet — it will be created empty, and the bundled runtime will start with no agents."
        entryCount == null -> "Could not read this folder's contents."
        entryCount == 0 -> "This folder exists but is currently empty — the bundled runtime will start with no agents."
        else -> "This folder already contains data ($entryCount item" + (if (entryCount == 1) "" else "s") +
            " at its top level) — the app will start showing whatever agents and conversations live there instead of what you see now."
    }
    return "The bundled local runtime will restart against:\n\n$candidatePath\n\n$contentsNote\n\n" +
        "If another Letta process (including the letta-code CLI) is already using this folder, you may see a harmless " +
        "\"could not claim scheduler lease\" notice in the runtime log — only cron scheduling is affected, chat still works."
}

/** The agent rail's recency window (Appearance group). */
internal fun railSection(recencyDays: Int, onChange: (Int) -> Unit): SettingsSectionModel = SettingsSectionModel(
    key = "desktop-rail",
    title = "Agent rail",
    supporting = "Show agents used in the last… Everything else stays one click away in the picker.",
    rows = listOf(
        SettingsRow.Choice(
            key = "rail-recency",
            title = "Agents on the rail",
            options = RAIL_RECENCY_CHOICES.map { (days, label) -> SettingsOption(days.toString(), label) },
            selected = recencyDays.toString(),
            onSelect = { picked -> picked.toIntOrNull()?.let(onChange) },
        ),
    ),
)

private val RAIL_RECENCY_CHOICES: List<Pair<Int, String>> = listOf(
    1 to "Day",
    3 to "3 days",
    7 to "Week",
    14 to "2 weeks",
    30 to "Month",
    0 to "All agents",
)

/** The OS appearance the window follows, as facts (Appearance group). */
internal fun systemAppearanceSection(dark: Boolean, accentHex: String?, highContrast: Boolean): SettingsSectionModel = SettingsSectionModel(
    key = "desktop-system-appearance",
    title = "System appearance",
    supporting = "Letta follows the OS theme, accent and contrast as they change.",
    rows = listOf(
        SettingsRow.Info("system-theme", "Theme", if (dark) "Dark" else "Light"),
        SettingsRow.Info("system-accent", "Accent", accentHex ?: "Letta default"),
        SettingsRow.Info("system-contrast", "High contrast", if (highContrast) "On" else "Off"),
    ),
)

/** Native notifications: whether they work here, and a test (Notifications group). */
internal fun desktopNotificationsSection(state: DesktopNucleusState, onTest: () -> Unit): SettingsSectionModel = SettingsSectionModel(
    key = "desktop-notifications",
    title = "Desktop notifications",
    rows = listOf(
        SettingsRow.Note(
            "notifications-availability",
            SettingsStatus(
                state.notificationMessage ?: if (state.notificationsAvailable) {
                    "Native notifications are available for completed responses, approval requests and failures."
                } else {
                    "The current desktop session could not initialize native notifications."
                },
            ),
        ),
        SettingsRow.Actions(
            "notifications-actions",
            listOf(SettingsAction("notifications-test", "Send test notification", enabled = state.notificationsAvailable) { onTest() }),
        ),
    ),
)

/** Updates, start at login, live system telemetry and startup readiness (Diagnostics & about group). */
internal fun desktopAboutSections(
    state: DesktopNucleusState,
    actions: DestinationNucleusActions,
    readiness: List<DesktopFeatureReadiness>,
): List<SettingsSectionModel> = listOf(
    updatesSection(state, actions),
    autoLaunchSection(state.autoLaunchState, actions),
    systemInfoSection(state.system, actions.onRefreshSystemInfo),
    SettingsSectionModel(
        key = "desktop-readiness",
        title = "Startup readiness",
        supporting = "Ctrl+Shift+Space opens the quick switcher globally. Closing the window keeps Letta running in the system tray.",
        rows = readiness.mapIndexed { index, feature ->
            SettingsRow.Info("readiness-$index", feature.title, feature.state.label, feature.state.tone)
        },
    ),
)

private fun updatesSection(state: DesktopNucleusState, actions: DestinationNucleusActions) = SettingsSectionModel(
    key = "desktop-updates",
    title = "Updates",
    rows = buildList {
        add(
            SettingsRow.Note(
                "updates-status",
                SettingsStatus(state.justUpdatedMessage ?: state.updateMessage ?: "GitHub releases, SHA-512 verification, and safe installer restart."),
            ),
        )
        if (state.updatePhase == DesktopUpdatePhase.Downloading) {
            add(SettingsRow.Progress("updates-progress", state.updateProgressPercent / PERCENT, "${state.updateProgressPercent}%"))
        }
        updateAction(state, actions)?.let { add(SettingsRow.Actions("updates-actions", listOf(it))) }
    },
)

private fun updateAction(state: DesktopNucleusState, actions: DestinationNucleusActions): SettingsAction? = when (state.updatePhase) {
    DesktopUpdatePhase.Available ->
        SettingsAction("updates-download", "Download ${state.updateVersion.orEmpty()}".trim(), primary = true) { actions.onDownloadUpdate() }
    DesktopUpdatePhase.ReadyToInstall -> SettingsAction("updates-install", "Install and restart", primary = true) { actions.onInstallUpdate() }
    DesktopUpdatePhase.Checking, DesktopUpdatePhase.Downloading -> null
    else -> SettingsAction("updates-check", "Check for updates") { actions.onCheckForUpdates() }
}

private fun autoLaunchSection(autoLaunch: AutoLaunchState, actions: DestinationNucleusActions): SettingsSectionModel {
    val enabled = autoLaunch == AutoLaunchState.ENABLED || autoLaunch == AutoLaunchState.ENABLED_BY_POLICY
    val externallyLocked = autoLaunch in setOf(AutoLaunchState.DISABLED_BY_USER, AutoLaunchState.DISABLED_BY_POLICY, AutoLaunchState.ENABLED_BY_POLICY)
    return SettingsSectionModel(
        key = "desktop-auto-launch",
        title = "Start at login",
        rows = buildList {
            add(
                SettingsRow.Toggle(
                    key = "auto-launch",
                    title = "Launch Letta when I sign in",
                    checked = enabled,
                    supporting = "Nucleus backend: ${autoLaunch.name.lowercase().replace('_', ' ')}",
                    enabled = autoLaunch != AutoLaunchState.UNSUPPORTED && !externallyLocked,
                    onChange = actions.onAutoLaunchChanged,
                ),
            )
            if (externallyLocked) {
                add(
                    SettingsRow.Actions(
                        "auto-launch-actions",
                        listOf(SettingsAction("auto-launch-open", "Open system startup settings") { actions.onOpenAutoLaunchSettings() }),
                    ),
                )
            }
        },
    )
}

private fun systemInfoSection(system: DesktopSystemSnapshot, onRefresh: () -> Unit) = SettingsSectionModel(
    key = "desktop-system-info",
    title = "Runtime introspection",
    supporting = if (system.available) "Native host telemetry" else "Native system information unavailable",
    rows = listOf(
        SettingsRow.Info("system-cpu", "CPU", system.cpuUsagePercent?.let { "${it.toInt()}% · ${system.physicalCores ?: "?"} cores" } ?: "—"),
        SettingsRow.Info("system-memory", "Memory", formatMemory(system.memoryUsedBytes, system.memoryTotalBytes)),
        SettingsRow.Info("system-gpu", "GPU", gpuLine(system)),
        SettingsRow.Info("system-cpu-temperature", "CPU temperature", system.cpuTemperatureCelsius?.let { "${it.toInt()} °C" } ?: "—"),
        SettingsRow.Info(
            "system-network",
            "Network",
            "${if (system.connected == true) "Online" else "Offline"} · ↓${formatBytes(system.receivedBytes)} ↑${formatBytes(system.transmittedBytes)}",
        ),
        SettingsRow.Actions("system-actions", listOf(SettingsAction("system-refresh", "Refresh") { onRefresh() })),
    ),
)

private fun gpuLine(system: DesktopSystemSnapshot): String = buildList {
    system.gpuName?.let(::add)
    system.gpuUsagePercent?.let { add("${it.toInt()}%") }
    system.gpuTemperatureCelsius?.let { add("${it.toInt()} °C") }
}.joinToString(" · ").ifBlank { "—" }

private fun formatMemory(used: Long?, total: Long?): String {
    val usedBytes = used ?: return "—"
    val totalBytes = total?.takeIf { it > 0 } ?: return "—"
    return "${formatBytes(usedBytes)} / ${formatBytes(totalBytes)}"
}

private fun formatBytes(bytes: Long): String = com.letta.mobile.util.FormatHelpers.formatByteSize(bytes)

private val DesktopFeatureState.label: String
    get() = when (this) {
        DesktopFeatureState.Ready -> "Ready"
        DesktopFeatureState.InProgress -> "In progress"
        DesktopFeatureState.AndroidOnly -> "Android only"
    }

private val DesktopFeatureState.tone: SettingsTone
    get() = when (this) {
        DesktopFeatureState.Ready -> SettingsTone.Positive
        DesktopFeatureState.InProgress -> SettingsTone.Warning
        DesktopFeatureState.AndroidOnly -> SettingsTone.Normal
    }

private const val PROVIDER_BASE_URL = "provider-base-url"
private const val PROVIDER_API_KEY = "provider-api-key"
private const val MB_PER_GB = 1024
private const val INSTANCE_ID_PREVIEW = 8
private const val PERCENT = 100f
