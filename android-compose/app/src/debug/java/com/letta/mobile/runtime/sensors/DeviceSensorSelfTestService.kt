package com.letta.mobile.runtime.sensors

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.letta.mobile.BuildConfig
import java.io.File

/**
 * Dev-only service entrypoint for ADB validation of Stage 1→2 without user
 * turns. A service is more reliable than a manifest broadcast receiver on newer
 * Android background-delivery policies.
 *
 * Usage:
 *   adb shell am startservice \
 *     -n com.letta.mobile.dev/com.letta.mobile.runtime.sensors.DeviceSensorSelfTestService
 *   adb shell run-as com.letta.mobile.dev cat files/device-sensor-self-test.json
 */
class DeviceSensorSelfTestService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!BuildConfig.DEBUG) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        runCatching {
            val snapshot = AndroidDeviceSensorSnapshotProvider(applicationContext).snapshot()
            val payload = buildSelfTestPayload(snapshot)
            val out = File(filesDir, DeviceSensorSelfTestReceiver.OUTPUT_FILE)
            out.writeText(payload)
            Log.w(
                TAG,
                "[device-sensor-self-test] wrote ${out.absolutePath} " +
                    "sensors=${snapshot.sensorCount} summary=${DeviceSensorSnapshotFormatter.toCompactString(snapshot)}",
            )
        }.onFailure { error ->
            Log.e(TAG, "[device-sensor-self-test] failed", error)
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "DeviceSensorSelfTest"

        fun buildSelfTestPayload(snapshot: DeviceSensorSnapshot): String = buildString {
            append("{\"summary\":")
            append(DeviceSensorSnapshotFormatter.toJsonString(DeviceSensorSnapshotFormatter.toCompactString(snapshot)))
            append(",\"snapshot\":")
            append(DeviceSensorSnapshotFormatter.toJson(snapshot))
            append("}")
        }
    }
}
