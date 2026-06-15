package com.letta.mobile.runtime.sensors

import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeviceSensorGroundingWriterTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `writes compact summary plus full snapshot to grounding file`() = runTest {
        val out = File(temp.root, "device-sensor-grounding.json")
        val writer = DeviceSensorGroundingWriter(
            provider = object : DeviceSensorSnapshotProvider {
                override fun snapshot(nowMillis: Long): DeviceSensorSnapshot = sampleSnapshot(nowMillis)
            },
            outputFile = out,
        )

        val report = writer.writeSnapshot(nowMillis = 42L)

        assertTrue(out.isFile)
        assertEquals(1, report.sensorCount)
        assertTrue(report.summary.contains("sensors=1"))
        val payload = Json.parseToJsonElement(out.readText()).jsonObject
        assertEquals(report.summary, payload["summary"]!!.jsonPrimitive.content)
        val snapshot = payload["snapshot"]!!.jsonObject
        assertEquals("42", snapshot["capturedAtMillis"]!!.jsonPrimitive.content)
        assertEquals("android.sensor.accelerometer", snapshot["sensors"]!!.jsonArray[0].jsonObject["stringType"]!!.jsonPrimitive.content)
    }

    private fun sampleSnapshot(now: Long): DeviceSensorSnapshot = DeviceSensorSnapshot(
        capturedAtMillis = now,
        battery = BatterySnapshot(
            levelPercent = 50,
            isCharging = false,
            chargePlug = null,
            temperatureCelsius = 30f,
            voltageMillivolts = 4000,
        ),
        thermal = ThermalSnapshot("none"),
        memory = MemorySnapshot(availableBytes = 500, totalBytes = 1000, lowMemory = false),
        storage = StorageSnapshot(availableBytes = 500, totalBytes = 1000),
        network = NetworkSnapshot(isConnected = true, transportTypes = listOf("wifi"), isMetered = false),
        sensors = listOf(
            SensorDescriptor(
                name = "Accel",
                vendor = "Vendor",
                type = 1,
                stringType = "android.sensor.accelerometer",
                reportingMode = "continuous",
                isWakeUpSensor = false,
                maxRange = 1f,
                resolution = 1f,
                powerMilliAmps = 1f,
                minDelayMicros = 1,
                maxDelayMicros = 1,
            ),
        ),
    )
}
