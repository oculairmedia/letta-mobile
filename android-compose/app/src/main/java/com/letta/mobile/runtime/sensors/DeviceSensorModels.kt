package com.letta.mobile.runtime.sensors

import kotlinx.serialization.Serializable

@Serializable
data class DeviceSensorSnapshot(
    val capturedAtMillis: Long,
    val battery: BatterySnapshot? = null,
    val thermal: ThermalSnapshot? = null,
    val memory: MemorySnapshot? = null,
    val storage: StorageSnapshot? = null,
    val network: NetworkSnapshot? = null,
    val display: DisplaySnapshot? = null,
    val sensors: List<SensorDescriptor> = emptyList(),
    val gatedCapabilities: List<DeviceSensorGatedCapability> = emptyList(),
) {
    val sensorCount: Int get() = sensors.size
}

@Serializable
data class BatterySnapshot(
    val levelPercent: Int?,
    val isCharging: Boolean,
    val chargePlug: String?,
    val temperatureCelsius: Float?,
    val voltageMillivolts: Int?,
)

@Serializable
data class ThermalSnapshot(
    val status: String,
)

@Serializable
data class MemorySnapshot(
    val availableBytes: Long,
    val totalBytes: Long,
    val lowMemory: Boolean,
) {
    val usedPercent: Int get() =
        if (totalBytes <= 0L) 0 else (((totalBytes - availableBytes).coerceAtLeast(0L) * 100L) / totalBytes).toInt()
}

@Serializable
data class StorageSnapshot(
    val availableBytes: Long,
    val totalBytes: Long,
) {
    val usedPercent: Int get() =
        if (totalBytes <= 0L) 0 else (((totalBytes - availableBytes).coerceAtLeast(0L) * 100L) / totalBytes).toInt()
}

@Serializable
data class NetworkSnapshot(
    val isConnected: Boolean,
    val transportTypes: List<String> = emptyList(),
    val isMetered: Boolean? = null,
)

@Serializable
data class DisplaySnapshot(
    val rotation: String,
    val orientation: String,
)

@Serializable
data class SensorDescriptor(
    val name: String,
    val vendor: String,
    val type: Int,
    val stringType: String,
    val reportingMode: String,
    val isWakeUpSensor: Boolean,
    val maxRange: Float,
    val resolution: Float,
    val powerMilliAmps: Float,
    val minDelayMicros: Int,
    val maxDelayMicros: Int,
)

@Serializable
data class SensorSampleResponse(
    val status: String,
    val sensor: SensorDescriptor? = null,
    val samples: List<SensorSample> = emptyList(),
    val requestedSamples: Int = 1,
    val timeoutMs: Long? = null,
    val error: String? = null,
)

@Serializable
data class SensorSample(
    val timestampNanos: Long,
    val accuracy: Int,
    val values: List<Float>,
)
