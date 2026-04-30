package com.letta.mobile.debug

import android.content.Context
import android.util.Base64
import android.util.Log
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.SettingsRepository
import com.letta.mobile.ui.screens.config.ConfigViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object AutomationAuthBootstrap {
    private const val TAG = "AutomationAuth"
    const val PREFS_NAME = "letta_automation"
    const val KEY_PAYLOAD_BASE64 = "config_payload_base64"
    private const val DEFAULT_CONFIG_ID = "automation-auth"

    private val json = Json { ignoreUnknownKeys = true }

    fun importPendingConfig(context: Context, settingsRepository: SettingsRepository) {
        importPendingConfig(
            context = context,
            saveConfig = { config -> settingsRepository.saveConfig(config) },
            setGatewayEnabled = { enabled -> settingsRepository.setClientModeEnabled(enabled) },
            setGatewayBaseUrl = { baseUrl -> settingsRepository.setClientModeBaseUrl(baseUrl) },
            setGatewayApiKey = { apiKey -> settingsRepository.setClientModeApiKey(apiKey) },
        )
    }

    internal fun importPendingConfig(
        context: Context,
        saveConfig: suspend (LettaConfig) -> Unit,
        setGatewayEnabled: suspend (Boolean) -> Unit = {},
        setGatewayBaseUrl: suspend (String) -> Unit = {},
        setGatewayApiKey: suspend (String?) -> Unit = {},
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
                payload.gatewaySettings()?.let { gateway ->
                    setGatewayEnabled(gateway.enabled)
                    setGatewayBaseUrl(gateway.baseUrl)
                    setGatewayApiKey(gateway.apiKey)
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
        val gatewayUrl: String? = null,
        val gatewayApiKey: String? = null,
        val gatewayEnabled: Boolean? = null,
    ) {
        fun normalized(): AutomationAuthPayload {
            val normalizedUrl = serverUrl.trim()
                .ifBlank { throw IllegalArgumentException("serverUrl is required") }
                .let { url ->
                    if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
                }
                .removeSuffix("/")
            val normalizedToken = accessToken.trim()
                .ifBlank { throw IllegalArgumentException("accessToken is required") }
            val normalizedId = configId.trim().ifBlank { DEFAULT_CONFIG_ID }
            val normalizedGatewayUrl = gatewayUrl?.trim().orEmpty()
                .takeIf { it.isNotBlank() }
                ?.let { url ->
                    when {
                        url.startsWith("http://") ||
                            url.startsWith("https://") ||
                            url.startsWith("ws://") ||
                            url.startsWith("wss://") -> url
                        else -> "ws://$url"
                    }
                }
                ?.removeSuffix("/")
            val normalizedGatewayApiKey = gatewayApiKey?.trim().orEmpty()
                .takeIf { it.isNotBlank() }
            if ((normalizedGatewayUrl == null) != (normalizedGatewayApiKey == null)) {
                throw IllegalArgumentException(
                    "gatewayUrl and gatewayApiKey must both be provided when bootstrapping LettaBot credentials"
                )
            }
            return copy(
                serverUrl = normalizedUrl,
                accessToken = normalizedToken,
                configId = normalizedId,
                gatewayUrl = normalizedGatewayUrl,
                gatewayApiKey = normalizedGatewayApiKey,
                gatewayEnabled = if (normalizedGatewayUrl != null) {
                    gatewayEnabled ?: true
                } else {
                    null
                },
            )
        }

        fun toLettaConfig(): LettaConfig {
            val resolvedMode = when (mode?.trim()?.uppercase()) {
                LettaConfig.Mode.CLOUD.name -> LettaConfig.Mode.CLOUD
                LettaConfig.Mode.SELF_HOSTED.name -> LettaConfig.Mode.SELF_HOSTED
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
                accessToken = accessToken,
            )
        }

        fun gatewaySettings(): GatewaySettings? {
            val baseUrl = gatewayUrl ?: return null
            val apiKey = gatewayApiKey ?: return null
            return GatewaySettings(
                baseUrl = baseUrl,
                apiKey = apiKey,
                enabled = gatewayEnabled ?: true,
            )
        }
    }

    internal data class GatewaySettings(
        val baseUrl: String,
        val apiKey: String,
        val enabled: Boolean,
    )
}
