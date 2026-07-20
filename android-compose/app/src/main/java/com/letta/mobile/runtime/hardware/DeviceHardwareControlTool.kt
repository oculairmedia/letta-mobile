package com.letta.mobile.runtime.hardware

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class DeviceHardwareControlTool(
    private val provider: DeviceHardwareControlProvider,
    private val json: Json = Json { encodeDefaults = true; explicitNulls = false },
) {
    fun capabilitiesJson(): String = json.encodeToString(provider.capabilities())

    fun setFlashlightJson(input: JsonObject): String = json.encodeToString(
        provider.setFlashlight(
            enabled = input.boolean("enabled") ?: false,
            dryRun = input.boolean("dryRun") ?: false,
        )
    )

    fun vibrateJson(input: JsonObject): String = json.encodeToString(
        provider.vibrate(
            durationMs = input.long("durationMs"),
            patternMs = input.longArray("patternMs") ?: input.longArray("pattern"),
        )
    )

    fun audioStatusJson(): String = json.encodeToString(provider.readAudioStatus())

    fun adjustMusicVolumeJson(input: JsonObject): String = json.encodeToString(
        provider.adjustMusicVolume(
            delta = input.int("delta"),
            level = input.int("level"),
        )
    )

    private fun JsonObject.boolean(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull

    private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull

    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

    private fun JsonObject.longArray(key: String): List<Long>? {
        val element: JsonElement = this[key] ?: return null
        return (element as? JsonArray)?.mapNotNull { item ->
            item.jsonPrimitive.longOrNull ?: item.jsonPrimitive.contentOrNull?.toLongOrNull()
        }
    }

    companion object {
        const val SET_FLASHLIGHT_TOOL_NAME = "set_flashlight"
        const val VIBRATE_TOOL_NAME = "vibrate"
        const val AUDIO_STATUS_TOOL_NAME = "audio_status"
        const val ADJUST_MUSIC_VOLUME_TOOL_NAME = "adjust_music_volume"
    }
}
