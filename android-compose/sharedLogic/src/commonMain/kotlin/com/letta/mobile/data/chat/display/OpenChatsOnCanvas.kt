package com.letta.mobile.data.chat.display

import com.letta.mobile.data.document.attempt
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * "Open conversations on the canvas" (default on). The chat page reads it
 * once for a conversation's first presentation; the full-screen chat is the alternative, and the
 * person's later mode changes still win. Not namespaced by backend.
 */
class OpenChatsOnCanvas(
    private val store: SecureSettingsStore,
    private val io: CoroutineDispatcher = Dispatchers.Default,
) {
    private val state = MutableStateFlow(store.getString(KEY)?.toBooleanStrictOrNull() ?: true)

    /** The saved preference; true unless the person turned it off. */
    val enabled: StateFlow<Boolean> = state.asStateFlow()

    /** Applies [enabled] now and writes it on [io]. A failed write keeps it for this session and is logged. */
    suspend fun setEnabled(enabled: Boolean) {
        state.value = enabled
        withContext(io) {
            attempt { store.putString(KEY, enabled.toString()) }.exceptionOrNull()?.let { error ->
                Telemetry.error(TAG, "save_failed", error)
            }
        }
    }

    private companion object {
        const val KEY = "chat.openOnCanvas"
        const val TAG = "OpenChatsOnCanvas"
    }
}
