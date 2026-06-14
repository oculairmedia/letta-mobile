package com.letta.mobile.runtime.sensors

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class DeviceSensorReadTool(
    private val provider: DeviceSensorSnapshotProvider,
    private val json: Json = Json { encodeDefaults = true; explicitNulls = false },
) {
    fun handle(input: JsonObject, nowMillis: Long = System.currentTimeMillis()): ReadSensorsToolResponse {
        val snapshot = provider.snapshot(nowMillis)
        val mode = input.string("mode")?.lowercase()?.takeIf { it.isNotBlank() } ?: "summary"
        val query = input.string("query")?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        val limit = input.string("limit")?.toIntOrNull()?.coerceIn(1, MAX_SENSOR_RESULTS) ?: DEFAULT_SENSOR_LIMIT
        return when (mode) {
            "summary" -> ReadSensorsToolResponse(
                mode = mode,
                summary = DeviceSensorSnapshotFormatter.toCompactString(snapshot),
                sensorCount = snapshot.sensorCount,
            )
            "catalog" -> ReadSensorsToolResponse(
                mode = mode,
                summary = DeviceSensorSnapshotFormatter.toCompactString(snapshot),
                sensorCount = snapshot.sensorCount,
                sensors = snapshot.sensors.filterSensors(query).take(limit),
                truncated = snapshot.sensors.filterSensors(query).size > limit,
            )
            "sensor" -> {
                val matching = snapshot.sensors.filterSensors(query).take(limit)
                ReadSensorsToolResponse(
                    mode = mode,
                    summary = DeviceSensorSnapshotFormatter.toCompactString(snapshot),
                    sensorCount = snapshot.sensorCount,
                    sensors = matching,
                    truncated = snapshot.sensors.filterSensors(query).size > limit,
                )
            }
            "snapshot" -> ReadSensorsToolResponse(
                mode = mode,
                summary = DeviceSensorSnapshotFormatter.toCompactString(snapshot),
                sensorCount = snapshot.sensorCount,
                snapshot = snapshot.copy(sensors = snapshot.sensors.take(limit)),
                truncated = snapshot.sensors.size > limit,
            )
            else -> ReadSensorsToolResponse(
                mode = mode,
                summary = DeviceSensorSnapshotFormatter.toCompactString(snapshot),
                sensorCount = snapshot.sensorCount,
                error = "Unsupported mode '$mode'. Use summary, catalog, sensor, or snapshot.",
            )
        }
    }

    fun handleJson(input: JsonObject, nowMillis: Long = System.currentTimeMillis()): String =
        json.encodeToString(handle(input, nowMillis))

    private fun List<SensorDescriptor>.filterSensors(query: String?): List<SensorDescriptor> =
        if (query == null) {
            this
        } else {
            filter { sensor ->
                sensor.name.lowercase().contains(query) ||
                    sensor.vendor.lowercase().contains(query) ||
                    sensor.stringType.lowercase().contains(query) ||
                    sensor.type.toString() == query
            }
        }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    companion object {
        const val TOOL_NAME = "read_sensors"
        const val DEFAULT_SENSOR_LIMIT = 64
        const val MAX_SENSOR_RESULTS = 256
    }
}

@Serializable
data class ReadSensorsToolResponse(
    val mode: String,
    val summary: String,
    val sensorCount: Int,
    val sensors: List<SensorDescriptor>? = null,
    val snapshot: DeviceSensorSnapshot? = null,
    val truncated: Boolean = false,
    val error: String? = null,
)
