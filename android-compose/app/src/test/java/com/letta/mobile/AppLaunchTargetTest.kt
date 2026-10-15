package com.letta.mobile

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.letta.mobile.debug.AutomationImportGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** letta-mobile-by4vm.31: automation launch extras are a non-shipping-build affordance only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class AppLaunchTargetTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun shippingBuildTypesIgnoreAutomationExtras() {
        listOf("release", "playRelease", "nonMinifiedRelease").forEach { buildType ->
            assertFalse(buildType, AutomationImportGate.automationLaunchExtrasEnabled(buildType))
        }
    }

    @Test
    fun debugAndBenchmarkHonourAutomationExtras() {
        assertTrue(AutomationImportGate.automationLaunchExtrasEnabled("debug"))
        assertTrue(AutomationImportGate.automationLaunchExtrasEnabled("benchmark"))
    }

    @Test
    fun openAgentListExtraIgnoredWhenAutomationDisabled() {
        val intent = Intent().putExtra("automation_open_agent_list", true)

        assertNull(AppLaunchTarget.fromIntent(intent, context, automationExtrasEnabled = false))
        assertEquals(AgentListLaunchTarget, AppLaunchTarget.fromIntent(intent, context, automationExtrasEnabled = true))
    }
}
