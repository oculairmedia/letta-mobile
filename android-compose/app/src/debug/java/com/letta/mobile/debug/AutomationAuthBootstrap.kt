package com.letta.mobile.debug

import android.content.Context
import android.util.Base64
import android.util.Log
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.ui.screens.config.ConfigViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object AutomationAuthBootstrap {
    private const val TAG = "AutomationAuth"
    const val PREFS_NAME = "letta_automation"
    const val KEY_PAYLOAD_BASE64 = "config_payload_base64"
    const val EXTRA_PAYLOAD_BASE64 = "com.letta.mobile.extra.AUTOMATION_CONFIG_PAYLOAD_BASE64"
    private const val DEFAULT_CONFIG_ID = "automation-auth"

    private val json = Json { ignoreUnknownKeys = true }

    fun importPendingConfig(context: Context, settingsRepository: ISettingsRepository) {
        importPendingConfig(
            context = context,
            saveConfig = { config -> settingsRepository.saveConfig(config) },
            saveClientModeSettings = { settings -> settings.applyTo(settingsRepository) },
        )
    }

    internal fun importPendingConfig(
        context: Context,
        saveConfig: suspend (LettaConfig) -> Unit,
        saveClientModeSettings: suspend (AutomationClientModeSettings) -> Unit = {},
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val encodedPayload = prefs.getString(KEY_PAYLOAD_BASE64, null)?.trim().orEmpty()
        if (encodedPayload.isBlank()) {
            return
        }

        runCatching {
            val payload = decodePayload(encodedPayload)
            runBlocking {
                saveConfig(payload.toLettaConfig())
                payload.toClientModeSettings()?.let { settings ->
                    saveClientModeSettings(settings)
                }
            }
            Log.i(TAG, "Imported automation credentials for ${payload.serverUrl}")
        }.onFailure { error ->
            Log.w(TAG, "Dropping invalid automation credential payload", error)
        }

        prefs.edit().remove(KEY_PAYLOAD_BASE64).commit()
    }

    private fun decodePayload(encodedPayload: String): AutomationAuthPayload {
        val decodedBytes = Base64.decode(encodedPayload, Base64.DEFAULT)
        val payload = json.decodeFromString<AutomationAuthPayload>(decodedBytes.decodeToString())
        return payload.normalized()
    }

    @Serializable
    private data class AutomationAuthPayload(
        val serverUrl: String,
        val accessToken: String,
        val configId: String = DEFAULT_CONFIG_ID,
        val mode: String? = null,
        val clientModeEnabled: Boolean? = null,
        val clientModeBaseUrl: String? = null,
        val clientModeApiKey: String? = null,
    ) {
        fun normalized(): AutomationAuthPayload {
            val normalizedMode = mode?.trim()?.uppercase()
            val normalizedUrl = if (normalizedMode == LettaConfig.Mode.LOCAL.name) {
                ConfigViewModel.LOCAL_RUNTIME_URL
            } else {
                serverUrl.trim()
                    .ifBlank { throw IllegalArgumentException("serverUrl is required") }
                    .let { url ->
                        if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
                    }
                    .removeSuffix("/")
            }
            val normalizedToken = if (normalizedMode == LettaConfig.Mode.LOCAL.name) {
                accessToken.trim()
            } else {
                accessToken.trim()
                    .ifBlank { throw IllegalArgumentException("accessToken is required") }
            }
            val normalizedId = configId.trim().ifBlank { DEFAULT_CONFIG_ID }
            return copy(
                serverUrl = normalizedUrl,
                accessToken = normalizedToken,
                configId = normalizedId,
            )
        }

        fun toLettaConfig(): LettaConfig {
            val resolvedMode = when (mode?.trim()?.uppercase()) {
                LettaConfig.Mode.CLOUD.name -> LettaConfig.Mode.CLOUD
                LettaConfig.Mode.SELF_HOSTED.name -> LettaConfig.Mode.SELF_HOSTED
                LettaConfig.Mode.LOCAL.name -> LettaConfig.Mode.LOCAL
                null, "" -> if (serverUrl == ConfigViewModel.DEFAULT_CLOUD_URL) {
                    LettaConfig.Mode.CLOUD
                } else {
                    LettaConfig.Mode.SELF_HOSTED
                }
                else -> throw IllegalArgumentException("Unsupported automation mode: $mode")
            }
            return LettaConfig(
                id = configId,
                mode = resolvedMode,
                serverUrl = serverUrl,
                accessToken = if (resolvedMode == LettaConfig.Mode.LOCAL) null else accessToken,
            )
        }

        fun toClientModeSettings(): AutomationClientModeSettings? {
            val enabled = clientModeEnabled
            val baseUrl = clientModeBaseUrl?.trim()?.removeSuffix("/")
            val apiKey = clientModeApiKey?.trim()?.takeIf { it.isNotBlank() }
            if (enabled == null && baseUrl.isNullOrBlank() && apiKey == null) {
                return null
            }
            require(enabled != true || !baseUrl.isNullOrBlank()) {
                "clientModeBaseUrl is required when clientModeEnabled is true"
            }
            return AutomationClientModeSettings(
                enabled = enabled ?: false,
                baseUrl = baseUrl.orEmpty(),
                apiKey = apiKey,
            )
        }
    }

    internal data class AutomationClientModeSettings(
        val enabled: Boolean,
        val baseUrl: String,
        val apiKey: String?,
    ) {
        @Suppress("UNUSED_PARAMETER")
        suspend fun applyTo(settingsRepository: ISettingsRepository) {
            // Client Mode removed — settings no longer persist.
        }
    }
}
