package com.letta.mobile.debug

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.chat.runtime.BackendConfigPolicy
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

    /** Debug builds always accept the launch-intent payload (release gates it). */
    val acceptsLaunchPayload: Boolean
        get() = true

    fun importPendingConfig(context: Context, settingsRepository: ISettingsRepository) {
        importPendingConfig(
            context = context,
            saveConfig = { config -> settingsRepository.saveConfig(config) },
        )
    }

    fun importPendingConfig(
        context: Context,
        saveConfig: suspend (LettaConfig) -> Unit,
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val encodedPayload = prefs.getString(KEY_PAYLOAD_BASE64, null)?.trim().orEmpty()
        if (encodedPayload.isBlank()) {
            return
        }

        runCatching {
            val payload = decodePayload(encodedPayload)
            runBlocking { saveConfig(payload.toLettaConfig()) }
            Log.i(TAG, "Imported automation credentials for ${payload.serverUrl}")
        }.onFailure { error ->
            Log.w(TAG, "Dropping invalid automation credential payload", error)
        }

        prefs.edit(commit = true) { remove(KEY_PAYLOAD_BASE64) }
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
            val resolvedMode = if (mode.isNullOrBlank()) {
                LettaConfig.Mode.SELF_HOSTED
            } else {
                LettaConfig.Mode.entries.firstOrNull { it.name == mode.trim().uppercase() }
                    ?: throw IllegalArgumentException("Unsupported automation mode: $mode")
            }
            return LettaConfig(
                id = configId,
                mode = resolvedMode,
                serverUrl = serverUrl,
                accessToken = if (resolvedMode == LettaConfig.Mode.LOCAL) null else accessToken,
            )
        }

    }
}
