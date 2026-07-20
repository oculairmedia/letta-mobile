package com.letta.mobile.runtime.mobileactions

import android.content.Context
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object MobileIntentActionsSelfTest {
    private val json = Json { encodeDefaults = true; explicitNulls = false; prettyPrint = true }

    fun run(context: Context): MobileIntentActionsSelfTestReport {
        val tool = MobileIntentActionTool(context.applicationContext)
        val checks = listOf(
            check(tool, MobileIntentActionTool.OPEN_WIFI_SETTINGS) {
                put("tool", MobileIntentActionTool.OPEN_WIFI_SETTINGS)
                put("dryRun", true)
            },
            check(tool, MobileIntentActionTool.SHOW_LOCATION_ON_MAP) {
                put("tool", MobileIntentActionTool.SHOW_LOCATION_ON_MAP)
                put("location", "1600 Amphitheatre Parkway, Mountain View, CA")
                put("dryRun", true)
            },
            check(tool, MobileIntentActionTool.COMPOSE_EMAIL) {
                put("tool", MobileIntentActionTool.COMPOSE_EMAIL)
                put("to", "person@example.com")
                put("subject", "Draft subject")
                put("body", "Draft body")
                put("dryRun", true)
            },
            check(tool, MobileIntentActionTool.INSERT_CONTACT) {
                put("tool", MobileIntentActionTool.INSERT_CONTACT)
                put("firstName", "Ada")
                put("lastName", "Lovelace")
                put("phoneNumber", "+15551234567")
                put("email", "ada@example.com")
                put("dryRun", true)
            },
            check(tool, MobileIntentActionTool.INSERT_CALENDAR_EVENT) {
                put("tool", MobileIntentActionTool.INSERT_CALENDAR_EVENT)
                put("datetime", "2030-01-02T03:04:05Z")
                put("title", "User mediated event")
                put("dryRun", true)
            },
        )
        val report = MobileIntentActionsSelfTestReport(
            passed = checks.all { it.status == "resolved" || it.status == "not_resolved" } && checks.all { it.userActionRequired && !it.launched },
            checks = checks,
        )
        File(context.filesDir, MobileIntentActionsSelfTestReceiver.REPORT_FILE).writeText(json.encodeToString(report))
        return report
    }

    private fun check(
        tool: MobileIntentActionTool,
        name: String,
        input: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): MobileIntentActionSelfTestCheck {
        val response = tool.handle(buildJsonObject(input))
        return MobileIntentActionSelfTestCheck(
            tool = name,
            status = response.status,
            resolved = response.resolved ?: false,
            launched = response.launched ?: false,
            userActionRequired = response.userActionRequired,
            action = response.intent?.action,
            data = response.intent?.data,
            type = response.intent?.type,
            flags = response.intent?.flags ?: 0,
            error = response.error,
        )
    }
}

@Serializable
data class MobileIntentActionsSelfTestReport(
    val passed: Boolean,
    val checks: List<MobileIntentActionSelfTestCheck>,
)

@Serializable
data class MobileIntentActionSelfTestCheck(
    val tool: String,
    val status: String,
    val resolved: Boolean,
    val launched: Boolean,
    val userActionRequired: Boolean,
    val action: String? = null,
    val data: String? = null,
    val type: String? = null,
    val flags: Int = 0,
    val error: String? = null,
)
