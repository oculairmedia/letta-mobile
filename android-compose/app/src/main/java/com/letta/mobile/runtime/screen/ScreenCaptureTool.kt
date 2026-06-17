package com.letta.mobile.runtime.screen

import com.letta.mobile.runtime.actions.MobileActionCapability
import com.letta.mobile.runtime.actions.MobileActionCapabilityStatus
import com.letta.mobile.runtime.actions.MobileActionExecutionMode
import com.letta.mobile.runtime.actions.MobileActionRiskTier
import com.letta.mobile.runtime.actions.MobileActionSensitivity
import com.letta.mobile.runtime.actions.MobileActionToolResponse
import com.letta.mobile.runtime.actions.MobileExternalToolHandler
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

class ScreenCaptureTool(
    private val provider: ScreenCaptureProvider,
    private val json: Json = Json { encodeDefaults = true; explicitNulls = false },
) : MobileExternalToolHandler {
    override val toolName: String = TOOL_NAME

    override fun handle(input: JsonObject, actionId: String): MobileActionToolResponse {
        val maxDimension = input["maxDimension"]?.jsonPrimitive?.intOrNull ?: AndroidScreenCaptureProvider.DEFAULT_MAX_DIMENSION
        return runCatching { provider.captureOwnWindow(maxDimension) }
            .fold(
                onSuccess = { capture ->
                    MobileActionToolResponse(
                        success = true,
                        toolName = toolName,
                        status = MobileActionCapabilityStatus.Available,
                        message = "Captured the current Letta app window.",
                        capabilityId = CAPABILITY_ID,
                        actionId = actionId,
                        data = json.encodeToJsonElement(ScreenCaptureResult.serializer(), capture) as JsonObject,
                    )
                },
                onFailure = { error ->
                    MobileActionToolResponse(
                        success = false,
                        toolName = toolName,
                        status = MobileActionCapabilityStatus.Error,
                        message = error.message ?: "Unable to capture the current app window.",
                        capabilityId = CAPABILITY_ID,
                        actionId = actionId,
                        error = "screen_capture_failed",
                    )
                },
            )
    }

    override fun capabilities(): List<MobileActionCapability> = listOf(
        MobileActionCapability(
            id = CAPABILITY_ID,
            toolName = TOOL_NAME,
            label = "Capture app screen",
            description = "Captures only the Letta app's current window hierarchy as a base64 PNG with width, height, and mimeType; full-screen or other-app capture requires future privileged MediaProjection/Shizuku escalation.",
            status = MobileActionCapabilityStatus.Available,
            riskTier = MobileActionRiskTier.Medium,
            sensitivity = MobileActionSensitivity.PersonalData,
            executionMode = MobileActionExecutionMode.Direct,
            defaultEnabled = true,
            reason = "Own-window capture reads the app's visible view hierarchy without Android screen-capture permission; arbitrary system or other-app screen capture is intentionally out of scope for this slice.",
        )
    )

    companion object {
        const val TOOL_NAME = "screen.capture"
        const val CAPABILITY_ID = "screen.capture.own_window"
    }
}
