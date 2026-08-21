package com.letta.mobile.data.storage

import kotlinx.coroutines.flow.Flow

/**
 * Platform-neutral preferences backing for non-secret UI settings (theme, pins,
 * feature flags). Android binds [androidx.datastore.core.DataStore]; desktop can
 * use a properties file adapter in a follow-up slice.
 */
interface SettingsPreferencesStore {
    val snapshots: Flow<SettingsPreferencesSnapshot>

    suspend fun edit(block: suspend (MutableSettingsPreferencesEditor) -> Unit)

    suspend fun clearAll()
}

interface SettingsPreferencesSnapshot {
    fun getString(key: String): String?

    fun getBoolean(key: String): Boolean?

    fun getFloat(key: String): Float?

    fun getStringSet(key: String): Set<String>?
}

interface MutableSettingsPreferencesEditor : SettingsPreferencesSnapshot {
    fun putString(key: String, value: String)

    fun putBoolean(key: String, value: Boolean)

    fun putFloat(key: String, value: Float)

    fun putStringSet(key: String, value: Set<String>)

    fun remove(key: String)

    fun clear()
}
