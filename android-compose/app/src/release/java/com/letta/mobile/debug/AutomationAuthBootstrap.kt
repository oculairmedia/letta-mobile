package com.letta.mobile.debug

import android.content.Context
import android.util.Base64
import ca.oculair.meridian.BuildConfig
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.LettaConfigMigration
import com.letta.mobile.data.chat.runtime.BackendConfigPolicy
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.ui.screens.config.ConfigViewModel
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object AutomationAuthBootstrap {
    const val PREFS_NAME = "letta_automation"
    const val KEY_PAYLOAD_BASE64 = "config_payload_base64"
    const val EXTRA_PAYLOAD_BASE64 = "com.letta.mobile.extra.AUTOMATION_CONFIG_PAYLOAD_BASE64"
    private const val DEFAULT_CONFIG_ID = "automation-auth"

    private val json = Json { ignoreUnknownKeys = true }

    /** letta-mobile-by4vm.1: only the non-shipping `benchmark` build type; see [AutomationImportGate]. */
    val acceptsLaunchPayload: Boolean
        get() = AutomationImportGate.releaseImportEnabled(BuildConfig.BUILD_TYPE)

    fun importPendingConfig(context: Context, settingsRepository: ISettingsRepository) {
        val encodedPayload = AutomationImportGate.consumeStagedPayload(context, acceptsLaunchPayload) ?: return

        runCatching {
            val payload = decodePayload(encodedPayload)
            runBlocking {
                settingsRepository.saveConfig(payload.toLettaConfig())
                payload.toClientModeSettings()?.let { settings ->
                    settings.applyTo(settingsRepository)
                }
            }
        }.onFailure { error ->
            Telemetry.error(
                tag = "AutomationAuth",
                name = "releasePayloadImportFailed",
                throwable = error,
                "prefsName" to PREFS_NAME,
                "key" to KEY_PAYLOAD_BASE64,
            )
        }
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
                        if (BackendConfigPolicy.REMOTE_URL_PREFIXES.any { url.startsWith(it) }) url else "https://$url"
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
            // letta-mobile-ckmub.9: a retired CLOUD mode reads as Remote host (LettaConfigMigration).
            val resolvedMode = if (mode.isNullOrBlank()) {
                LettaConfig.Mode.SELF_HOSTED
            } else {
                LettaConfigMigration.modeOf(mode) ?: throw IllegalArgumentException("Unsupported automation mode: $mode")
            }
            return LettaConfigMigration.migrate(
                mode,
                LettaConfig(
                    id = configId,
                    mode = resolvedMode,
                    serverUrl = serverUrl,
                    accessToken = if (resolvedMode == LettaConfig.Mode.LOCAL) null else accessToken,
                ),
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

    private data class AutomationClientModeSettings(
        val enabled: Boolean,
        val baseUrl: String,
        val apiKey: String?,
    ) {
        @Suppress("UNUSED_PARAMETER")
        fun applyTo(settingsRepository: ISettingsRepository) {
            // Client Mode removed — settings no longer persist.
        }
    }
}
