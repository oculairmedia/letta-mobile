package com.letta.mobile.runtime.local

import androidx.test.core.app.ApplicationProvider
import com.letta.mobile.runtime.actions.DeviceActionCommandRunner
import com.letta.mobile.runtime.actions.InMemoryMobileActionAuditSink
import com.letta.mobile.runtime.actions.MobileActionRegistry
import com.letta.mobile.runtime.hardware.AudioStatus
import com.letta.mobile.runtime.hardware.DeviceHardwareControlProvider
import com.letta.mobile.runtime.hardware.DeviceHardwareControlTool
import com.letta.mobile.runtime.hardware.FlashlightCapability
import com.letta.mobile.runtime.hardware.HardwareCapabilities
import com.letta.mobile.runtime.hardware.HardwareControlResponse
import com.letta.mobile.runtime.hardware.HardwareControlStatus
import com.letta.mobile.runtime.hardware.VibrationCapability
import com.letta.mobile.runtime.mobileactions.AndroidProviderReadTool
import com.letta.mobile.runtime.mobileactions.MobileIntentActionTool
import com.letta.mobile.runtime.sensors.BatterySnapshot
import com.letta.mobile.runtime.sensors.DeviceSensorSnapshot
import com.letta.mobile.runtime.sensors.DeviceSensorSnapshotProvider
import com.letta.mobile.runtime.sensors.MemorySnapshot
import com.letta.mobile.runtime.sensors.NetworkSnapshot
import com.letta.mobile.runtime.sensors.SensorDescriptor
import com.letta.mobile.runtime.sensors.StorageSnapshot
import com.letta.mobile.runtime.sensors.ThermalSnapshot
import java.io.OutputStream
import java.net.Socket
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AndroidNetworkBridgeReadSensorsTest {
    @Test
    fun `device sensors endpoint returns read_sensors response`() {
        val bridge = LocalAndroidNetworkBridge(
            sensorSnapshotProvider = object : DeviceSensorSnapshotProvider {
                override fun snapshot(nowMillis: Long): DeviceSensorSnapshot = sampleSnapshot(nowMillis)
            },
            mobileActionRegistry = mobileActionRegistry(),
            mobileIntentActionTool = mobileIntentTool(),
            hardwareControlProvider = fakeHardwareProvider(),
            deviceActionCommandRunner = commandRunner(),
        )

        bridge.start().use { session ->
            val response = post(session.baseUrl, session.authToken, "/device/sensors/read", "{\"mode\":\"summary\"}")
            val body = response.substringAfter("\r\n\r\n")
            val obj = Json.parseToJsonElement(body).jsonObject
            assertEquals("summary", obj["mode"]!!.jsonPrimitive.content)
            assertEquals("1", obj["sensorCount"]!!.jsonPrimitive.content)
            assertTrue(obj["summary"]!!.jsonPrimitive.content.contains("sensors=1"))
        }
    }

    @Test
    fun `mobile action endpoint returns truthful dry-run response`() {
        val bridge = LocalAndroidNetworkBridge(
            sensorSnapshotProvider = object : DeviceSensorSnapshotProvider {
                override fun snapshot(nowMillis: Long): DeviceSensorSnapshot = sampleSnapshot(nowMillis)
            },
            mobileActionRegistry = mobileActionRegistry(),
            mobileIntentActionTool = mobileIntentTool(),
            hardwareControlProvider = fakeHardwareProvider(),
            deviceActionCommandRunner = commandRunner(),
        )

        bridge.start().use { session ->
            val response = post(
                session.baseUrl,
                session.authToken,
                "/device/mobile-actions/intent",
                "{\"tool\":\"compose_email\",\"to\":\"ada@example.com\",\"subject\":\"Hi\",\"body\":\"Body\",\"dryRun\":true}",
            )
            val body = response.substringAfter("\r\n\r\n")
            val obj = Json.parseToJsonElement(body).jsonObject
            assertEquals("compose_email", obj["tool"]!!.jsonPrimitive.content)
            // dry-run reports intent CONSTRUCTION; the resolving status is environment-dependent
            // (CI emulators have no email app → "no_handler"; a device with one → "resolved").
            // Both are valid dry-run outcomes; intent/userActionRequired/launched are stable either way.
            assertTrue(obj["status"]!!.jsonPrimitive.content in setOf("resolved", "no_handler"))
            assertEquals("true", obj["userActionRequired"]!!.jsonPrimitive.content)
            assertEquals("false", obj["launched"]!!.jsonPrimitive.content)
            assertEquals("android.intent.action.SENDTO", obj["intent"]!!.jsonObject["action"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `device action command endpoint returns command envelope`() {
        val bridge = LocalAndroidNetworkBridge(
            sensorSnapshotProvider = sensorProvider(),
            mobileActionRegistry = mobileActionRegistry(),
            mobileIntentActionTool = mobileIntentTool(),
            hardwareControlProvider = fakeHardwareProvider(),
            deviceActionCommandRunner = commandRunner(),
        )

        bridge.start().use { session ->
            val response = post(session.baseUrl, session.authToken, "/device/actions/command", "{\"command\":\"sensors.summary\"}")
            val body = response.substringAfter("\r\n\r\n")
            val obj = Json.parseToJsonElement(body).jsonObject
            assertEquals("sensors.summary", obj["command"]!!.jsonPrimitive.content)
            assertEquals("true", obj["success"]!!.jsonPrimitive.content)
            assertEquals("summary", obj["payload"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `bridge rejects missing and wrong auth token`() {
        val bridge = LocalAndroidNetworkBridge(
            sensorSnapshotProvider = sensorProvider(),
            mobileActionRegistry = mobileActionRegistry(),
            mobileIntentActionTool = mobileIntentTool(),
            hardwareControlProvider = fakeHardwareProvider(),
            deviceActionCommandRunner = commandRunner(),
        )

        bridge.start().use { session ->
            val missing = post(session.baseUrl, null, "/device/actions/command", "{\"command\":\"sensors.summary\"}")
            val wrong = post(session.baseUrl, "wrong-token", "/device/actions/command", "{\"command\":\"sensors.summary\"}")
            assertTrue(missing.startsWith("HTTP/1.1 401"))
            assertTrue(wrong.startsWith("HTTP/1.1 401"))
        }
    }

    private fun post(baseUrl: String, authToken: String?, path: String, body: String): String {
        val uri = java.net.URI(baseUrl)
        Socket(uri.host, uri.port).use { socket ->
            val bytes = body.toByteArray(Charsets.UTF_8)
            val request = buildString {
                append("POST $path HTTP/1.1\r\n")
                append("Host: ${uri.host}:${uri.port}\r\n")
                append("Content-Type: application/json\r\n")
                authToken?.let { append("Authorization: Bearer $it\r\n") }
                append("Content-Length: ${bytes.size}\r\n")
                append("Connection: close\r\n\r\n")
            }
            val out: OutputStream = socket.getOutputStream()
            out.write(request.toByteArray(Charsets.UTF_8))
            out.write(bytes)
            out.flush()
            return socket.getInputStream().bufferedReader().use { it.readText() }
        }
    }

    private fun sensorProvider(): DeviceSensorSnapshotProvider = object : DeviceSensorSnapshotProvider {
        override fun snapshot(nowMillis: Long): DeviceSensorSnapshot = sampleSnapshot(nowMillis)
    }

    private fun mobileActionRegistry(): MobileActionRegistry =
        MobileActionRegistry(emptySet(), emptySet(), InMemoryMobileActionAuditSink())

    private fun mobileIntentTool(): MobileIntentActionTool =
        MobileIntentActionTool(ApplicationProvider.getApplicationContext())

    private fun commandRunner(): DeviceActionCommandRunner = DeviceActionCommandRunner(
        sensorReadTool = com.letta.mobile.runtime.sensors.DeviceSensorReadTool(sensorProvider()),
        mobileActionRegistry = mobileActionRegistry(),
        mobileIntentActionTool = mobileIntentTool(),
        hardwareControlTool = DeviceHardwareControlTool(fakeHardwareProvider()),
        providerReadTool = AndroidProviderReadTool(ApplicationProvider.getApplicationContext()),
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
}
