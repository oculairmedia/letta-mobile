@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.pages.plugins

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.PluginId
import com.letta.mobile.data.plugin.PluginCapability
import com.letta.mobile.data.plugin.catalog.PluginConsentItem
import com.letta.mobile.data.plugin.catalog.PluginPackageFile
import com.letta.mobile.data.plugin.catalog.PluginsPageController
import com.letta.mobile.data.plugin.catalog.inMemoryLiveViewsSetting
import com.letta.mobile.data.plugin.catalog.pluginPackageSha256
import com.letta.mobile.ui.devfixtures.FakePluginCatalog
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.LettaPaneDefaults
import com.letta.mobile.ui.host.LocalLettaHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** letta-mobile-cdgpr.9: the shared Plugins page over the fake catalog - consent, enable/disable, disable all, faults. */
class PluginsPageTest {
    /** Every catalog call answers at once on the calling thread: the page settles before the next assertion. */
    private fun controller(catalog: FakePluginCatalog, liveViews: Boolean = false) = PluginsPageController(
        catalog,
        CoroutineScope(Dispatchers.Unconfined),
        inMemoryLiveViewsSetting(liveViews),
        hashing = EmptyCoroutineContext,
    )

    private fun ComposeUiTest.show(controller: PluginsPageController, onPick: () -> Unit = {}) {
        // Pointer containers (dialogs) everywhere, the phone's sheet included: the test draws no window insets.
        val host = LettaHost.Pointer.copy(sheet = LettaPaneDefaults.Dialog)
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLettaHost provides host) {
                    Box(Modifier.width(560.dp).height(900.dp)) {
                        val state by controller.state.collectAsState()
                        PluginsPage(state, controller, options = PluginsPageOptions(onPickPackage = onPick))
                    }
                }
            }
        }
    }

    private val chart = PluginId("letta.chart")
    private val clock = PluginId("letta.clock")
    private val chartBytes = "chart package".encodeToByteArray()

    /** Picks the chart package and uploads it: the consent sheet opens. */
    private fun ComposeUiTest.stage(controller: PluginsPageController) {
        controller.pick(PluginPackageFile("chart.zip", chartBytes))
        onNodeWithTag(PluginsPageTags.PICKED_SHA).assertExists()
        onNodeWithText("SHA-256 ${pluginPackageSha256(chartBytes)}").assertExists()
        onNodeWithTag(PluginsPageTags.UPLOAD).performClick()
        onNodeWithTag(PluginsPageTags.CONSENT).assertExists()
    }

    private fun ComposeUiTest.decide(item: PluginConsentItem, allow: Boolean) {
        val tag = if (allow) PluginsPageTags.allow(item) else PluginsPageTags.deny(item)
        onNodeWithTag(tag).performScrollTo().performClick()
    }

    private val chartItems = FakePluginCatalog.ChartPluginRequest.let { request ->
        request.capabilities.map(PluginConsentItem::Capability) + request.pages.map(PluginConsentItem::Page)
    }

    @Test
    fun thePickedFilesHashIsShownBeforeUploadAndTheSheetListsEverythingAsked() = runComposeUiTest {
        val catalog = FakePluginCatalog()
        val controller = controller(catalog)
        var picks = 0
        show(controller) { picks++ }
        onNodeWithTag(PluginsPageTags.PICK).performClick()
        assertEquals(1, picks)
        controller.pick(PluginPackageFile("chart.zip", chartBytes))
        assertTrue(catalog.calls.isEmpty(), "nothing is uploaded before the owner reviews the hash")
        stage(controller)
        listOf("canvas:place", "assets:write", "ui:pages", "pages/chart.html").forEach { onNodeWithText(it).performScrollTo().assertExists() }
        onNodeWithText("Package SHA-256 ${pluginPackageSha256(chartBytes)}").assertExists()
        onNodeWithTag(PluginsPageTags.CONSENT_INSTALL).assertIsNotEnabled()
    }

    @Test
    fun denyingOneCapabilityRefusesTheInstallWithTheReason() = runComposeUiTest {
        val catalog = FakePluginCatalog()
        val controller = controller(catalog)
        show(controller)
        stage(controller)
        chartItems.forEach { decide(it, allow = it != PluginConsentItem.Capability(PluginCapability.ASSETS_WRITE)) }
        onNodeWithTag(PluginsPageTags.CONSENT_INSTALL).assertIsEnabled().performClick()
        onNodeWithTag(PluginsPageTags.CONSENT).assertDoesNotExist()
        onNodeWithTag(PluginsPageTags.ERROR).assertExists()
        onNodeWithText("Install refused: you denied assets:write", substring = true).assertExists()
        assertFalse(catalog.calls.any { it.startsWith("consent:") }, "a denied consent is never sent")
        assertTrue("cancel:letta.chart" in catalog.calls, "the staged package is dropped")
        assertTrue(controller.state.value.plugins.isEmpty())
    }

    @Test
    fun allowingEverythingInstallsThePlugin() = runComposeUiTest {
        val catalog = FakePluginCatalog()
        val controller = controller(catalog)
        show(controller)
        stage(controller)
        chartItems.forEach { decide(it, allow = true) }
        onNodeWithTag(PluginsPageTags.CONSENT_INSTALL).performClick()
        onNodeWithTag(PluginsPageTags.CONSENT).assertDoesNotExist()
        assertTrue("consent:letta.chart" in catalog.calls)
        onNodeWithTag(PluginsPageTags.toggle(chart)).assertIsOff()
    }

    @Test
    fun aPluginIsEnabledAndDisabledFromItsRow() = runComposeUiTest {
        val catalog = FakePluginCatalog(installed = FakePluginCatalog.SamplePlugins)
        val controller = controller(catalog)
        show(controller)
        onNodeWithTag(PluginsPageTags.toggle(chart)).assertIsOn().performClick()
        onNodeWithTag(PluginsPageTags.toggle(chart)).assertIsOff()
        onNodeWithTag(PluginsPageTags.toggle(chart)).performClick()
        onNodeWithTag(PluginsPageTags.toggle(chart)).assertIsOn()
        assertEquals(listOf("disable:letta.chart", "enable:letta.chart"), catalog.calls)
    }

    @Test
    fun disableAllAsksFirstAndOnlyThenTurnsEveryPluginOff() = runComposeUiTest {
        val catalog = FakePluginCatalog(installed = FakePluginCatalog.SamplePlugins)
        val controller = controller(catalog)
        show(controller)
        onNodeWithTag(PluginsPageTags.DISABLE_ALL).performClick()
        onNodeWithTag(PluginsPageTags.DISABLE_ALL_CONFIRM).assertExists()
        assertTrue(catalog.calls.isEmpty(), "nothing is disabled before the confirmation")
        onNodeWithTag(PluginsPageTags.DISABLE_ALL_CONFIRM).performClick()
        assertEquals(listOf("disableAll"), catalog.calls)
        onNodeWithTag(PluginsPageTags.DISABLE_ALL_CONFIRM).assertDoesNotExist()
        onNodeWithTag(PluginsPageTags.toggle(chart)).assertIsOff()
        onNodeWithTag(PluginsPageTags.DISABLE_ALL).assertIsNotEnabled()
    }

    @Test
    fun aFaultedPluginShowsItsFaultBadgeAndReason() = runComposeUiTest {
        show(controller(FakePluginCatalog(installed = FakePluginCatalog.SamplePlugins)))
        onNodeWithTag(PluginsPageTags.fault(clock)).assertExists()
        onNode(hasTestTag(PluginsPageTags.fault(clock)) and hasContentDescription("Faulted: crashed 3 times")).assertExists()
        onNodeWithText("Faulted: crashed 3 times", substring = true).assertExists()
        onNodeWithTag(PluginsPageTags.fault(chart)).assertDoesNotExist()
    }

    @Test
    fun removeAsksFirst() = runComposeUiTest {
        val catalog = FakePluginCatalog(installed = FakePluginCatalog.SamplePlugins)
        val controller = controller(catalog)
        show(controller)
        onNodeWithTag(PluginsPageTags.remove(clock)).performClick()
        onNodeWithText("Remove Clock?").assertExists()
        onNodeWithTag(PluginsPageTags.REMOVE_CONFIRM).performClick()
        assertEquals(listOf("remove:letta.clock"), catalog.calls)
        onNodeWithText("Clock").assertDoesNotExist()
    }

    @Test
    fun aRefusalIsShownAndDismissed() = runComposeUiTest {
        val catalog = FakePluginCatalog(installed = FakePluginCatalog.SamplePlugins, refuse = "host is read-only")
        show(controller(catalog))
        onNodeWithTag(PluginsPageTags.toggle(chart)).performClick()
        onNodeWithText("host is read-only").assertExists()
        onNodeWithText("Dismiss").performClick()
        onNodeWithTag(PluginsPageTags.ERROR).assertDoesNotExist()
        onNodeWithTag(PluginsPageTags.toggle(chart)).assertIsOn()
    }

    @Test
    fun theLiveViewsSwitchFollowsThisDevicesSetting() = runComposeUiTest {
        val controller = controller(FakePluginCatalog(), liveViews = true)
        show(controller)
        onNodeWithTag(PluginsPageTags.LIVE_VIEWS).assertIsOn().performClick()
        onNodeWithTag(PluginsPageTags.LIVE_VIEWS).assertIsOff()
        assertFalse(controller.state.value.liveViews)
    }
}
