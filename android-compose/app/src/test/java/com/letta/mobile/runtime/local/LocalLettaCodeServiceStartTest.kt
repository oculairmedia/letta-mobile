package com.letta.mobile.runtime.local

import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the notification-visibility decision used by
 * [LocalLettaCodeService.start]. POST_NOTIFICATIONS being denied on Android 13+
 * must only affect notification VISIBILITY, never gate the local runtime launch
 * (letta-mobile-uzzd8).
 */
class LocalLettaCodeServiceStartTest {

    @Test
    fun preTiramisuAlwaysVisibleRegardlessOfPermission() {
        assertTrue(
            LocalLettaCodeService.notificationsWillBeVisible(
                sdkInt = Build.VERSION_CODES.S,
                postNotificationsGranted = false,
            ),
        )
        assertTrue(
            LocalLettaCodeService.notificationsWillBeVisible(
                sdkInt = Build.VERSION_CODES.S,
                postNotificationsGranted = true,
            ),
        )
    }

    @Test
    fun tiramisuWithGrantedPermissionIsVisible() {
        assertTrue(
            LocalLettaCodeService.notificationsWillBeVisible(
                sdkInt = Build.VERSION_CODES.TIRAMISU,
                postNotificationsGranted = true,
            ),
        )
    }

    @Test
    fun tiramisuWithDeniedPermissionIsNotVisibleButStillAllowsStart() {
        // The runtime must still be allowed to start; only visibility is affected.
        assertFalse(
            LocalLettaCodeService.notificationsWillBeVisible(
                sdkInt = Build.VERSION_CODES.TIRAMISU,
                postNotificationsGranted = false,
            ),
        )
    }

    @Test
    fun newerSdkWithDeniedPermissionIsNotVisible() {
        assertFalse(
            LocalLettaCodeService.notificationsWillBeVisible(
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                postNotificationsGranted = false,
            ),
        )
    }
}
