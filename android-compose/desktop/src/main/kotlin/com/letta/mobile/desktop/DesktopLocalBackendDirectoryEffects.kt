package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.letta.mobile.data.runtime.LocalBackendDirectoryValidation
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.desktop.runtime.DesktopLocalBackendDirectorySettings
import com.letta.mobile.desktop.runtime.DesktopLocalRuntimeHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** State for the "Local backend data directory" settings card. */
internal data class DesktopLocalBackendDirectoryState(
    val currentPath: String?,
    val defaultPath: String,
    val isSaving: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
) {
    /** True when the user has not overridden the default location. */
    val isDefault: Boolean get() = currentPath == null

    /** The path actually in effect, for display. */
    val effectivePath: String get() = currentPath ?: defaultPath
}

internal data class DesktopLocalBackendDirectoryActions(
    val onLoad: () -> Unit,
    val onChangeDirectory: (path: String) -> Unit,
    val onResetToDefault: () -> Unit,
)

/**
 * Owns the (state, actions) pair for [DesktopLocalBackendDirectorySettingsCard].
 * File IO runs on [Dispatchers.IO]; [onSaved] is invoked after a successful
 * change/reset so the caller can restart the bundled runtime — same pattern
 * as [rememberDesktopLocalRuntimeProviderState] (see `LettaDesktopApp.kt`,
 * which routes this to `chatController.retryConnection()` only when the
 * active backend is the local runtime).
 */
@Composable
internal fun rememberDesktopLocalBackendDirectoryState(
    scope: CoroutineScope,
    secureSettingsStore: SecureSettingsStore,
    onSaved: () -> Unit,
): Pair<DesktopLocalBackendDirectoryState, DesktopLocalBackendDirectoryActions> {
    var state by remember {
        mutableStateOf(
            DesktopLocalBackendDirectoryState(
                currentPath = null,
                defaultPath = DesktopLocalRuntimeHost.defaultBackendDirectory().absolutePath,
            ),
        )
    }

    val actions = remember(scope, secureSettingsStore) {
        DesktopLocalBackendDirectoryActions(
            onLoad = {
                val stored = DesktopLocalBackendDirectorySettings.readStoredPath(secureSettingsStore)
                state = state.copy(currentPath = stored)
            },
            onChangeDirectory = { path ->
                scope.launch {
                    state = state.copy(isSaving = true, message = null, isError = false)
                    val result = withContext(Dispatchers.IO) {
                        DesktopLocalBackendDirectorySettings.save(secureSettingsStore, path)
                    }
                    when (result) {
                        is LocalBackendDirectoryValidation.Result.Valid -> {
                            state = state.copy(
                                currentPath = path.trim(),
                                isSaving = false,
                                message = "Saved. Restarting the bundled runtime…",
                                isError = false,
                            )
                            onSaved()
                        }
                        is LocalBackendDirectoryValidation.Result.Invalid -> {
                            state = state.copy(isSaving = false, message = result.reason, isError = true)
                        }
                    }
                }
            },
            onResetToDefault = {
                scope.launch {
                    state = state.copy(isSaving = true, message = null, isError = false)
                    withContext(Dispatchers.IO) {
                        DesktopLocalBackendDirectorySettings.resetToDefault(secureSettingsStore)
                    }
                    state = state.copy(
                        currentPath = null,
                        isSaving = false,
                        message = "Reset to default. Restarting the bundled runtime…",
                        isError = false,
                    )
                    onSaved()
                }
            },
        )
    }

    return state to actions
}

internal data class DesktopLocalConfigState(
    val providerState: DesktopLocalRuntimeProviderState,
    val providerActions: DesktopLocalRuntimeProviderActions,
    val directoryState: DesktopLocalBackendDirectoryState,
    val directoryActions: DesktopLocalBackendDirectoryActions,
)

@Composable
internal fun rememberDesktopLocalConfigState(
    scope: CoroutineScope,
    secureSettingsStore: SecureSettingsStore,
    isLocalMode: Boolean,
    onRestartRequested: () -> Unit,
): DesktopLocalConfigState {
    val (providerState, providerActions) = rememberDesktopLocalRuntimeProviderState(
        scope = scope,
        onSaved = {
            if (isLocalMode) onRestartRequested()
        },
    )
    val (directoryState, directoryActions) = rememberDesktopLocalBackendDirectoryState(
        scope = scope,
        secureSettingsStore = secureSettingsStore,
        onSaved = {
            if (isLocalMode) onRestartRequested()
        },
    )
    return DesktopLocalConfigState(
        providerState = providerState,
        providerActions = providerActions,
        directoryState = directoryState,
        directoryActions = directoryActions,
    )
}
