package com.letta.mobile.runtime.notifications

import com.letta.mobile.runtime.actions.MobileActionCapability
import com.letta.mobile.runtime.actions.MobileActionCapabilityStatus
import com.letta.mobile.runtime.actions.MobileActionExecutionMode
import com.letta.mobile.runtime.actions.MobileActionRiskTier
import com.letta.mobile.runtime.actions.MobileActionSensitivity
import com.letta.mobile.runtime.actions.MobileActionToolResponse
import com.letta.mobile.runtime.actions.MobileExternalToolHandler
import com.letta.mobile.runtime.actions.newActionId
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * `notification.poll` — returns recent notifications captured by
 * [LettaNotificationListenerService]. OPT-IN and permission-gated: reports
 * SettingsRequired (with the settings action to grant access) until the user
 * enables Notification access. Reading notifications is cross-app data, hence
 * High risk / CrossAppData sensitivity.
 *
 * The access check is injected as a lambda so the handler is unit-testable
 * without a real Android Context.
 */
class NotificationPollTool(
    private val store: NotificationStore,
    private val isAccessGranted: () -> Boolean,
    private val json: Json = Json { encodeDefaults = true; explicitNulls = false },
) : MobileExternalToolHandler {

    override val toolName: String = TOOL_NAME

    override fun capabilities(): List<MobileActionCapability> = listOf(
        MobileActionCapability(
            id = CAPABILITY_ID,
            toolName = TOOL_NAME,
            label = "Read recent notifications",
            description = "Return recently posted Android notifications (package, title, text, time). " +
                "Reads notifications from OTHER apps — cross-app data — and requires the user to grant " +
                "Notification access. Opt-in only.",
            status = if (isAccessGranted()) {
                MobileActionCapabilityStatus.Available
            } else {
                MobileActionCapabilityStatus.SettingsRequired
            },
            riskTier = MobileActionRiskTier.High,
            sensitivity = MobileActionSensitivity.CrossAppData,
            executionMode = MobileActionExecutionMode.Direct,
            defaultEnabled = false,
            requiredSettings = listOf(
                LettaNotificationListenerService.NOTIFICATION_ACCESS_SETTINGS_ACTION,
            ),
            reason = "Requires user-granted Notification access; until then no notifications are readable.",
        ),
    )

    override fun handle(input: JsonObject, actionId: String): MobileActionToolResponse {
        if (!isAccessGranted()) {
            return MobileActionToolResponse(
                success = false,
                toolName = TOOL_NAME,
                status = MobileActionCapabilityStatus.SettingsRequired,
                message = "Notification access is not granted. Ask the user to enable it in " +
                    "Settings > Notification access for this app.",
                capabilityId = CAPABILITY_ID,
                actionId = actionId,
                requiresUserAction = true,
                intentAction = LettaNotificationListenerService.NOTIFICATION_ACCESS_SETTINGS_ACTION,
                error = "notification_access_not_granted",
            )
        }
        val requested = input["limit"]?.jsonPrimitive?.intOrNull ?: DEFAULT_LIMIT
        val limit = requested.coerceIn(1, MAX_LIMIT)
        val items = store.recent(limit)
        return MobileActionToolResponse(
            success = true,
            toolName = TOOL_NAME,
            status = MobileActionCapabilityStatus.Available,
            message = "Returned ${items.size} notification(s).",
            capabilityId = CAPABILITY_ID,
            actionId = actionId,
            payloadJson = json.encodeToString(items),
        )
    }

    companion object {
        const val TOOL_NAME = "notification.poll"
        const val CAPABILITY_ID = "android.notification.poll"
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = InMemoryNotificationStore.DEFAULT_MAX_SIZE
    }
}

/** Convenience factory used by Hilt to wire the real Android access check. */
fun newActionIdForNotificationPoll(): String = newActionId()
