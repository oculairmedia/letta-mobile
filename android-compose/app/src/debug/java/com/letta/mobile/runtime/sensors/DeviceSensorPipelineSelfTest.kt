package com.letta.mobile.runtime.sensors

import android.content.Context
import com.letta.mobile.runtime.actions.AndroidMobileActionCapabilityProvider
import com.letta.mobile.runtime.actions.InMemoryMobileActionAuditSink
import com.letta.mobile.runtime.actions.MobileActionRegistry
import com.letta.mobile.runtime.hardware.AndroidDeviceHardwareControlProvider
import com.letta.mobile.runtime.hardware.DeviceHardwareControlTool
import com.letta.mobile.runtime.hardware.HardwareControlStatus
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

object DeviceSensorPipelineSelfTest {
    const val REPORT_FILE = "device-sensor-pipeline-report.json"
    const val ACTION_CAPABILITY_MATRIX_FILE = "mobile-action-capability-matrix.json"

    private val json = Json { encodeDefaults = true; explicitNulls = false; prettyPrint = true }

    fun run(context: Context): PipelineSelfTestReport {
        val filesDir = context.filesDir
        val stages = mutableListOf<PipelineStageResult>()

        val provider = AndroidDeviceSensorSnapshotProvider(context.applicationContext)
        val snapshot = provider.snapshot()
        val summary = DeviceSensorSnapshotFormatter.toCompactString(snapshot)
        stages += stage(
            id = "stage1.raw_android_providers",
            passed = snapshot.sensorCount > 0,
            details = "Enumerated ${snapshot.sensorCount} sensors via SensorManager.TYPE_ALL.",
            artifact = null,
        )
        stages += stage(
            id = "stage2.typed_snapshot",
            passed = snapshot.capturedAtMillis > 0 && snapshot.battery != null && snapshot.memory != null,
            details = "Typed snapshot has battery=${snapshot.battery != null}, memory=${snapshot.memory != null}, network=${snapshot.network != null}.",
            artifact = null,
        )

        val snapshotOut = File(filesDir, DeviceSensorSelfTestReceiver.OUTPUT_FILE)
        snapshotOut.writeText(DeviceSensorSelfTestService.buildSelfTestPayload(snapshot))

        val groundingOut = File(filesDir, DeviceSensorGroundingWriter.FILE_NAME)
        val groundingReport = runBlocking {
            DeviceSensorGroundingWriter(
                provider = object : DeviceSensorSnapshotProvider {
                    override fun snapshot(nowMillis: Long): DeviceSensorSnapshot = snapshot
                },
                outputFile = groundingOut,
            ).writeSnapshot(snapshot.capturedAtMillis)
        }
        val groundingPayload = runCatching { json.parseToJsonElement(groundingOut.readText()).jsonObject }.getOrNull()
        stages += stage(
            id = "stage3.passive_grounding_file",
            passed = groundingOut.isFile && groundingPayload?.get("summary")?.jsonPrimitive?.content == groundingReport.summary,
            details = "Wrote passive grounding summary (${groundingReport.bytes}B): ${groundingReport.summary}",
            artifact = groundingOut.name,
        )

        val readToolOut = File(filesDir, DeviceSensorSelfTestActivity.READ_SENSORS_OUTPUT_FILE)
        val readToolText = DeviceSensorReadTool(
            provider = object : DeviceSensorSnapshotProvider {
                override fun snapshot(nowMillis: Long): DeviceSensorSnapshot = snapshot
            },
        ).handleJson(buildJsonObject { put("mode", "summary") }, snapshot.capturedAtMillis)
        readToolOut.writeText(readToolText)
        val readToolPayload = runCatching { json.parseToJsonElement(readToolText).jsonObject }.getOrNull()
        stages += stage(
            id = "stage4.active_read_sensors_shape",
            passed = readToolPayload?.get("sensorCount")?.jsonPrimitive?.content?.toIntOrNull() == snapshot.sensorCount,
            details = "read_sensors summary returned sensorCount=${readToolPayload?.get("sensorCount")?.jsonPrimitive?.content}.",
            artifact = readToolOut.name,
        )

        val actionMatrixOut = File(filesDir, ACTION_CAPABILITY_MATRIX_FILE)
        val actionRegistry = MobileActionRegistry(
            providers = setOf(AndroidMobileActionCapabilityProvider(context.applicationContext)),
            handlers = emptySet(),
            auditSink = InMemoryMobileActionAuditSink(),
        )
        actionMatrixOut.writeText(json.encodeToString(actionRegistry.matrix()))
        val actionMatrixPayload = runCatching { json.parseToJsonElement(actionMatrixOut.readText()).jsonObject }.getOrNull()
        stages += stage(
            id = "stage5.mobile_action_capability_matrix",
            passed = actionMatrixPayload?.get("platform")?.jsonPrimitive?.content == "android" &&
                actionRegistry.matrix().capabilities.isNotEmpty(),
            details = "Wrote ${actionRegistry.matrix().capabilities.size} mobile action capability descriptors for future tools.",
            artifact = actionMatrixOut.name,
        )

        val hardwareToolOut = File(filesDir, DeviceSensorSelfTestActivity.HARDWARE_CONTROLS_OUTPUT_FILE)
        val hardwareTool = DeviceHardwareControlTool(AndroidDeviceHardwareControlProvider(context.applicationContext))
        val capabilitiesText = hardwareTool.capabilitiesJson()
        val vibrationText = hardwareTool.vibrateJson(buildJsonObject { put("durationMs", 100) })
        val flashlightProbeText = hardwareTool.setFlashlightJson(
            buildJsonObject {
                put("enabled", false)
                put("dryRun", true)
            }
        )
        hardwareToolOut.writeText(
            """{"capabilities":$capabilitiesText,"vibration":$vibrationText,"flashlightProbe":$flashlightProbeText}"""
        )
        val vibrationPayload = runCatching { json.parseToJsonElement(vibrationText).jsonObject }.getOrNull()
        val flashlightPayload = runCatching { json.parseToJsonElement(flashlightProbeText).jsonObject }.getOrNull()
        val safeVibration = vibrationPayload?.get("status")?.jsonPrimitive?.content in setOf(
            HardwareControlStatus.Success.name,
            HardwareControlStatus.UnsupportedHardware.name,
            HardwareControlStatus.BlockedByAndroidPolicy.name,
        )
        val dryRunFlashlight = flashlightPayload?.get("status")?.jsonPrimitive?.content in setOf(
            HardwareControlStatus.DryRun.name,
            HardwareControlStatus.UnsupportedHardware.name,
            HardwareControlStatus.NotAvailable.name,
            HardwareControlStatus.BlockedByAndroidPolicy.name,
        )
        stages += stage(
            id = "stage6.hardware_controls_safe_probe",
            passed = safeVibration && dryRunFlashlight,
            details = "Hardware controls probed; vibration status=${vibrationPayload?.get("status")?.jsonPrimitive?.content}, flashlight status=${flashlightPayload?.get("status")?.jsonPrimitive?.content}.",
            artifact = hardwareToolOut.name,
        )

        val preloadArtifact = "letta-code/nodejs-project/embedded-runtime-introspection-preload.cjs"
        val preloadText = runCatching {
            context.assets.open(preloadArtifact).bufferedReader().use { it.readText() }
        }.getOrElse {
            File(filesDir, "embedded-lettacode/nodejs-project/embedded-runtime-introspection-preload.cjs")
                .takeIf { file -> file.isFile }
                ?.readText()
                .orEmpty()
        }
        stages += stage(
            id = "stage6.passive_transport_preload",
            passed = preloadText.contains("LETTA_MOBILE_DEVICE_SENSOR_GROUNDING_PATH") && preloadText.contains("Device context:"),
            details = "Embedded packaged preload includes device grounding file transport and Device context injection.",
            artifact = preloadArtifact,
        )
        stages += stage(
            id = "stage7.active_tool_transport_preload",
            passed = preloadText.contains("read_sensors") && preloadText.contains("/device/sensors/read") && preloadText.contains("@letta/externalTools"),
            details = "Embedded preload registers read_sensors external tool and routes to Android bridge endpoint.",
            artifact = preloadArtifact,
        )
        stages += stage(
            id = "stage8.hardware_tool_transport_preload",
            passed = preloadText.contains("set_flashlight") && preloadText.contains("/device/hardware/set_flashlight") && preloadText.contains("audio_status"),
            details = "Embedded preload registers hardware control tools and routes to Android bridge endpoints.",
            artifact = preloadArtifact,
        )

        val report = PipelineSelfTestReport(
            passed = stages.all { it.passed },
            summary = summary,
            sensorCount = snapshot.sensorCount,
            stages = stages,
            artifacts = listOf(
                snapshotOut.name,
                groundingOut.name,
                readToolOut.name,
                actionMatrixOut.name,
                hardwareToolOut.name,
                REPORT_FILE,
            ),
        )
        File(filesDir, REPORT_FILE).writeText(json.encodeToString(report))
        return report
    }

    private fun stage(id: String, passed: Boolean, details: String, artifact: String?): PipelineStageResult =
        PipelineStageResult(id = id, passed = passed, details = details, artifact = artifact)
}

@kotlinx.serialization.Serializable
data class PipelineSelfTestReport(
    val passed: Boolean,
    val summary: String,
    val sensorCount: Int,
    val stages: List<PipelineStageResult>,
    val artifacts: List<String>,
)

@kotlinx.serialization.Serializable
data class PipelineStageResult(
    val id: String,
    val passed: Boolean,
    val details: String,
    val artifact: String? = null,
)
