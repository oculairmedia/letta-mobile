package com.letta.mobile.runtime.actions

import com.letta.mobile.runtime.hardware.DeviceHardwareControlTool
import com.letta.mobile.runtime.mobileactions.MobileIntentActionTool
import com.letta.mobile.runtime.sensors.DeviceSensorReadTool
import javax.inject.Inject
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DeviceActionCommandRunner @Inject constructor(
    private val sensorReadTool: DeviceSensorReadTool,
    private val mobileActionRegistry: MobileActionRegistry,
    private val mobileIntentActionTool: MobileIntentActionTool,
    private val hardwareControlTool: DeviceHardwareControlTool,
) {
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    fun runJson(commandJson: String): String = json.encodeToString(run(commandJson))

    fun run(commandJson: String): DeviceActionCommandResult {
        val root = runCatching { json.parseToJsonElement(commandJson).jsonObject }.getOrElse { error ->
            return errorResult("unknown", "invalid_json", error.message ?: "Command is not valid JSON.")
        }
        val command = root.string("command")?.trim().orEmpty()
        val input = root["input"]?.jsonObject ?: JsonObject(emptyMap())
        return when (command) {
            "device.catalog" -> ok(command, DeviceActionCommandCatalog.toJson())
            "sensors.summary" -> ok(command, sensorReadTool.handleJson(jsonObject("mode" to "summary")))
            "sensors.catalog" -> ok(command, sensorReadTool.handleJson(input.withDefaultMode("catalog")))
            "sensors.snapshot" -> ok(command, sensorReadTool.handleJson(input.withDefaultMode("snapshot")))
            "mobile.capabilities" -> ok(command, mobileActionRegistry.matrixJson())
            "intent.dry_run" -> ok(command, mobileIntentActionTool.handleJson(input.withDryRun()))
            "hardware.capabilities" -> ok(command, hardwareControlTool.capabilitiesJson())
            "hardware.flashlight" -> runFlashlightCommand(command, input)
            "hardware.flashlight_on" -> ok(command, hardwareControlTool.setFlashlightJson(input.withDefaults(mapOf("enabled" to true, "dryRun" to false))))
            "hardware.flashlight_off" -> ok(command, hardwareControlTool.setFlashlightJson(input.withDefaults(mapOf("enabled" to false, "dryRun" to false))))
            "set_flashlight" -> runFlashlightCommand(command, input)
            "hardware.flashlight_probe" -> ok(
                command,
                hardwareControlTool.setFlashlightJson(
                    input.withDefaults(mapOf("enabled" to false, "dryRun" to true))
                ),
            )
            "hardware.vibrate" -> ok(command, hardwareControlTool.vibrateJson(input))
            "hardware.audio_status" -> ok(command, hardwareControlTool.audioStatusJson())
            else -> errorResult(command.ifBlank { "unknown" }, "unknown_command", "Unknown device action command: $command")
        }
    }

    private fun runFlashlightCommand(command: String, input: JsonObject): DeviceActionCommandResult {
        if (input["enabled"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == null) {
            return errorResult(command, "invalid_input", "hardware.flashlight requires input.enabled true or false.")
        }
        return ok(command, hardwareControlTool.setFlashlightJson(input.withDefaults(mapOf("dryRun" to false))))
    }

    private fun ok(command: String, payloadJson: String): DeviceActionCommandResult {
        val payload = runCatching { json.parseToJsonElement(payloadJson).jsonObject }.getOrElse { error ->
            return errorResult(command, "invalid_payload", error.message ?: "Command payload was not valid JSON.")
        }
        return DeviceActionCommandResult(command = command, success = true, payload = payload)
    }

    private fun errorResult(command: String, code: String, message: String): DeviceActionCommandResult =
        DeviceActionCommandResult(command = command, success = false, error = DeviceActionCommandError(code, message))

    private fun JsonObject.withDefaultMode(mode: String): JsonObject =
        if (containsKey("mode")) this else withDefaults(mapOf("mode" to mode))

    private fun JsonObject.withDryRun(): JsonObject = withDefaults(mapOf("dryRun" to true))

    private fun JsonObject.withDefaults(defaults: Map<String, Any>): JsonObject = kotlinx.serialization.json.buildJsonObject {
        defaults.forEach { (key, value) ->
            when (value) {
                is Boolean -> put(key, kotlinx.serialization.json.JsonPrimitive(value))
                is Number -> put(key, kotlinx.serialization.json.JsonPrimitive(value))
                else -> put(key, kotlinx.serialization.json.JsonPrimitive(value.toString()))
            }
        }
        this@withDefaults.forEach { (key, value) -> put(key, value) }
    }

    private fun jsonObject(vararg values: Pair<String, String>): JsonObject = kotlinx.serialization.json.buildJsonObject {
        values.forEach { (key, value) -> put(key, kotlinx.serialization.json.JsonPrimitive(value)) }
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
}

@Serializable
data class DeviceActionCommandResult(
    val command: String,
    val success: Boolean,
    val payload: JsonObject? = null,
    val error: DeviceActionCommandError? = null,
)

@Serializable
data class DeviceActionCommandError(
    val code: String,
    val message: String,
)
