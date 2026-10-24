package com.letta.mobile.runtime.mobileactions

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File

/**
 * ADB usage:
 *   adb shell am broadcast -a com.letta.mobile.MOBILE_INTENT_ACTIONS_SELF_TEST ca.oculair.meridian.dev
 *   adb shell run-as ca.oculair.meridian.dev cat files/mobile-intent-actions-self-test.json
 */
class MobileIntentActionsSelfTestReceiver : BroadcastReceiver() {
    // Report failures from any self-test stage without crashing the debug receiver thread.
    @Suppress("TooGenericExceptionCaught")
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        Thread {
            try {
                val report = MobileIntentActionsSelfTest.run(context.applicationContext)
                Log.i(
                    TAG,
                    "[mobile-intent-actions-self-test] wrote ${File(context.filesDir, REPORT_FILE).absolutePath} " +
                        "passed=${report.passed}",
                )
            } catch (error: Exception) {
                logSelfTestFailure(error)
            } finally {
                result.finish()
            }
        }.start()
    }

    private fun logSelfTestFailure(error: Exception) {
        Log.e(TAG, "[mobile-intent-actions-self-test] failed", error)
    }

    companion object {
        const val REPORT_FILE = "mobile-intent-actions-self-test.json"
        private const val TAG = "MobileIntentActionsST"
    }
}
