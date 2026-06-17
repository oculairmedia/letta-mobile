package com.letta.mobile.runtime.clipboard

import com.letta.mobile.runtime.actions.MobileActionCapability
import com.letta.mobile.runtime.actions.MobileActionCapabilityStatus
import com.letta.mobile.runtime.actions.MobileActionExecutionMode
import com.letta.mobile.runtime.actions.MobileActionRiskTier
import com.letta.mobile.runtime.actions.MobileActionSensitivity
import com.letta.mobile.runtime.actions.MobileActionToolResponse
import com.letta.mobile.runtime.actions.MobileExternalToolHandler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

class ClipboardTool @Inject constructor(
    private val provider: ClipboardProvider,
) : MobileExternalToolHandler {

    override val toolName: String = "clipboard"

    override fun capabilities(): List<MobileActionCapability> = listOf(
        MobileActionCapability(
            id = CLIPBOARD_READ_CAPABILITY_ID,
            toolName = CLIPBOARD_READ_TOOL_NAME,
            label = "Read clipboard text",
            description = "Read the current text content from the device clipboard.",
            status = MobileActionCapabilityStatus.Available,
            riskTier = MobileActionRiskTier.Low,
            sensitivity = MobileActionSensitivity.PersonalData,
            executionMode = MobileActionExecutionMode.Direct,
            reason = "Clipboard read is available while app is in foreground. Android 10+ restricts background clipboard access.",
        ),
        MobileActionCapability(
            id = CLIPBOARD_WRITE_CAPABILITY_ID,
            toolName = CLIPBOARD_WRITE_TOOL_NAME,
            label = "Write clipboard text",
            description = "Write text content to the device clipboard.",
            status = MobileActionCapabilityStatus.Available,
            riskTier = MobileActionRiskTier.Low,
            sensitivity = MobileActionSensitivity.DeviceState,
            executionMode = MobileActionExecutionMode.Direct,
            reason = "Clipboard write requires no special permissions.",
        ),
    )

    override fun handle(input: JsonObject, actionId: String): MobileActionToolResponse {
        val action = input.string("action")?.trim()
        return when (action) {
            "read" -> handleRead(actionId)
            "write" -> handleWrite(input, actionId)
            else -> MobileActionToolResponse(
                success = false,
                toolName = toolName,
                status = MobileActionCapabilityStatus.Error,
                message = "Invalid action: '$action'. Use 'read' or 'write'.",
                capabilityId = CLIPBOARD_READ_CAPABILITY_ID,
                actionId = actionId,
                error = "invalid_action",
            )
        }
    }

    private fun handleRead(actionId: String): MobileActionToolResponse {
        val response = provider.readText()
        return MobileActionToolResponse(
            success = response.success,
            toolName = CLIPBOARD_READ_TOOL_NAME,
            status = if (response.success) MobileActionCapabilityStatus.Available else MobileActionCapabilityStatus.Error,
            message = buildString {
                append(response.reason)
                if (response.text != null) {
                    append(" Text: \"${response.text}\"")
                }
            },
            capabilityId = CLIPBOARD_READ_CAPABILITY_ID,
            actionId = actionId,
            error = if (response.success) null else "read_failed",
        )
    }

    private fun handleWrite(input: JsonObject, actionId: String): MobileActionToolResponse {
        val text = input.string("text")
        if (text == null) {
            return MobileActionToolResponse(
                success = false,
                toolName = CLIPBOARD_WRITE_TOOL_NAME,
                status = MobileActionCapabilityStatus.Error,
                message = "Missing required parameter: 'text'",
                capabilityId = CLIPBOARD_WRITE_CAPABILITY_ID,
                actionId = actionId,
                error = "missing_text",
            )
        }
        val response = provider.writeText(text)
        return MobileActionToolResponse(
            success = response.success,
            toolName = CLIPBOARD_WRITE_TOOL_NAME,
            status = if (response.success) MobileActionCapabilityStatus.Available else MobileActionCapabilityStatus.Error,
            message = response.reason,
            capabilityId = CLIPBOARD_WRITE_CAPABILITY_ID,
            actionId = actionId,
            error = if (response.success) null else "write_failed",
        )
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    companion object {
        const val CLIPBOARD_READ_TOOL_NAME = "clipboard.read"
        const val CLIPBOARD_WRITE_TOOL_NAME = "clipboard.write"
        const val CLIPBOARD_READ_CAPABILITY_ID = "android.clipboard.read"
        const val CLIPBOARD_WRITE_CAPABILITY_ID = "android.clipboard.write"
    }
}
