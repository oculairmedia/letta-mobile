package com.letta.mobile.testutil

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.letta.mobile.data.storage.SecureSettingsStore
import java.io.File
import java.util.UUID

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

fun createTestPreferencesDataStore(): DataStore<Preferences> {
    val file = File(
        System.getProperty("java.io.tmpdir"),
        "letta-mobile-settings-tests/${UUID.randomUUID()}.preferences_pb",
    )
    file.parentFile?.mkdirs()
    return PreferenceDataStoreFactory.create(produceFile = { file })
}
