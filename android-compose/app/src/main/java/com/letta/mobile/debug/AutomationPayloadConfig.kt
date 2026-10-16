package com.letta.mobile.debug

import com.letta.mobile.data.model.LettaConfig

/**
 * The backend config an automation credential payload describes; shared by the debug and
 * release [AutomationAuthBootstrap] variants. A blank [mode] means Remote host; an unknown one
 * is rejected. A LOCAL config carries no token.
 */
internal fun automationLettaConfig(configId: String, mode: String?, serverUrl: String, accessToken: String): LettaConfig {
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
