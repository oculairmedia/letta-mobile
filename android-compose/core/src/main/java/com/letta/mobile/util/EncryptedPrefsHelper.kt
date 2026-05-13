package com.letta.mobile.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EncryptedPrefsHelper @Inject constructor() {
    init {
        // letta-mobile-rnyg: do not fail on reassignment. Tests (and any
        // Robolectric/Hilt restart path) may construct multiple instances
        // across test invocations; the latest construction always wins.
        INSTANCE = this
    }

    companion object {
        private const val TAG = "EncryptedPrefsHelper"
        private const val PREFS_NAME = "letta_secure_prefs"

        @Volatile
        private var INSTANCE: EncryptedPrefsHelper? = null

        /**
         * Static bridge for existing callers (syf4 migration). `LettaApplication`
         * eagerly injects the instance so the production path goes through the
         * Hilt-built singleton; tests and other callers that reach this before
         * Hilt has built the helper get a lazily-created default instance.
         */
        fun getEncryptedPrefs(context: Context): SharedPreferences {
            val instance = INSTANCE ?: synchronized(this) {
                INSTANCE ?: EncryptedPrefsHelper().also { INSTANCE = it }
            }
            return instance.getEncryptedPrefs(context)
        }
    }

    fun getEncryptedPrefs(context: Context): SharedPreferences {
        return runCatching {
            createEncryptedPrefs(context)
        }.getOrElse { error ->
            Log.w(TAG, "Encrypted preferences are unreadable; resetting secure store", error)
            resetSecureStore(context)
            createEncryptedPrefs(context)
        }
    }

    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun resetSecureStore(context: Context) {
        context.deleteSharedPreferences(PREFS_NAME)
        runCatching {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            keyStore.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
        }.onFailure { error ->
            Log.w(TAG, "Unable to delete encrypted preferences master key", error)
        }
    }
}
