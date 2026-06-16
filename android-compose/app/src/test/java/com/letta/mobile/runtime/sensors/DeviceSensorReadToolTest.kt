package com.letta.mobile.runtime.sensors

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSensorReadToolTest {
    private val provider = object : DeviceSensorSnapshotProvider {
        override fun snapshot(nowMillis: Long): DeviceSensorSnapshot = sampleSnapshot(nowMillis)
    }

    private val tool = DeviceSensorReadTool(provider)

    @Test
    fun `summary mode returns compact context without sensor descriptors`() {
        val response = Json.parseToJsonElement(tool.handleJson(buildJsonObject { put("mode", "summary") }, 100L)).jsonObject

        assertEquals("summary", response["mode"]!!.jsonPrimitive.content)
        assertEquals("2", response["sensorCount"]!!.jsonPrimitive.content)
        assertTrue(response["summary"]!!.jsonPrimitive.content.contains("sensors=2"))
        assertFalse(response.containsKey("sensors"))
    }

    @Test
    fun `catalog mode returns bounded sensor descriptors`() {
        val response = Json.parseToJsonElement(
            tool.handleJson(
                buildJsonObject {
                    put("mode", "catalog")
                    put("limit", "1")
                },
                100L,
            )
        ).jsonObject

        assertEquals("catalog", response["mode"]!!.jsonPrimitive.content)
        assertEquals("true", response["truncated"]!!.jsonPrimitive.content)
        val sensors = response["sensors"]!!.jsonArray
        assertEquals(1, sensors.size)
        assertEquals("android.sensor.accelerometer", sensors[0].jsonObject["stringType"]!!.jsonPrimitive.content)
    }

    @Test
    fun `sensor mode filters by query`() {
        val response = Json.parseToJsonElement(
            tool.handleJson(
                buildJsonObject {
                    put("mode", "sensor")
                    put("query", "light")
                },
                100L,
            )
        ).jsonObject

        val sensors = response["sensors"]!!.jsonArray
        assertEquals(1, sensors.size)
        assertEquals("android.sensor.light", sensors[0].jsonObject["stringType"]!!.jsonPrimitive.content)
    }

    @Test
    fun `sample mode reads live values from matched sensor`() {
        val sampleTool = DeviceSensorReadTool(
            provider = provider,
            sampler = object : DeviceSensorSampler {
                override fun sample(
                    descriptor: SensorDescriptor,
                    sampleCount: Int,
                    timeoutMs: Long,
                ): SensorSampleResponse = SensorSampleResponse(
                    status = "available",
                    sensor = descriptor,
                    samples = listOf(SensorSample(timestampNanos = 123L, accuracy = 3, values = listOf(1.5f, 2.5f))),
                    requestedSamples = sampleCount,
                    timeoutMs = timeoutMs,
                )
            },
        )
        val response = Json.parseToJsonElement(
            sampleTool.handleJson(
                buildJsonObject {
                    put("mode", "sample")
                    put("query", "light")
                    put("samples", "2")
                    put("timeoutMs", "1500")
                },
                100L,
            )
        ).jsonObject

        assertEquals("sample", response["mode"]!!.jsonPrimitive.content)
        assertEquals("false", response["truncated"]!!.jsonPrimitive.content)
        val sample = response["sample"]!!.jsonObject
        assertEquals("available", sample["status"]!!.jsonPrimitive.content)
        assertEquals("2", sample["requestedSamples"]!!.jsonPrimitive.content)
        assertEquals("1500", sample["timeoutMs"]!!.jsonPrimitive.content)
        assertEquals(1, sample["samples"]!!.jsonArray.size)
        assertEquals("android.sensor.light", sample["sensor"]!!.jsonObject["stringType"]!!.jsonPrimitive.content)
    }

    @Test
    fun `sample mode reports no_match when query misses catalog`() {
        val response = Json.parseToJsonElement(
            tool.handleJson(
                buildJsonObject {
                    put("mode", "sample")
                    put("query", "MLX90632")
                },
                100L,
            )
        ).jsonObject

        val sample = response["sample"]!!.jsonObject
        assertEquals("no_match", sample["status"]!!.jsonPrimitive.content)
        assertTrue(sample["error"]!!.jsonPrimitive.content.contains("No catalog sensor"))
    }

    @Test
    fun `snapshot mode returns bounded full snapshot`() {
        val response = Json.parseToJsonElement(
            tool.handleJson(
                buildJsonObject {
                    put("mode", "snapshot")
                    put("limit", "1")
                },
                100L,
            )
        ).jsonObject

        assertEquals("snapshot", response["mode"]!!.jsonPrimitive.content)
        assertEquals("true", response["truncated"]!!.jsonPrimitive.content)
        assertEquals("100", response["snapshot"]!!.jsonObject["capturedAtMillis"]!!.jsonPrimitive.content)
        assertEquals(1, response["snapshot"]!!.jsonObject["sensors"]!!.jsonArray.size)
    }

    @Test
    fun `unsupported mode reports error in stable shape`() {
        val response = Json.parseToJsonElement(tool.handleJson(buildJsonObject { put("mode", "raw") }, 100L)).jsonObject

        assertEquals("raw", response["mode"]!!.jsonPrimitive.content)
        assertTrue(response["error"]!!.jsonPrimitive.content.contains("Unsupported mode"))
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
            SensorDescriptor("Light", "Vendor", 5, "android.sensor.light", "on_change", false, 1f, 1f, 1f, 1, 1),
        ),
    )
}
