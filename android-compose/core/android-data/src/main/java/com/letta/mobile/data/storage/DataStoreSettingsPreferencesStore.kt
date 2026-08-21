package com.letta.mobile.data.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Android DataStore binding for [SettingsPreferencesStore] (Phase 5q). */
class DataStoreSettingsPreferencesStore(
    private val dataStore: DataStore<Preferences>,
) : SettingsPreferencesStore {
    override val snapshots: Flow<SettingsPreferencesSnapshot> =
        dataStore.data.map { PreferencesSnapshot(it) }

    override suspend fun edit(block: suspend (MutableSettingsPreferencesEditor) -> Unit) {
        dataStore.edit { mutable ->
            block(MutablePreferencesEditor(mutable))
        }
    }

    override suspend fun clearAll() {
        dataStore.edit { it.clear() }
    }

    private class PreferencesSnapshot(
        private val prefs: Preferences,
    ) : SettingsPreferencesSnapshot {
        override fun getString(key: String): String? = prefs[stringPreferencesKey(key)]

        override fun getBoolean(key: String): Boolean? = prefs[booleanPreferencesKey(key)]

        override fun getFloat(key: String): Float? = prefs[floatPreferencesKey(key)]

        override fun getStringSet(key: String): Set<String>? = prefs[stringSetPreferencesKey(key)]
    }

    private class MutablePreferencesEditor(
        private val prefs: MutablePreferences,
    ) : MutableSettingsPreferencesEditor {
        override fun getString(key: String): String? = prefs[stringPreferencesKey(key)]

        override fun getBoolean(key: String): Boolean? = prefs[booleanPreferencesKey(key)]

        override fun getFloat(key: String): Float? = prefs[floatPreferencesKey(key)]

        override fun getStringSet(key: String): Set<String>? = prefs[stringSetPreferencesKey(key)]

        override fun putString(key: String, value: String) {
            prefs[stringPreferencesKey(key)] = value
        }

        override fun putBoolean(key: String, value: Boolean) {
            prefs[booleanPreferencesKey(key)] = value
        }

        override fun putFloat(key: String, value: Float) {
            prefs[floatPreferencesKey(key)] = value
        }

        override fun putStringSet(key: String, value: Set<String>) {
            prefs[stringSetPreferencesKey(key)] = value
        }

        override fun remove(key: String) {
            prefs.remove(stringPreferencesKey(key))
            prefs.remove(booleanPreferencesKey(key))
            prefs.remove(floatPreferencesKey(key))
            prefs.remove(stringSetPreferencesKey(key))
        }

        override fun clear() {
            prefs.clear()
        }
    }
}
