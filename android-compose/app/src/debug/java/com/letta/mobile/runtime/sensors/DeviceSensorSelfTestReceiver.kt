package com.letta.mobile.runtime.sensors

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.letta.mobile.BuildConfig
import java.io.File

/**
 * Dev-only ADB hook for validating Stage 1→2 of the device sensor pipeline
 * without relying on user turns.
 *
 * Usage:
 *   adb shell am broadcast -a com.letta.mobile.DEVICE_SENSOR_SELF_TEST \
 *     -n com.letta.mobile.dev/com.letta.mobile.runtime.sensors.DeviceSensorSelfTestReceiver
 *   adb shell run-as com.letta.mobile.dev cat files/device-sensor-self-test.json
 */
class DeviceSensorSelfTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG || intent.action != ACTION) return
        val snapshot = AndroidDeviceSensorSnapshotProvider(context.applicationContext).snapshot()
        val payload = DeviceSensorSelfTestService.buildSelfTestPayload(snapshot)
        val out = File(context.filesDir, OUTPUT_FILE)
        out.writeText(payload)
        Log.w(
            TAG,
            "[device-sensor-self-test] wrote ${out.absolutePath} " +
                "sensors=${snapshot.sensorCount} summary=${DeviceSensorSnapshotFormatter.toCompactString(snapshot)}",
        )
    }

    companion object {
        const val ACTION = "com.letta.mobile.DEVICE_SENSOR_SELF_TEST"
        const val OUTPUT_FILE = "device-sensor-self-test.json"
        private const val TAG = "DeviceSensorSelfTest"
    }
}
