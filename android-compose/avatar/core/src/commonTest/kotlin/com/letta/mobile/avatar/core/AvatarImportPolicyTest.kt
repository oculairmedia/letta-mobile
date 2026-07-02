package com.letta.mobile.avatar.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AvatarImportPolicyTest {
    @Test
    fun explicitRedistributionRightsAllowCatalogImport() {
        val decision = AvatarImportPolicy.evaluate(
            AvatarLicense(allowRedistribution = true),
            AvatarImportVisibility.SHARED_CATALOG,
        )
        assertEquals(AvatarImportDecision.Allowed, decision)
    }

    @Test
    fun deniedRedistributionRejectsCatalogButAllowsLocal() {
        val license = AvatarLicense(allowRedistribution = false, licenseName = "VRoid Hub personal")

        val catalog = AvatarImportPolicy.evaluate(license, AvatarImportVisibility.SHARED_CATALOG)
        val rejected = assertIs<AvatarImportDecision.Rejected>(catalog)
        assertTrue("VRoid Hub personal" in rejected.reason)

        assertEquals(
            AvatarImportDecision.Allowed,
            AvatarImportPolicy.evaluate(license, AvatarImportVisibility.PRIVATE_LOCAL),
        )
    }

    @Test
    fun unknownRedistributionRejectsCatalogAndDowngradesLocal() {
        val unknown = AvatarLicense()

        assertIs<AvatarImportDecision.Rejected>(
            AvatarImportPolicy.evaluate(unknown, AvatarImportVisibility.SHARED_CATALOG),
        )
        assertIs<AvatarImportDecision.AllowedLocalOnly>(
            AvatarImportPolicy.evaluate(unknown, AvatarImportVisibility.PRIVATE_LOCAL),
        )
    }
}
