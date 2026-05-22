package com.letta.mobile.di

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.util.EncryptedPrefsHelper
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "letta_settings")

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class EncryptedPrefs

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {
    @Provides
    @Singleton
    fun provideSettingsDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = context.settingsDataStore

    @Provides
    @Singleton
    @EncryptedPrefs
    fun provideEncryptedPrefs(
        @ApplicationContext context: Context,
    ): SharedPreferences = EncryptedPrefsHelper.getEncryptedPrefs(context)

    @Provides
    @Singleton
    fun provideSecureSettingsStore(
        @EncryptedPrefs sharedPreferences: SharedPreferences,
    ): SecureSettingsStore = SharedPreferencesSecureSettingsStore(sharedPreferences)
}

private class SharedPreferencesSecureSettingsStore(
    private val sharedPreferences: SharedPreferences,
) : SecureSettingsStore {
    override fun getString(key: String, defaultValue: String?): String? =
        sharedPreferences.getString(key, defaultValue)

    override fun putString(key: String, value: String) {
        sharedPreferences.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        sharedPreferences.edit().remove(key).apply()
    }

    override fun clear() {
        sharedPreferences.edit().clear().apply()
    }
}
