package com.letta.mobile.debug

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * letta-mobile-by4vm.1: the release [AutomationAuthBootstrap] only reaches
 * `saveConfig` with a payload returned by [AutomationImportGate.consumeStagedPayload],
 * so a release build type must get `null` back and the staged key must be gone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
@Tag("integration")
class AutomationImportGateTest {
    private lateinit var context: Context
    private lateinit var stagingPrefs: SharedPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        stagingPrefs = context.getSharedPreferences(AutomationAuthBootstrap.PREFS_NAME, Context.MODE_PRIVATE)
        stagingPrefs.edit().clear().commit()
    }

    @After
    fun tearDown() {
        stagingPrefs.edit().clear().commit()
    }

    @Test
    fun onlyTheBenchmarkBuildTypeMayImport() {
        listOf("release", "nonMinifiedRelease", "benchmarkRelease", "debug").forEach { buildType ->
            assertFalse(buildType, AutomationImportGate.releaseImportEnabled(buildType))
        }
        assertTrue(AutomationImportGate.releaseImportEnabled("benchmark"))
    }

    @Test
    fun disabledGateRejectsAndClearsTheStagedPayload() {
        stage("ZXZpbC1wYXlsb2Fk")

        assertNull(AutomationImportGate.consumeStagedPayload(context, importEnabled = false))
        assertNull(stagingPrefs.getString(AutomationAuthBootstrap.KEY_PAYLOAD_BASE64, null))
    }

    @Test
    fun enabledGateReturnsTheStagedPayloadOnce() {
        stage(" YmVuY2gtcGF5bG9hZA== ")

        assertEquals("YmVuY2gtcGF5bG9hZA==", AutomationImportGate.consumeStagedPayload(context, importEnabled = true))
        assertNull(stagingPrefs.getString(AutomationAuthBootstrap.KEY_PAYLOAD_BASE64, null))
        assertNull(AutomationImportGate.consumeStagedPayload(context, importEnabled = true))
    }

    private fun stage(encoded: String) {
        stagingPrefs.edit().putString(AutomationAuthBootstrap.KEY_PAYLOAD_BASE64, encoded).commit()
    }
}
