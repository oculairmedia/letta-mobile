package com.letta.mobile.data.plugin.catalog

import com.letta.mobile.data.model.PluginId
import com.letta.mobile.data.plugin.PluginCapability
import com.letta.mobile.data.plugin.PluginHealth
import com.letta.mobile.data.plugin.PluginState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** letta-mobile-cdgpr.9: the Plugins page's pure state machine. */
class PluginsPageModelTest {
    private val request = PluginInstallRequest(
        id = PluginId("letta.chart"),
        name = "Chart",
        version = "1.0.0",
        publisher = "Letta",
        sha256 = "a".repeat(64),
        runtimeKind = "process",
        capabilities = listOf(PluginCapability.CANVAS_PLACE, PluginCapability.NET_CONNECT),
        origins = listOf("https://api.example.test"),
        pages = listOf("pages/chart.html"),
    )

    private fun staged(): PluginsPageState = PluginsPageModel.reduce(PluginsPageState(busy = true), PluginsPageEvent.Staged(request))

    private fun PluginsPageState.deciding(vararg decisions: Pair<PluginConsentItem, Boolean>): PluginsPageState =
        decisions.fold(this) { state, (item, allow) -> PluginsPageModel.reduce(state, PluginsPageEvent.Decided(item, allow)) }

    private val canvas = PluginConsentItem.Capability(PluginCapability.CANVAS_PLACE)
    private val net = PluginConsentItem.Capability(PluginCapability.NET_CONNECT)
    private val origin = PluginConsentItem.Origin("https://api.example.test")
    private val page = PluginConsentItem.Page("pages/chart.html")

    @Test
    fun theSheetListsEveryCapabilityOriginAndPage() {
        val sheet = staged().consent!!
        assertEquals(listOf(canvas, net, origin, page), sheet.items)
        assertFalse(staged().busy)
    }

    @Test
    fun installWaitsForADecisionOnEveryItem() {
        val sheet = staged().deciding(canvas to true, net to true, origin to true).consent!!
        assertFalse(sheet.allDecided)
        assertEquals(PluginConsentVerdict.Undecided, PluginsPageModel.verdict(sheet))
    }

    @Test
    fun oneDenyRefusesTheInstallAndNamesWhatWasDenied() {
        val sheet = staged().deciding(canvas to true, net to false, origin to true, page to true).consent!!
        val verdict = assertIs<PluginConsentVerdict.Refused>(PluginsPageModel.verdict(sheet))
        assertTrue("net:connect" in verdict.reason, verdict.reason)
        assertTrue("nothing was installed" in verdict.reason, verdict.reason)
    }

    @Test
    fun allowingEverythingGrantsExactlyWhatWasAsked() {
        val sheet = staged().deciding(canvas to true, net to true, origin to true, page to true).consent!!
        val verdict = assertIs<PluginConsentVerdict.Granted>(PluginsPageModel.verdict(sheet))
        assertEquals(PluginGrants(request.capabilities.toSet(), request.origins.toSet(), request.pages.toSet()), verdict.grants)
    }

    @Test
    fun aDecisionCanBeChangedBeforeInstall() {
        val sheet = staged().deciding(canvas to false, canvas to true).consent!!
        assertEquals(true, sheet.decision(canvas))
    }

    @Test
    fun aDecisionOnSomethingNotAskedForIsIgnored() {
        val sheet = staged().deciding(PluginConsentItem.Capability(PluginCapability.UI_CAMERA) to true).consent!!
        assertTrue(sheet.decisions.isEmpty())
    }

    @Test
    fun aRefusedInstallClosesTheSheetAndKeepsTheReason() {
        val picked = PickedPluginPackage("chart.zip", 10, "b".repeat(64))
        val state = PluginsPageModel.reduce(staged().copy(picked = picked), PluginsPageEvent.InstallRefused("no"))
        assertNull(state.consent)
        assertNull(state.picked)
        assertEquals("no", state.error)
    }

    @Test
    fun aFinishedCallClosesItsConfirmation() {
        val asked = PluginsPageState(busy = true, confirmDisableAll = true, pendingRemove = PluginId("letta.chart"))
        val done = PluginsPageModel.reduce(asked, PluginsPageEvent.Done)
        assertFalse(done.busy)
        assertFalse(done.confirmDisableAll)
        assertNull(done.pendingRemove)
        val failed = PluginsPageModel.reduce(asked, PluginsPageEvent.Failed("refused"))
        assertEquals("refused", failed.error)
        assertFalse(failed.confirmDisableAll)
    }

    @Test
    fun aRowCarriesItsFaultReason() {
        assertEquals("crashed", PluginSummary(PluginId("a"), "A", "1", PluginState.Faulted("crashed")).faultReason)
        assertEquals("exit 1", PluginSummary(PluginId("a"), "A", "1", PluginState.Enabled, PluginHealth.Failed("exit 1")).faultReason)
        assertNull(PluginSummary(PluginId("a"), "A", "1", PluginState.Active, PluginHealth.Degraded("slow")).faultReason)
        assertTrue(PluginSummary(PluginId("a"), "A", "1", PluginState.Active).enabled)
        assertFalse(PluginSummary(PluginId("a"), "A", "1", PluginState.Installed).enabled)
    }
}
