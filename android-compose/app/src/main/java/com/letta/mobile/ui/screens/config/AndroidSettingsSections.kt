package com.letta.mobile.ui.screens.config

import com.letta.mobile.data.settings.SettingsAction
import com.letta.mobile.data.settings.SettingsOption
import com.letta.mobile.data.settings.SettingsRow
import com.letta.mobile.data.settings.SettingsSectionModel
import com.letta.mobile.data.settings.SettingsStatus
import com.letta.mobile.data.settings.SettingsTone
import com.letta.mobile.data.transport.appserver.LettaCodeContract
import com.letta.mobile.runtime.local.EmbeddedLettaCodeRuntimeStatus
import com.letta.mobile.runtime.local.modelcatalog.EmbeddedModelCatalogItem
import com.letta.mobile.runtime.local.modelcatalog.EmbeddedModelDownloadState
import com.letta.mobile.util.FormatHelpers
import kotlin.math.roundToInt

/*
 * letta-mobile-ckmub.3.12 (hard cut S10): Android's own Settings sections as data. The shared
 * SettingsPage draws them; nothing here draws. Replaces ConfigScreen's local model, embedded
 * runtime, background delivery, features and integrations cards.
 */

/** What the on-device model section can ask of the settings view model and the file picker. */
internal class OnDeviceModelActions(
    val viewModel: ConfigViewModel,
    val onImportModel: () -> Unit,
    val onSave: () -> Unit,
)

/**
 * The embedded runtime and its model (Local runtime only): the runtime's status, the Hugging Face
 * token, the catalog downloads, the model file and its tuning, and an OpenAI-compatible provider
 * on the local network as the alternative to an on-device model.
 */
internal fun onDeviceModelSection(state: ConfigUiState, actions: OnDeviceModelActions): SettingsSectionModel {
    val vm = actions.viewModel
    return SettingsSectionModel(
        key = "android-on-device-model",
        title = "On-device model",
        supporting = "The letta-code runtime on this phone runs its agents with a model file here, or with a model server on your network.",
        rows = buildList {
            addAll(runtimeStatusRows(state.embeddedRuntimeStatus))
            add(
                SettingsRow.Text(
                    key = "hugging-face-token",
                    title = "Hugging Face token",
                    value = state.huggingFaceToken,
                    placeholder = "Needed for gated models",
                    secret = true,
                    onChange = vm::updateHuggingFaceToken,
                ),
            )
            addAll(catalogRows(state, vm))
            addAll(modelFileRows(state, actions))
            addAll(localProviderRows(state, vm))
            add(SettingsRow.Actions("model-save", listOf(SettingsAction("model-save", if (state.isSaving) "Saving…" else "Save model settings", primary = true, enabled = !state.isSaving) { actions.onSave() })))
        },
    )
}

private fun runtimeStatusRows(status: EmbeddedLettaCodeRuntimeStatus): List<SettingsRow> = listOf(
    SettingsRow.Info("runtime-version", "Embedded runtime", status.version.ifBlank { "disabled" }),
    SettingsRow.Info(
        "runtime-execution",
        "Execution",
        if (status.runnable) "Enabled" else "Disabled",
        if (status.runnable) SettingsTone.Positive else SettingsTone.Warning,
    ),
    SettingsRow.Note(
        "runtime-note",
        SettingsStatus(
            if (status.runnable) {
                "Agents run on this device. Keep the app allowed in the background so replies and notifications arrive."
            } else {
                "This build cannot run the embedded runtime; connect to a remote host instead."
            },
        ),
    ),
    SettingsRow.Note("runtime-dev-only", SettingsStatus(EMBEDDED_RUNTIME_DEV_ONLY_NOTE, SettingsTone.Warning)),
)

/**
 * letta-mobile-ckmub.5: the embedded runtime is for development. It is letta-code
 * [LettaCodeContract.ANDROID_EMBEDDED_VERSION] (nodejs-mobile, Node 18), below the
 * [LettaCodeContract.VERSION] contract, so features verified against the contract are remote-only.
 */
internal val EMBEDDED_RUNTIME_DEV_ONLY_NOTE: String =
    "For development only. The on-phone runtime is letta-code ${LettaCodeContract.ANDROID_EMBEDDED_VERSION}, " +
        "older than the ${LettaCodeContract.VERSION} the app is built for, so newer features are missing. " +
        "For everyday use, connect to a desktop or remote host."

private fun catalogRows(state: ConfigUiState, vm: ConfigViewModel): List<SettingsRow> {
    if (state.embeddedModelCatalog.isEmpty()) {
        return listOf(SettingsRow.Note("catalog-empty", SettingsStatus("No downloadable models for this device yet.")))
    }
    val hasToken = state.huggingFaceToken.isNotBlank()
    return state.embeddedModelCatalog.flatMap { item -> catalogItemRows(item, CatalogRowContext(state.localModelPath, hasToken, vm)) }
}

