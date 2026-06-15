package com.letta.mobile.runtime.mobileactions

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File

/**
 * ADB usage:
 *   adb shell am broadcast -a com.letta.mobile.MOBILE_INTENT_ACTIONS_SELF_TEST com.letta.mobile.dev
 *   adb shell run-as com.letta.mobile.dev cat files/mobile-intent-actions-self-test.json
 */
class MobileIntentActionsSelfTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        Thread {
            try {
                val report = MobileIntentActionsSelfTest.run(context.applicationContext)
                Log.i(TAG, "[mobile-intent-actions-self-test] wrote ${File(context.filesDir, REPORT_FILE).absolutePath} passed=${report.passed}")
            } catch (error: Exception) {
                Log.e(TAG, "[mobile-intent-actions-self-test] failed", error)
            } finally {
                result.finish()
            }
        }.start()
    }

    companion object {
        const val REPORT_FILE = "mobile-intent-actions-self-test.json"
        private const val TAG = "MobileIntentActionsST"
    }
}
