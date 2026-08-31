package com.letta.mobile.runtime.actions

import com.letta.mobile.data.controller.capability.Capability
import com.letta.mobile.data.controller.extras.ExternalToolResult
import com.letta.mobile.data.controller.extras.HostExternalTool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

fun interface DeviceActionCommandExecutor {
    fun runJson(commandJson: String): String
}

/** Exposes the existing Android command runner to the App Server external-tool loop. */
class DeviceActionExternalTool(
    private val executor: DeviceActionCommandExecutor,
) : HostExternalTool {
    constructor(runner: DeviceActionCommandRunner) : this(DeviceActionCommandExecutor(runner::runJson))
    override val name: String = NAME
    override val description: String =
        "Run a command from the Android device_action catalog on the phone that owns this turn. " +
            "Call device.catalog first to discover the current commands and input hints."
    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("command") { put("type", "string") }
            putJsonObject("input") { put("type", "object") }
        }
        put("required", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("command")) })
        put("additionalProperties", false)
    }
    // HostExternalTool bypasses endpoint capability filtering; this value is never used as a gate.
    override val capability: Capability = Capability.ImageHydration

    override suspend fun invoke(input: JsonObject, agentId: String?): ExternalToolResult =
        runCatching { executor.runJson(input.toString()) }.fold(
            onSuccess = { content ->
                val result = runCatching {
                    kotlinx.serialization.json.Json.parseToJsonElement(content).jsonObject
                }.getOrElse { return ExternalToolResult.Error("Device action returned invalid JSON: ${it.message}") }
                if (result["success"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true) {
                    ExternalToolResult.Success(content)
                } else {
                    ExternalToolResult.Error(result["error"]?.toString() ?: "Device action failed.")
                }
            },
            onFailure = { ExternalToolResult.Error("Device action failed: ${it.message}") },
        )

    companion object {
        const val NAME = "device_action"
    }
}