private class CatalogRowContext(val selectedPath: String, val hasHuggingFaceToken: Boolean, val vm: ConfigViewModel)

private fun catalogItemRows(item: EmbeddedModelCatalogItem, context: CatalogRowContext): List<SettingsRow> {
    val entry = item.entry
    val key = "catalog-${entry.modelId}"
    val size = "${FormatHelpers.formatByteSize(entry.sizeInBytes)} · ${FormatHelpers.formatByteSize(entry.estimatedPeakMemoryInBytes)} peak memory"
    val rows = mutableListOf<SettingsRow>(SettingsRow.Info(key, entry.name, size))
    if (!entry.isSupported) {
        rows += SettingsRow.Note("$key-unsupported", SettingsStatus(entry.unsupportedReason ?: "Not supported on this device.", SettingsTone.Warning))
        return rows
    }
    if (entry.requiresAuth && !context.hasHuggingFaceToken) {
        rows += SettingsRow.Note("$key-token", SettingsStatus("Needs a Hugging Face token: add one above to download it.", SettingsTone.Warning))
    }
    rows += downloadRows(item, key, context)
    return rows
}

private fun downloadRows(item: EmbeddedModelCatalogItem, key: String, context: CatalogRowContext): List<SettingsRow> =
    when (val download = item.state) {
        EmbeddedModelDownloadState.Idle, EmbeddedModelDownloadState.Cancelled, is EmbeddedModelDownloadState.Failed -> buildList {
            (download as? EmbeddedModelDownloadState.Failed)?.let { add(SettingsRow.Note("$key-failed", SettingsStatus(it.message, SettingsTone.Error))) }
            val canDownload = !item.entry.requiresAuth || context.hasHuggingFaceToken
            add(SettingsRow.Actions("$key-actions", listOf(SettingsAction("$key-download", "Download", enabled = canDownload) { context.vm.downloadEmbeddedModel(item) })))
        }
        is EmbeddedModelDownloadState.Downloading -> listOf(
            SettingsRow.Progress(
                "$key-progress",
                embeddedModelDownloadProgress(download.bytesDownloaded, download.totalBytes),
                embeddedModelDownloadProgressLabel(download.bytesDownloaded, download.totalBytes),
            ),
            SettingsRow.Actions("$key-actions", listOf(SettingsAction("$key-cancel", "Cancel") { context.vm.cancelEmbeddedModelDownload(item) })),
        )
        is EmbeddedModelDownloadState.Downloaded -> {
            val selected = download.localPath == context.selectedPath
            listOf(
                SettingsRow.Actions(
                    "$key-actions",
                    listOf(SettingsAction("$key-select", if (selected) "In use" else "Use this model", primary = !selected, enabled = !selected) { context.vm.selectEmbeddedModel(item) }),
                ),
            )
        }
    }

private fun modelFileRows(state: ConfigUiState, actions: OnDeviceModelActions): List<SettingsRow> {
    val vm = actions.viewModel
    return listOf(
        SettingsRow.Text("model-path", "Model file", state.localModelPath, placeholder = "/…/model.litertlm", onChange = vm::updateLocalModelPath),
        SettingsRow.Actions(
            "model-import",
            listOf(SettingsAction("model-import", if (state.isImportingLocalModel) "Importing…" else "Import a .litertlm file", enabled = !state.isImportingLocalModel) { actions.onImportModel() }),
        ),
        SettingsRow.Text("model-handle", "Model handle", state.localModelHandle, placeholder = "local/model", onChange = vm::updateLocalModelHandle),
        SettingsRow.Choice(
            key = "model-accelerator",
            title = "Accelerator",
            options = LOCAL_MODEL_ACCELERATORS.map { (value, label) -> SettingsOption(value, label) },
            selected = state.localModelAccelerator,
            onSelect = vm::updateLocalModelAccelerator,
        ),
        SettingsRow.Text("model-max-tokens", "Max tokens", state.localModelMaxTokens, numeric = true, onChange = vm::updateLocalModelMaxTokens),
    )
}

