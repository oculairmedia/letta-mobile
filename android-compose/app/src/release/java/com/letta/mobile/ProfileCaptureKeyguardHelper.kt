package com.letta.mobile

import android.app.Activity
import android.os.Build
import android.view.WindowManager

/** Enables keyguard rendering only for the generated Baseline Profile target. */
object ProfileCaptureKeyguardHelper {
    fun allowProfileCaptureLaunch(activity: Activity) {
        if (BuildConfig.BUILD_TYPE != "nonMinifiedRelease") {
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
        } else {
            // letta-mobile-2y33: FLAG_SHOW_WHEN_LOCKED is deprecated since API 27.
            // This branch runs pre-O_MR1 (API < 27) so the new API
            // (setShowWhenLocked) is not available here.
            @Suppress("DEPRECATION")
            activity.window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
    }
}
