package com.letta.mobile.testutil

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.letta.mobile.data.storage.SecureSettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InMemorySecureSettingsStore : SecureSettingsStore {
    private val values = mutableMapOf<String, String>()

    override fun getString(key: String, defaultValue: String?): String? = values[key] ?: defaultValue

    override fun putString(key: String, value: String) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }

    override fun clear() {
        values.clear()
    }
}

fun createTestPreferencesDataStore(): DataStore<Preferences> = InMemoryPreferencesDataStore()

private class InMemoryPreferencesDataStore(
    initialPreferences: Preferences = emptyPreferences(),
) : DataStore<Preferences> {
    private val state = MutableStateFlow(initialPreferences)
    private val updateMutex = Mutex()

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        return updateMutex.withLock {
            val updated = transform(state.value)
            state.value = updated
            updated
        }
    }
}
