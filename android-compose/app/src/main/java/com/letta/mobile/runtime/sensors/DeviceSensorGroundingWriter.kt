package com.letta.mobile.runtime.sensors

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Writes the latest no-permission device context to a small file consumed by
 * the embedded runtime-introspection preload. File handoff keeps the canonical
 * introspection injection in JS while Android owns raw sensor access.
 */
class DeviceSensorGroundingWriter(
    private val provider: DeviceSensorSnapshotProvider,
    val outputFile: File,
    private val json: Json = Json { encodeDefaults = true; explicitNulls = false },
) {
    suspend fun writeSnapshot(nowMillis: Long = System.currentTimeMillis()): GroundingWriteReport =
        withContext(Dispatchers.IO) {
            val snapshot = provider.snapshot(nowMillis)
            val payload = DeviceSensorGroundingPayload(
                summary = DeviceSensorSnapshotFormatter.toCompactString(snapshot),
                snapshot = snapshot,
            )
            val encoded = json.encodeToString(payload)
            outputFile.parentFile?.mkdirs()
            val tmp = File(outputFile.parentFile, "${outputFile.name}.tmp")
            tmp.writeText(encoded)

            Files.move(tmp.toPath(), outputFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)

            GroundingWriteReport(
                file = outputFile,
                bytes = encoded.toByteArray(Charsets.UTF_8).size,
                sensorCount = snapshot.sensorCount,
                summary = payload.summary,
            )
        }

    @Serializable
    data class DeviceSensorGroundingPayload(
        val summary: String,
        val snapshot: DeviceSensorSnapshot,
    )

    data class GroundingWriteReport(
        val file: File,
        val bytes: Int,
        val sensorCount: Int,
        val summary: String,
    )

    companion object {
        const val FILE_NAME = "device-sensor-grounding.json"
    }
}
