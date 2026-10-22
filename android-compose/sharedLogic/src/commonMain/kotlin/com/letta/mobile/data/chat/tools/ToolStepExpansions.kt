package com.letta.mobile.data.chat.tools

import com.letta.mobile.data.serialization.LettaJson
import com.letta.mobile.data.storage.SecureSettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Which tool steps the person opened or closed against their default, remembered across reloads.
 *
 * Only a flip from a step's default is kept (a step is collapsed by default), so a timeline of
 * collapsed steps holds nothing here, however long it is. The set is bounded: past [capacity]
 * keys the oldest flip is forgotten, and that step reads its default again.
 */
class ToolStepExpansions(
    private val storage: SecureSettingsStore? = null,
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val flipped = MutableStateFlow(load())

    /** The flipped step keys, oldest first. */
    val toggled: StateFlow<List<String>> = flipped.asStateFlow()

    /** Whether the step [key], open by default when [openByDefault], is open now. */
    fun isExpanded(key: String, openByDefault: Boolean, toggled: List<String> = flipped.value): Boolean =
        openByDefault != (key in toggled)

    /** Opens or closes the step [key] by flipping it from its default. */
    fun toggle(key: String) {
        flipped.update { current ->
            if (key in current) current - key else (current + key).takeLast(capacity)
        }
        save(flipped.value)
    }

    private fun load(): List<String> {
        val stored = storage?.getString(KEY) ?: return emptyList()
        return runCatching { LettaJson.persisted.decodeFromString(KeysSerializer, stored) }.getOrDefault(emptyList()).takeLast(capacity)
    }

    private fun save(keys: List<String>) {
        val store = storage ?: return
        if (keys.isEmpty()) store.remove(KEY) else store.putString(KEY, LettaJson.persisted.encodeToString(KeysSerializer, keys))
    }

    companion object {
        /** Flips kept before the oldest is forgotten. */
        const val DEFAULT_CAPACITY: Int = 256

        /** Where the flips are kept in the device's settings. */
        const val KEY: String = "chat.tool.steps.toggled"

        private val KeysSerializer = ListSerializer(String.serializer())
    }
}
