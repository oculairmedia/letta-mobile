package com.letta.mobile.runtime.sensors

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSensorSnapshotFormatterTest {
    @Test
    fun `compact string includes bounded no-permission device context`() {
        val snapshot = sampleSnapshot()
        val compact = DeviceSensorSnapshotFormatter.toCompactString(snapshot)

        assertTrue(compact.contains("🔋72%⚡"))
        assertTrue(compact.contains("🌡️31.5°C"))
        assertTrue(compact.contains("thermal=none"))
        assertTrue(compact.contains("🧠50%"))
        assertTrue(compact.contains("💿75%"))
        assertTrue(compact.contains("📶wifi+vpn"))
        assertTrue(compact.contains("↻portrait/90"))
        assertTrue(compact.contains("sensors=2"))
    }

    @Test
    fun `json shape is stable and machine readable`() {
        val json = DeviceSensorSnapshotFormatter.toJson(sampleSnapshot())
        val obj = Json.parseToJsonElement(json).jsonObject

        assertEquals("1234", obj["capturedAtMillis"]!!.jsonPrimitive.content)
        assertEquals("72", obj["battery"]!!.jsonObject["levelPercent"]!!.jsonPrimitive.content)
        assertEquals("none", obj["thermal"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        val sensors = obj["sensors"]!!.jsonArray
        assertEquals("android.sensor.accelerometer", sensors[0].jsonObject["stringType"]!!.jsonPrimitive.content)
        assertEquals("android.sensor.light", sensors[1].jsonObject["stringType"]!!.jsonPrimitive.content)
    }

    @Test
    fun `json string helper escapes summary for self-test payload`() {
        val encoded = DeviceSensorSnapshotFormatter.toJsonString("a \"quoted\" summary")
        assertEquals("\"a \\\"quoted\\\" summary\"", encoded)
    }

    private fun sampleSnapshot(): DeviceSensorSnapshot = DeviceSensorSnapshot(
        capturedAtMillis = 1234L,
        battery = BatterySnapshot(
            levelPercent = 72,
            isCharging = true,
            chargePlug = "usb",
            temperatureCelsius = 31.5f,
            voltageMillivolts = 4012,
        ),
        thermal = ThermalSnapshot("none"),
        memory = MemorySnapshot(
            availableBytes = 1_000L,
            totalBytes = 2_000L,
            lowMemory = false,
        ),
        storage = StorageSnapshot(
            availableBytes = 250L,
            totalBytes = 1_000L,
        ),
        network = NetworkSnapshot(
            isConnected = true,
            transportTypes = listOf("wifi", "vpn"),
            isMetered = false,
        ),
        display = DisplaySnapshot(
            rotation = "90",
            orientation = "portrait",
        ),
        sensors = listOf(
            SensorDescriptor(
                name = "Accel",
                vendor = "Vendor",
                type = 1,
                stringType = "android.sensor.accelerometer",
                reportingMode = "continuous",
                isWakeUpSensor = false,
                maxRange = 39.2f,
                resolution = 0.01f,
                powerMilliAmps = 0.2f,
                minDelayMicros = 5_000,
                maxDelayMicros = 100_000,
            ),
            SensorDescriptor(
                name = "Light",
                vendor = "Vendor",
                type = 5,
                stringType = "android.sensor.light",
                reportingMode = "on_change",
                isWakeUpSensor = false,
                maxRange = 10_000f,
                resolution = 1f,
                powerMilliAmps = 0.1f,
                minDelayMicros = 0,
                maxDelayMicros = 0,
            ),
        ),
    )
}
