package com.letta.mobile.data.storage

interface SecureSettingsStore {
    fun getString(key: String, defaultValue: String? = null): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
    fun clear()
}
