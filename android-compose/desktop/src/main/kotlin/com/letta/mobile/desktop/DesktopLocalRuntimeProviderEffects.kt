package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.letta.mobile.data.runtime.LocalRuntimeProviderConfig
import com.letta.mobile.desktop.runtime.DesktopLocalRuntimeProviderStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the (state, actions) pair for [LocalRuntimeProviderSettingsCard].
 * File IO runs on [Dispatchers.IO] via [store]; [onSaved] is invoked after
 * a successful write so the caller can restart the bundled runtime (see
 * `LettaDesktopApp.kt`, which routes this to `chatController.retryConnection()`
 * only when the active backend is the local runtime).
 */
@Composable
internal fun rememberDesktopLocalRuntimeProviderState(
    scope: CoroutineScope,
    onSaved: () -> Unit,
    store: DesktopLocalRuntimeProviderStore = remember { DesktopLocalRuntimeProviderStore.default },
): Pair<DesktopLocalRuntimeProviderState, DesktopLocalRuntimeProviderActions> {
    var state by remember { mutableStateOf(DesktopLocalRuntimeProviderState(status = null)) }

    val actions = remember(scope, store) {
        DesktopLocalRuntimeProviderActions(
            onLoad = {
                scope.launch {
                    val status = withContext(Dispatchers.IO) { store.readStatus() }
                    state = state.copy(status = status)
                }
            },
            onSave = { baseUrl, apiKey ->
                scope.launch {
                    state = state.copy(isSaving = true, message = null, isError = false)
                    val result = runCatching { LocalRuntimeProviderConfig(baseUrl = baseUrl, apiKey = apiKey) }
                        .mapCatching { config -> withContext(Dispatchers.IO) { store.save(config) }.getOrThrow() }
                    result.fold(
                        onSuccess = { status ->
                            state = state.copy(
                                status = status,
                                isSaving = false,
                                message = "Saved. Restarting the bundled runtime…",
                                isError = false,
                            )
                            onSaved()
                        },
                        onFailure = { error ->
                            state = state.copy(
                                isSaving = false,
                                message = error.message ?: "Could not save the local runtime provider config",
                                isError = true,
                            )
                        },
                    )
                }
            },
        )
    }

    return state to actions
}
