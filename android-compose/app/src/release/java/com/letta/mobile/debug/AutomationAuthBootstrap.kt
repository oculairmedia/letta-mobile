package com.letta.mobile.debug

import android.content.Context
import com.letta.mobile.data.repository.SettingsRepository
import com.letta.mobile.util.Telemetry

object AutomationAuthBootstrap {
    fun importPendingConfig(context: Context, settingsRepository: SettingsRepository) {
        val prefs = context.getSharedPreferences("letta_automation", Context.MODE_PRIVATE)
        val hasPayload = !prefs.getString("config_payload_base64", null).isNullOrBlank()
        if (!hasPayload) {
            return
        }

        prefs.edit().remove("config_payload_base64").commit()
        Telemetry.error(
            tag = "AutomationAuth",
            name = "releasePayloadRejected",
            throwable = SecurityException("Release build rejected staged automation credentials"),
            "prefsName" to "letta_automation",
            "key" to "config_payload_base64",
        )
    }
}
