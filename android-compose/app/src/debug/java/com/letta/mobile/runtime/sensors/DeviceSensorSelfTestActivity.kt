package com.letta.mobile.runtime.sensors

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.letta.mobile.BuildConfig

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
                val report = DeviceSensorPipelineSelfTest.run(applicationContext)
                Log.w(
                    TAG,
                    "[device-sensor-self-test] report=${DeviceSensorPipelineSelfTest.REPORT_FILE} " +
                        "passed=${report.passed} sensors=${report.sensorCount} summary=${report.summary}",
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
        const val HARDWARE_CONTROLS_OUTPUT_FILE = "device-hardware-controls-self-test.json"
        private const val TAG = "DeviceSensorSelfTest"
    }
}
