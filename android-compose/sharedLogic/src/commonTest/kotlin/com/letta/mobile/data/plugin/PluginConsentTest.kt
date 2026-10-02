package com.letta.mobile.data.plugin

import com.letta.mobile.data.plugin.PluginRegistryFixtures.json
import com.letta.mobile.data.plugin.PluginRegistryFixtures.manifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Capabilities and consent diffing (plan section 3.3): what an update adds must be consented to again. */
class PluginConsentTest {
    private val installed = PluginRegistryFixtures.example
    private val consent = PluginConsent.forManifest(installed, PluginRegistryFixtures.NOW)

    @Test
    fun consentToTheManifestCoversItExactly() {
        assertTrue(consent.covers(installed))
        assertEquals("1.2.0", consent.forVersion)
        assertTrue(PluginConsentDiff.between(consent, installed).isEmpty)
    }

    @Test
    fun anUpdateThatAddsACapabilityOrAnOriginMustBeConsentedAgain() {
        val update = manifest(
            "/version" to json("\"1.3.0\""),
            "/capabilities/5" to json("\"ui:openLink\""),
            "/net/connect/2" to json("\"wss://jobs.example.test\""),
        )
        val diff = PluginConsentDiff.forUpdate(installed, consent, update)
        assertEquals(setOf(PluginCapability.UI_OPEN_LINK), diff.addedCapabilities)
        assertEquals(setOf("wss://jobs.example.test"), diff.addedOrigins)
        assertFalse(diff.runtimeChanged)
        assertFalse(diff.isEmpty)
    }

    @Test
    fun anUpdateThatDropsCapabilitiesAsksNothing() {
        val update = manifest("/version" to json("\"1.3.0\""), "/capabilities" to json("""["canvas:place","net:connect","ui:pages"]"""))
        assertTrue(PluginConsentDiff.forUpdate(installed, consent, update).isEmpty)
    }

    @Test
    fun anUpdateThatChangesTheRuntimeKindMustBeConsentedAgain() {
        val update = manifest("/version" to json("\"1.3.0\""), "/runtime" to json("""{"kind":"process","command":"node"}"""))
        val diff = PluginConsentDiff.forUpdate(installed, consent, update)
        assertTrue(diff.runtimeChanged)
        assertFalse(diff.isEmpty)
    }

    @Test
    fun noConsentAtAllMeansEverythingIsAdded() {
        val diff = PluginConsentDiff.between(null, installed)
        assertEquals(installed.capabilities.toSet(), diff.addedCapabilities)
        assertEquals(installed.net.connect.toSet(), diff.addedOrigins)
    }

    @Test
    fun pagePermissionsAreTheFirstUseCapabilities() {
        assertEquals(
            listOf("ui:openLink", "ui:clipboardWrite", "ui:camera", "ui:microphone", "ui:geolocation"),
            PluginCapability.pagePermissions.map { it.wire },
        )
        assertTrue(PluginCapability.entries.filter { it.consent == PluginConsentTime.INSTALL }.none { it in PluginCapability.pagePermissions })
    }
}
