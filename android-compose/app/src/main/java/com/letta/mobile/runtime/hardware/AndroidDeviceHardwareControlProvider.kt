package com.letta.mobile.runtime.hardware

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import javax.inject.Inject

class AndroidDeviceHardwareControlProvider @Inject constructor(
    private val context: Context,
) : DeviceHardwareControlProvider {
    private val appContext = context.applicationContext

    override fun capabilities(): HardwareCapabilities = HardwareCapabilities(
        flashlight = flashlightCapability(),
        vibration = vibrationCapability(),
        audio = audioStatus(),
    )

    override fun setFlashlight(enabled: Boolean, dryRun: Boolean): HardwareControlResponse {
        val capability = flashlightCapability()
        if (!capability.supported) {
            return HardwareControlResponse(
                tool = DeviceHardwareControlTool.SET_FLASHLIGHT_TOOL_NAME,
                status = capability.status,
                supported = false,
                reason = capability.reason,
                flashlight = capability,
            )
        }
        if (dryRun) {
            return HardwareControlResponse(
                tool = DeviceHardwareControlTool.SET_FLASHLIGHT_TOOL_NAME,
                status = HardwareControlStatus.DryRun,
                supported = true,
                reason = "Flashlight capability probe succeeded; torch was not changed.",
                flashlight = capability,
            )
        }
        val cameraId = capability.cameraIdsWithFlash.firstOrNull()
            ?: return HardwareControlResponse(
                tool = DeviceHardwareControlTool.SET_FLASHLIGHT_TOOL_NAME,
                status = HardwareControlStatus.UnsupportedHardware,
                supported = false,
                reason = "No camera with flash is available.",
                flashlight = capability,
            )
        return runCatching {
            cameraManager().setTorchMode(cameraId, enabled)
            HardwareControlResponse(
                tool = DeviceHardwareControlTool.SET_FLASHLIGHT_TOOL_NAME,
                status = HardwareControlStatus.Success,
                supported = true,
                reason = if (enabled) "Flashlight enabled." else "Flashlight disabled.",
                flashlight = capability,
            )
        }.getOrElse { error ->
            HardwareControlResponse(
                tool = DeviceHardwareControlTool.SET_FLASHLIGHT_TOOL_NAME,
                status = HardwareControlStatus.BlockedByAndroidPolicy,
                supported = true,
                reason = error.message ?: "Android blocked torch control.",
                flashlight = capability,
            )
        }
    }

    override fun vibrate(durationMs: Long?, patternMs: List<Long>?): HardwareControlResponse {
        val capability = vibrationCapability()
        if (!capability.supported) {
            return HardwareControlResponse(
                tool = DeviceHardwareControlTool.VIBRATE_TOOL_NAME,
                status = capability.status,
                supported = false,
                reason = capability.reason,
            )
        }
        val clampedPattern = patternMs?.take(MAX_PATTERN_SEGMENTS)?.map { it.coerceIn(0L, MAX_PATTERN_SEGMENT_MS) }
        val clampedDuration = durationMs?.coerceIn(1L, MAX_VIBRATION_MS)
        val effect = if (!clampedPattern.isNullOrEmpty()) {
            VibrationEffect.createWaveform(clampedPattern.toLongArray(), -1)
        } else {
            VibrationEffect.createOneShot(clampedDuration ?: DEFAULT_VIBRATION_MS, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                manager.defaultVibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                val vibrator = appContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(effect)
            }
            HardwareControlResponse(
                tool = DeviceHardwareControlTool.VIBRATE_TOOL_NAME,
                status = HardwareControlStatus.Success,
                supported = true,
                reason = "Vibration started with safe clamped limits.",
                vibration = VibrationResult(
                    requestedDurationMs = durationMs,
                    actualDurationMs = if (clampedPattern.isNullOrEmpty()) clampedDuration ?: DEFAULT_VIBRATION_MS else null,
                    requestedPatternMs = patternMs,
                    actualPatternMs = clampedPattern,
                ),
            )
        }.getOrElse { error ->
            HardwareControlResponse(
                tool = DeviceHardwareControlTool.VIBRATE_TOOL_NAME,
                status = HardwareControlStatus.BlockedByAndroidPolicy,
                supported = true,
                reason = error.message ?: "Android blocked vibration.",
            )
        }
    }

    override fun readAudioStatus(): HardwareControlResponse {
        val audio = audioStatus()
        return HardwareControlResponse(
            tool = DeviceHardwareControlTool.AUDIO_STATUS_TOOL_NAME,
            status = audio.status,
            supported = audio.status == HardwareControlStatus.Available,
            reason = audio.reason,
            audio = audio,
        )
    }

    override fun adjustMusicVolume(delta: Int?, level: Int?): HardwareControlResponse {
        val audioManager = audioManagerOrNull()
            ?: return HardwareControlResponse(
                tool = DeviceHardwareControlTool.ADJUST_MUSIC_VOLUME_TOOL_NAME,
                status = HardwareControlStatus.NotAvailable,
                supported = false,
                reason = "AudioManager is not available.",
                audio = audioStatus(),
            )
        val before = audioStatus()
        if (before.fixedVolume == true) {
            return HardwareControlResponse(
                tool = DeviceHardwareControlTool.ADJUST_MUSIC_VOLUME_TOOL_NAME,
                status = HardwareControlStatus.BlockedByAndroidPolicy,
                supported = false,
                reason = "Android reports fixed volume; music volume cannot be adjusted.",
                audio = before,
            )
        }
        val max = before.maxMusicVolume ?: 0
        val current = before.currentMusicVolume ?: 0
        val target = when {
            level != null -> level.coerceIn(0, max)
            delta != null -> (current + delta.coerceIn(-MAX_VOLUME_DELTA, MAX_VOLUME_DELTA)).coerceIn(0, max)
            else -> return HardwareControlResponse(
                tool = DeviceHardwareControlTool.ADJUST_MUSIC_VOLUME_TOOL_NAME,
                status = HardwareControlStatus.InvalidInput,
                supported = true,
                reason = "Provide either delta or level.",
                audio = before,
            )
        }
        return runCatching {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            val after = audioStatus()
            HardwareControlResponse(
                tool = DeviceHardwareControlTool.ADJUST_MUSIC_VOLUME_TOOL_NAME,
                status = HardwareControlStatus.Success,
                supported = true,
                reason = "Music volume adjusted within safe bounds.",
                audio = after,
            )
        }.getOrElse { error ->
            HardwareControlResponse(
                tool = DeviceHardwareControlTool.ADJUST_MUSIC_VOLUME_TOOL_NAME,
                status = HardwareControlStatus.BlockedByAndroidPolicy,
                supported = true,
                reason = error.message ?: "Android policy blocked volume adjustment.",
                audio = before,
            )
        }
    }

    private fun flashlightCapability(): FlashlightCapability {
        if (!appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
            return FlashlightCapability(
                status = HardwareControlStatus.UnsupportedHardware,
                supported = false,
                reason = "Device does not report FEATURE_CAMERA_FLASH.",
            )
        }
        val ids = runCatching {
            cameraManager().cameraIdList.filter { id ->
                cameraManager().getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrElse {
            return FlashlightCapability(
                status = HardwareControlStatus.NotAvailable,
                supported = false,
                reason = "CameraManager could not enumerate flash-capable cameras.",
            )
        }
        return if (ids.isEmpty()) {
            FlashlightCapability(
                status = HardwareControlStatus.UnsupportedHardware,
                supported = false,
                cameraIdsWithFlash = emptyList(),
                reason = "No camera IDs expose FLASH_INFO_AVAILABLE.",
            )
        } else {
            FlashlightCapability(
                status = HardwareControlStatus.Available,
                supported = true,
                cameraIdsWithFlash = ids,
                reason = "Flash-capable camera IDs are available.",
            )
        }
    }

    private fun vibrationCapability(): VibrationCapability {
        val hasVibrator = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator.hasVibrator()
            } else {
                @Suppress("DEPRECATION")
                (appContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).hasVibrator()
            }
        }.getOrDefault(false)
        return if (hasVibrator) {
            VibrationCapability(HardwareControlStatus.Available, supported = true, reason = "Device vibrator is available.")
        } else {
            VibrationCapability(HardwareControlStatus.UnsupportedHardware, supported = false, reason = "Device does not expose a vibrator.")
        }
    }

    private fun audioStatus(): AudioStatus {
        val manager = audioManagerOrNull()
            ?: return AudioStatus(HardwareControlStatus.NotAvailable, reason = "AudioManager is not available.")
        return runCatching {
            AudioStatus(
                status = HardwareControlStatus.Available,
                currentMusicVolume = manager.getStreamVolume(AudioManager.STREAM_MUSIC),
                maxMusicVolume = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                ringerMode = when (manager.ringerMode) {
                    AudioManager.RINGER_MODE_SILENT -> "silent"
                    AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                    AudioManager.RINGER_MODE_NORMAL -> "normal"
                    else -> "unknown"
                },
                fixedVolume = manager.isVolumeFixed,
                reason = "Audio status is available without extra permissions.",
            )
        }.getOrElse { error ->
            AudioStatus(HardwareControlStatus.BlockedByAndroidPolicy, reason = error.message ?: "Android blocked audio status.")
        }
    }

    private fun cameraManager(): CameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private fun audioManagerOrNull(): AudioManager? = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    companion object {
        const val MAX_VIBRATION_MS = 1_000L
        const val DEFAULT_VIBRATION_MS = 100L
        const val MAX_PATTERN_SEGMENT_MS = 500L
        const val MAX_PATTERN_SEGMENTS = 8
        const val MAX_VOLUME_DELTA = 3
    }
}
