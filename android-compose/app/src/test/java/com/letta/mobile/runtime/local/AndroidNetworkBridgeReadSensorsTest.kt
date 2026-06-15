package com.letta.mobile.runtime.local

import androidx.test.core.app.ApplicationProvider
import com.letta.mobile.runtime.actions.InMemoryMobileActionAuditSink
import com.letta.mobile.runtime.actions.MobileActionRegistry
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
            mobileActionRegistry = MobileActionRegistry(emptySet(), emptySet(), InMemoryMobileActionAuditSink()),
            mobileIntentActionTool = MobileIntentActionTool(ApplicationProvider.getApplicationContext()),
        )

        bridge.start().use { session ->
            val response = post(session.baseUrl, "/device/sensors/read", "{\"mode\":\"summary\"}")
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
            mobileActionRegistry = MobileActionRegistry(emptySet(), emptySet(), InMemoryMobileActionAuditSink()),
            mobileIntentActionTool = MobileIntentActionTool(ApplicationProvider.getApplicationContext()),
        )

        bridge.start().use { session ->
            val response = post(
                session.baseUrl,
                "/device/mobile-actions/intent",
                "{\"tool\":\"compose_email\",\"to\":\"ada@example.com\",\"subject\":\"Hi\",\"body\":\"Body\",\"dryRun\":true}",
            )
            val body = response.substringAfter("\r\n\r\n")
            val obj = Json.parseToJsonElement(body).jsonObject
            assertEquals("compose_email", obj["tool"]!!.jsonPrimitive.content)
            assertTrue(obj["status"]!!.jsonPrimitive.content in setOf("resolved", "not_resolved"))
            assertEquals("true", obj["userActionRequired"]!!.jsonPrimitive.content)
            assertEquals("false", obj["launched"]!!.jsonPrimitive.content)
            assertEquals("android.intent.action.SENDTO", obj["intent"]!!.jsonObject["action"]!!.jsonPrimitive.content)
        }
    }

    private fun post(baseUrl: String, path: String, body: String): String {
        val uri = java.net.URI(baseUrl)
        Socket(uri.host, uri.port).use { socket ->
            val bytes = body.toByteArray(Charsets.UTF_8)
            val request = buildString {
                append("POST $path HTTP/1.1\r\n")
                append("Host: ${uri.host}:${uri.port}\r\n")
                append("Content-Type: application/json\r\n")
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
