package com.letta.mobile.debug

import android.content.Context
import com.letta.mobile.util.Telemetry

/**
 * letta-mobile-by4vm.1: MainActivity is exported and BROWSABLE, so any installed
 * app or `intent://` link can attach the automation payload extra. The release
 * [AutomationAuthBootstrap] uses this gate so that only the non-shipping,
 * debug-signed `benchmark` build type (`.benchmark` application id, used for
 * baseline-profile capture) can import it. Every other variant built from the
 * release source set (release, playRelease, nonMinifiedRelease, ...) ignores it.
 *
 * Lives in `main` so the debug-variant unit tests can exercise the release policy.
 */
object AutomationImportGate {
    private const val IMPORT_BUILD_TYPE = "benchmark"

    fun releaseImportEnabled(buildType: String): Boolean = buildType == IMPORT_BUILD_TYPE

    /**
     * Reads and always clears the staged payload, so the `letta_automation`
     * prefs file can never persist it. Returns it only when [importEnabled].
     */
    fun consumeStagedPayload(context: Context, importEnabled: Boolean): String? {
        val prefs = context.getSharedPreferences(AutomationAuthBootstrap.PREFS_NAME, Context.MODE_PRIVATE)
        val encodedPayload = prefs.getString(AutomationAuthBootstrap.KEY_PAYLOAD_BASE64, null)?.trim().orEmpty()
        if (encodedPayload.isBlank()) {
            return null
        }
        prefs.edit().remove(AutomationAuthBootstrap.KEY_PAYLOAD_BASE64).commit()
        if (!importEnabled) {
            Telemetry.event(
                tag = "AutomationAuth",
                name = "releasePayloadRejected",
                "prefsName" to AutomationAuthBootstrap.PREFS_NAME,
                level = Telemetry.Level.WARN,
            )
            return null
        }
        return encodedPayload
    }
}