private fun localProviderRows(state: ConfigUiState, vm: ConfigViewModel): List<SettingsRow> = listOf(
    SettingsRow.Note(
        "provider-help",
        SettingsStatus("Or use a model server on your network (an OpenAI-compatible endpoint): agents still run on this device, model calls go there."),
    ),
    SettingsRow.Text(
        "provider-base-url",
        "Model server URL",
        state.localProviderBaseUrl,
        placeholder = "http(s)://<host>:<port>/v1",
        onChange = vm::updateLocalProviderBaseUrl,
    ),
    SettingsRow.Text("provider-api-key", "Model server API key", state.localProviderApiKey, secret = true, onChange = vm::updateLocalProviderApiKey),
    SettingsRow.Text("provider-model", "Model server model", state.localProviderModel, placeholder = "lmstudio/…", onChange = vm::updateLocalProviderModel),
)

private val LOCAL_MODEL_ACCELERATORS = listOf("cpu" to "CPU", "gpu" to "GPU", "npu" to "NPU")

/** The other saved backends, to switch between or remove (Backend group). */
internal fun savedBackendsSection(onOpen: () -> Unit) = SettingsSectionModel(
    key = "android-saved-backends",
    title = "Saved backends",
    supporting = "Every remote host and local runtime you have set up on this phone.",
    rows = listOf(SettingsRow.Actions("saved-backends", listOf(SettingsAction("saved-backends-open", "Manage saved backends") { onOpen() }))),
)

/** Battery optimization, which decides whether replies arrive while the app is closed (Notifications group). */
internal fun backgroundDeliverySection(exempt: Boolean, onAllow: () -> Unit) = SettingsSectionModel(
    key = "android-background-delivery",
    title = "Background delivery",
    rows = listOf(
        SettingsRow.Info(
            "battery-status",
            "Battery optimization",
            if (exempt) "Unrestricted" else "Restricted",
            if (exempt) SettingsTone.Positive else SettingsTone.Warning,
        ),
        SettingsRow.Note(
            "battery-note",
            SettingsStatus(
                if (exempt) {
                    "Replies and approvals keep arriving while the app is in the background."
                } else {
                    "Android may pause the app in the background, delaying replies and approval notifications."
                },
            ),
        ),
    ) + if (exempt) emptyList() else listOf(SettingsRow.Actions("battery-actions", listOf(SettingsAction("battery-allow", "Allow in background", primary = true) { onAllow() }))),
)

/** Phone permissions the agents can use (Providers group, beside the providers). */
internal fun systemAccessSection(onOpen: () -> Unit) = SettingsSectionModel(
    key = "android-system-access",
    title = "System access",
    supporting = "Which phone capabilities (notifications, location, files) your agents may use.",
    rows = listOf(SettingsRow.Actions("system-access", listOf(SettingsAction("system-access-open", "Manage system access") { onOpen() }))),
)

/** The app's version and runtime (Diagnostics & about group). */
internal fun androidAboutSection(versionName: String, status: EmbeddedLettaCodeRuntimeStatus) = SettingsSectionModel(
    key = "android-about",
    title = "About",
    rows = listOf(
        SettingsRow.Info("about-version", "Version", versionName),
        SettingsRow.Info("about-runtime", "Embedded letta-code", status.version.ifBlank { "disabled" }),
    ),
)

/** Where the developer section's debug entries lead; null leaves them out (release builds). */
internal class DeveloperDestinations(val onVibesyncDebug: () -> Unit, val onCanvasDebug: () -> Unit)

/** Preview features, and the debug tools of a debug build (Developer group). */
internal fun developerSection(state: ConfigUiState, vm: ConfigViewModel, debug: DeveloperDestinations?) = SettingsSectionModel(
    key = "android-developer",
    title = "Developer",
    rows = buildList {
        add(
            SettingsRow.Toggle(
                key = "dev-projects",
                title = "Projects",
                checked = state.enableProjects,
                supporting = "Group agents and conversations into projects (preview).",
                onChange = vm::updateEnableProjects,
            ),
        )
        debug?.let { destinations ->
            add(
                SettingsRow.Actions(
                    "dev-debug",
                    listOf(
                        SettingsAction("dev-vibesync", "Vibesync debug") { destinations.onVibesyncDebug() },
                        SettingsAction("dev-canvas", "Canvas debug") { destinations.onCanvasDebug() },
                    ),
                ),
            )
        }
    },
)

fun embeddedModelDownloadProgress(bytesDownloaded: Long, totalBytes: Long?): Float? {
    val total = totalBytes?.takeIf { it > 0L } ?: return null
    return (bytesDownloaded.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
}

fun embeddedModelDownloadProgressLabel(bytesDownloaded: Long, totalBytes: Long?): String {
    val downloaded = FormatHelpers.formatByteSize(bytesDownloaded.coerceAtLeast(0L))
    val total = totalBytes?.takeIf { it > 0L } ?: return downloaded
    val percent = ((bytesDownloaded.toDouble() / total.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
    return "$downloaded / ${FormatHelpers.formatByteSize(total)} · $percent%"
}
