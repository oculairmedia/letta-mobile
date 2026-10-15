package com.letta.mobile.data.plugin.catalog

import com.letta.mobile.data.settings.SettingsGroup
import com.letta.mobile.data.settings.SettingsRow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** letta-mobile-cdgpr.9: the catalog every client binds until C7, the package hash, and the Settings entry. */
class PluginCatalogTest {
    @Test
    fun theUnavailableCatalogListsNothingAndRefusesWithItsReason() = runTest {
        val catalog = UnavailablePluginCatalog()
        assertTrue(catalog.plugins.value.isEmpty())
        val refused = assertIs<PluginCatalogResult.Refused>(catalog.install(byteArrayOf(1), "a".repeat(64)))
        assertEquals(UnavailablePluginCatalog.REASON, refused.reason)
        assertIs<PluginCatalogResult.Refused>(catalog.disableAll())
    }

    @Test
    fun aPackageIsPinnedByItsLowercaseHexSha256() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", pluginPackageSha256(ByteArray(0)))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", pluginPackageSha256("abc".encodeToByteArray()))
    }

    @Test
    fun settingsOpenThePluginsPageFromTheProvidersGroup() {
        var opened = 0
        val contribution = pluginsSettingsContribution { opened++ }
        assertEquals(SettingsGroup.Providers, contribution.group)
        assertEquals(PLUGINS_SECTION_KEY, contribution.initial.key)
        assertIs<SettingsRow.Link>(contribution.initial.rows.single()).onClick()
        assertEquals(1, opened)
    }
}
