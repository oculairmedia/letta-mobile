package com.letta.mobile.runtime.hardware

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceHardwareControlToolTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `capabilities reports exact supported and policy shape`() {
        val payload = json.parseToJsonElement(DeviceHardwareControlTool(FakeHardwareProvider()).capabilitiesJson()).jsonObject

        assertEquals("Available", payload["flashlight"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals("true", payload["flashlight"]!!.jsonObject["supported"]!!.jsonPrimitive.content)
        assertEquals("0", payload["flashlight"]!!.jsonObject["cameraIdsWithFlash"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("Available", payload["vibration"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals("4", payload["audio"]!!.jsonObject["currentMusicVolume"]!!.jsonPrimitive.content)
        assertEquals("normal", payload["audio"]!!.jsonObject["ringerMode"]!!.jsonPrimitive.content)
    }

    @Test
    fun `set flashlight dry run keeps action honest`() {
        val provider = FakeHardwareProvider()
        val text = DeviceHardwareControlTool(provider).setFlashlightJson(
            buildJsonObject {
                put("enabled", true)
                put("dryRun", true)
            }
        )
        val payload = json.parseToJsonElement(text).jsonObject

        assertEquals("set_flashlight", payload["tool"]!!.jsonPrimitive.content)
        assertEquals("DryRun", payload["status"]!!.jsonPrimitive.content)
        assertEquals("true", payload["supported"]!!.jsonPrimitive.content)
        assertFalse(provider.flashlightChanged)
    }

    @Test
    fun `vibrate clamps fake provider response shape`() {
        val text = DeviceHardwareControlTool(FakeHardwareProvider()).vibrateJson(
            buildJsonObject {
                putJsonArray("patternMs") {
                    add(0)
                    add(800)
                    add(25)
                }
            }
        )
        val payload = json.parseToJsonElement(text).jsonObject
        val vibration = payload["vibration"]!!.jsonObject

        assertEquals("vibrate", payload["tool"]!!.jsonPrimitive.content)
        assertEquals("Success", payload["status"]!!.jsonPrimitive.content)
        assertEquals("500", vibration["actualPatternMs"]!!.jsonArray[1].jsonPrimitive.content)
    }

    @Test
    fun `unsupported hardware status is explicit`() {
        val text = DeviceHardwareControlTool(FakeHardwareProvider(supportsFlashlight = false)).setFlashlightJson(
            buildJsonObject { put("enabled", true) }
        )
        val payload = json.parseToJsonElement(text).jsonObject

        assertEquals("UnsupportedHardware", payload["status"]!!.jsonPrimitive.content)
        assertEquals("false", payload["supported"]!!.jsonPrimitive.content)
        assertTrue(payload["reason"]!!.jsonPrimitive.content.contains("No fake flash"))
    }

    @Test
    fun `audio adjustment reports blocked policy`() {
        val text = DeviceHardwareControlTool(FakeHardwareProvider(fixedVolume = true)).adjustMusicVolumeJson(
            buildJsonObject { put("delta", 1) }
        )
        val payload = json.parseToJsonElement(text).jsonObject

        assertEquals("adjust_music_volume", payload["tool"]!!.jsonPrimitive.content)
        assertEquals("BlockedByAndroidPolicy", payload["status"]!!.jsonPrimitive.content)
        assertEquals("true", payload["audio"]!!.jsonObject["fixedVolume"]!!.jsonPrimitive.content)
    }

    private class FakeHardwareProvider(
        private val supportsFlashlight: Boolean = true,
        private val fixedVolume: Boolean = false,
    ) : DeviceHardwareControlProvider {
        var flashlightChanged = false

        override fun capabilities(): HardwareCapabilities = HardwareCapabilities(
            flashlight = flashlightCapability(),
            vibration = VibrationCapability(HardwareControlStatus.Available, true, "Fake vibrator available."),
            audio = audioStatus(),
        )

        override fun setFlashlight(enabled: Boolean, dryRun: Boolean): HardwareControlResponse {
            val capability = flashlightCapability()
            if (!capability.supported) {
                return HardwareControlResponse("set_flashlight", capability.status, false, capability.reason, flashlight = capability)
            }
            if (dryRun) {
                return HardwareControlResponse("set_flashlight", HardwareControlStatus.DryRun, true, "Fake dry run.", flashlight = capability)
            }
            flashlightChanged = true
            return HardwareControlResponse("set_flashlight", HardwareControlStatus.Success, true, "Fake flashlight changed.", flashlight = capability)
        }

        override fun vibrate(durationMs: Long?, patternMs: List<Long>?): HardwareControlResponse = HardwareControlResponse(
            tool = "vibrate",
            status = HardwareControlStatus.Success,
            supported = true,
            reason = "Fake vibration started.",
            vibration = VibrationResult(
                requestedDurationMs = durationMs,
                actualDurationMs = durationMs?.coerceIn(1L, 1_000L),
                requestedPatternMs = patternMs,
                actualPatternMs = patternMs?.take(8)?.map { it.coerceIn(0L, 500L) },
            ),
        )

        override fun readAudioStatus(): HardwareControlResponse = HardwareControlResponse(
            tool = "audio_status",
            status = HardwareControlStatus.Available,
            supported = true,
            reason = "Fake audio status.",
            audio = audioStatus(),
        )

        override fun adjustMusicVolume(delta: Int?, level: Int?): HardwareControlResponse {
            val audio = audioStatus()
            return if (fixedVolume) {
                HardwareControlResponse("adjust_music_volume", HardwareControlStatus.BlockedByAndroidPolicy, false, "Fixed volume policy.", audio = audio)
            } else {
                HardwareControlResponse("adjust_music_volume", HardwareControlStatus.Success, true, "Fake volume adjusted.", audio = audio)
            }
        }

        private fun flashlightCapability(): FlashlightCapability = if (supportsFlashlight) {
            FlashlightCapability(HardwareControlStatus.Available, true, listOf("0"), "Fake flash available.")
        } else {
            FlashlightCapability(HardwareControlStatus.UnsupportedHardware, false, reason = "No fake flash hardware.")
        }

        private fun audioStatus(): AudioStatus = AudioStatus(
            status = HardwareControlStatus.Available,
            currentMusicVolume = 4,
            maxMusicVolume = 10,
            ringerMode = "normal",
            fixedVolume = fixedVolume,
            reason = "Fake audio status.",
        )
    }
}
