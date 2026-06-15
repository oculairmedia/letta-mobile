package com.letta.mobile.runtime.actions

import androidx.test.core.app.ApplicationProvider
import com.letta.mobile.runtime.hardware.AudioStatus
import com.letta.mobile.runtime.hardware.DeviceHardwareControlProvider
import com.letta.mobile.runtime.hardware.DeviceHardwareControlTool
import com.letta.mobile.runtime.hardware.FlashlightCapability
import com.letta.mobile.runtime.hardware.HardwareCapabilities
import com.letta.mobile.runtime.hardware.HardwareControlResponse
import com.letta.mobile.runtime.hardware.HardwareControlStatus
import com.letta.mobile.runtime.hardware.VibrationCapability
import com.letta.mobile.runtime.mobileactions.MobileIntentActionTool
import com.letta.mobile.runtime.sensors.BatterySnapshot
import com.letta.mobile.runtime.sensors.DeviceSensorReadTool
import com.letta.mobile.runtime.sensors.DeviceSensorSnapshot
import com.letta.mobile.runtime.sensors.DeviceSensorSnapshotProvider
import com.letta.mobile.runtime.sensors.MemorySnapshot
import com.letta.mobile.runtime.sensors.NetworkSnapshot
import com.letta.mobile.runtime.sensors.SensorDescriptor
import com.letta.mobile.runtime.sensors.StorageSnapshot
import com.letta.mobile.runtime.sensors.ThermalSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class DeviceActionCommandRunnerTest {
    private val runner = DeviceActionCommandRunner(
        sensorReadTool = DeviceSensorReadTool(object : DeviceSensorSnapshotProvider {
            override fun snapshot(nowMillis: Long): DeviceSensorSnapshot = sampleSnapshot(nowMillis)
        }),
        mobileActionRegistry = MobileActionRegistry(emptySet(), emptySet(), InMemoryMobileActionAuditSink()),
        mobileIntentActionTool = MobileIntentActionTool(ApplicationProvider.getApplicationContext()),
        hardwareControlTool = DeviceHardwareControlTool(fakeHardwareProvider()),
    )

    @Test
    fun `sensors summary returns command envelope with payload`() {
        val result = Json.parseToJsonElement(runner.runJson("""{"command":"sensors.summary"}""")).jsonObject

        assertEquals("sensors.summary", result["command"]!!.jsonPrimitive.content)
        assertEquals("true", result["success"]!!.jsonPrimitive.content)
        assertEquals("summary", result["payload"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
        assertEquals("1", result["payload"]!!.jsonObject["sensorCount"]!!.jsonPrimitive.content)
    }

    @Test
    fun `intent dry run forces dryRun true`() {
        val result = Json.parseToJsonElement(
            runner.runJson(
                """{"command":"intent.dry_run","input":{"tool":"compose_email","to":"ada@example.com"}}"""
            )
        ).jsonObject

        assertEquals("true", result["success"]!!.jsonPrimitive.content)
        val payload = result["payload"]!!.jsonObject
        assertEquals("compose_email", payload["tool"]!!.jsonPrimitive.content)
        assertEquals("true", payload["dryRun"]!!.jsonPrimitive.content)
        assertEquals("false", payload["launched"]!!.jsonPrimitive.content)
    }

    @Test
    fun `hardware capabilities routes to hardware tool`() {
        val result = Json.parseToJsonElement(runner.runJson("""{"command":"hardware.capabilities"}""")).jsonObject

        assertEquals("true", result["success"]!!.jsonPrimitive.content)
        assertTrue(result["payload"]!!.jsonObject.containsKey("flashlight"))
        assertTrue(result["payload"]!!.jsonObject.containsKey("audio"))
    }

    @Test
    fun `unknown command returns stable error envelope`() {
        val result = Json.parseToJsonElement(runner.runJson("""{"command":"nope"}""")).jsonObject

        assertEquals("nope", result["command"]!!.jsonPrimitive.content)
        assertEquals("false", result["success"]!!.jsonPrimitive.content)
        assertEquals("unknown_command", result["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        assertFalse(result.containsKey("payload"))
    }

    private fun sampleSnapshot(now: Long): DeviceSensorSnapshot = DeviceSensorSnapshot(
        capturedAtMillis = now,
        battery = BatterySnapshot(80, false, null, 31f, 4000),
        thermal = ThermalSnapshot("none"),
        memory = MemorySnapshot(availableBytes = 500, totalBytes = 1000, lowMemory = false),
        storage = StorageSnapshot(availableBytes = 750, totalBytes = 1000),
        network = NetworkSnapshot(isConnected = true, transportTypes = listOf("wifi"), isMetered = false),
        sensors = listOf(
            SensorDescriptor("Accel", "Vendor", 1, "android.sensor.accelerometer", "continuous", false, 1f, 1f, 1f, 1, 1),
        ),
    )

    private fun fakeHardwareProvider(): DeviceHardwareControlProvider = object : DeviceHardwareControlProvider {
        private val caps = HardwareCapabilities(
            flashlight = FlashlightCapability(HardwareControlStatus.UnsupportedHardware, false, reason = "test"),
            vibration = VibrationCapability(HardwareControlStatus.UnsupportedHardware, false, reason = "test"),
            audio = AudioStatus(HardwareControlStatus.Available, currentMusicVolume = 1, maxMusicVolume = 10, ringerMode = "normal", fixedVolume = false, reason = "test"),
        )

        override fun capabilities(): HardwareCapabilities = caps
        override fun setFlashlight(enabled: Boolean, dryRun: Boolean): HardwareControlResponse =
            HardwareControlResponse("set_flashlight", HardwareControlStatus.UnsupportedHardware, false, "test", flashlight = caps.flashlight)
        override fun vibrate(durationMs: Long?, patternMs: List<Long>?): HardwareControlResponse =
            HardwareControlResponse("vibrate", HardwareControlStatus.UnsupportedHardware, false, "test")
        override fun readAudioStatus(): HardwareControlResponse =
            HardwareControlResponse("audio_status", HardwareControlStatus.Available, true, "test", audio = caps.audio)
        override fun adjustMusicVolume(delta: Int?, level: Int?): HardwareControlResponse =
            HardwareControlResponse("adjust_music_volume", HardwareControlStatus.Success, true, "test", audio = caps.audio)
    }
}
