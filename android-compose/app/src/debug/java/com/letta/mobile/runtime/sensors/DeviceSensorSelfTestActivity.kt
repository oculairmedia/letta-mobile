package com.letta.mobile.runtime.sensors

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.letta.mobile.BuildConfig
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.put

/**
 * Dev-only no-display Activity for ADB validation of Stage 1→2 without user
 * turns. Activity starts are permitted from shell even when the app is stopped;
 * it writes machine-readable output then immediately finishes.
 *
 * Usage:
 *   adb shell am start \
 *     -n com.letta.mobile.dev/com.letta.mobile.runtime.sensors.DeviceSensorSelfTestActivity
 *   adb shell run-as com.letta.mobile.dev cat files/device-sensor-self-test.json
 */
class DeviceSensorSelfTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG) {
            runCatching {
                val snapshot = AndroidDeviceSensorSnapshotProvider(applicationContext).snapshot()
                val payload = DeviceSensorSelfTestService.buildSelfTestPayload(snapshot)
                val out = File(filesDir, DeviceSensorSelfTestReceiver.OUTPUT_FILE)
                out.writeText(payload)
                val grounding = File(filesDir, DeviceSensorGroundingWriter.FILE_NAME)
                runBlocking {
                    DeviceSensorGroundingWriter(
                        provider = object : DeviceSensorSnapshotProvider {
                            override fun snapshot(nowMillis: Long): DeviceSensorSnapshot = snapshot
                        },
                        outputFile = grounding,
                    ).writeSnapshot(snapshot.capturedAtMillis)
                }
                val bridgeOut = File(filesDir, READ_SENSORS_OUTPUT_FILE)
                val toolResponse = DeviceSensorReadTool(
                    provider = object : DeviceSensorSnapshotProvider {
                        override fun snapshot(nowMillis: Long): DeviceSensorSnapshot = snapshot
                    },
                ).handleJson(kotlinx.serialization.json.buildJsonObject { put("mode", "summary") }, snapshot.capturedAtMillis)
                bridgeOut.writeText(toolResponse)
                Log.w(
                    TAG,
                    "[device-sensor-self-test] wrote ${out.absolutePath}, ${grounding.absolutePath}, " +
                        "and ${bridgeOut.absolutePath} sensors=${snapshot.sensorCount} " +
                        "summary=${DeviceSensorSnapshotFormatter.toCompactString(snapshot)}",
                )
            }.onFailure { error ->
                Log.e(TAG, "[device-sensor-self-test] failed", error)
            }
        }
        finish()
        overridePendingTransition(0, 0)
    }


    companion object {
        const val READ_SENSORS_OUTPUT_FILE = "device-sensor-read-tool.json"
        private const val TAG = "DeviceSensorSelfTest"
    }
}
