package com.letta.mobile.runtime.hardware

import kotlinx.serialization.Serializable

@Serializable
enum class HardwareControlStatus {
    Available,
    Success,
    DryRun,
    UnsupportedHardware,
    BlockedByAndroidPolicy,
    NotAvailable,
    InvalidInput,
    Failed,
}

@Serializable
data class FlashlightCapability(
    val status: HardwareControlStatus,
    val supported: Boolean,
    val cameraIdsWithFlash: List<String> = emptyList(),
    val reason: String,
)

@Serializable
data class VibrationCapability(
    val status: HardwareControlStatus,
    val supported: Boolean,
    val reason: String,
)

@Serializable
data class AudioStatus(
    val status: HardwareControlStatus,
    val currentMusicVolume: Int? = null,
    val maxMusicVolume: Int? = null,
    val ringerMode: String? = null,
    val fixedVolume: Boolean? = null,
    val reason: String,
)

@Serializable
data class HardwareCapabilities(
    val flashlight: FlashlightCapability,
    val vibration: VibrationCapability,
    val audio: AudioStatus,
)

@Serializable
data class HardwareControlResponse(
    val tool: String,
    val status: HardwareControlStatus,
    val supported: Boolean,
    val reason: String,
    val capabilities: HardwareCapabilities? = null,
    val flashlight: FlashlightCapability? = null,
    val vibration: VibrationResult? = null,
    val audio: AudioStatus? = null,
)

@Serializable
data class VibrationResult(
    val requestedDurationMs: Long? = null,
    val actualDurationMs: Long? = null,
    val requestedPatternMs: List<Long>? = null,
    val actualPatternMs: List<Long>? = null,
)

interface DeviceHardwareControlProvider {
    fun capabilities(): HardwareCapabilities
    fun setFlashlight(enabled: Boolean, dryRun: Boolean = false): HardwareControlResponse
    fun vibrate(durationMs: Long?, patternMs: List<Long>?): HardwareControlResponse
    fun readAudioStatus(): HardwareControlResponse
    fun adjustMusicVolume(delta: Int?, level: Int?): HardwareControlResponse
}
