package com.letta.mobile.data.plugin

import com.letta.mobile.plugin.api.PluginApi
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [PluginManifestRules.CONTRACT_VERSION] is inlined in commonMain so clients don't ship
 * `:plugin-api` (letta-mobile-cdgpr.1). The host (jvm) does, so this holds the two equal.
 */
class PluginContractVersionTest {
    @Test
    fun manifestRulesContractMatchesPluginApi() {
        assertEquals(PluginApi.CONTRACT_VERSION, PluginManifestRules.CONTRACT_VERSION)
    }
}
