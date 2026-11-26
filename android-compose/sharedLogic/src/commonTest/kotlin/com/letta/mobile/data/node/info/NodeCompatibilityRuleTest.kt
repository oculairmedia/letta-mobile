package com.letta.mobile.data.node.info

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NodeCompatibilityRuleTest {
    private val desktop = NodeInfo(
        os = "windows",
        appVersion = "1.4.0",
        lettaCodeVersion = "0.34.70",
        capabilities = listOf("ui", "catalog", "approve", "execute", "store"),
        runtimePatches = RuntimePatchSet(RuntimePatchSet.SOURCE_BOOT_LINE, applied = 36, skipped = 0),
    )
    private val host = desktop.copy(os = "linux", capabilities = listOf("catalog", "execute", "store", "always-on"))

    @Test
    fun aMatchingNodeIsCompatibleAndAllowsWhatItAdvertises() {
        val verdict = NodeCompatibilityRule.evaluate(host, reference = desktop)

        assertEquals(NodeAvailability.COMPATIBLE, verdict.availability)
        assertTrue(verdict.allows(NodeCapability.EXECUTE))
        assertTrue(verdict.allows(NodeCapability.ALWAYS_ON))
        assertFalse(verdict.allows(NodeCapability.UI), "the host does not advertise ui")
    }

    @Test
    fun aStoreFormatMajorMismatchBlocksExecuteAndStoreButNotUiOrCatalog() {
        val verdict = NodeCompatibilityRule.evaluate(desktop.copy(storeFormat = StoreFormatVersion(2, 0)))

        assertEquals(NodeAvailability.LIMITED, verdict.availability)
        assertEquals(NodeIncompatibilityKind.STORE_FORMAT, verdict.reasons.single().kind)
        assertEquals("1.0", verdict.reasons.single().expected)
        assertEquals("2.0", verdict.reasons.single().actual)
        assertFalse(verdict.allows(NodeCapability.EXECUTE))
        assertFalse(verdict.allows(NodeCapability.STORE))
        assertTrue(verdict.allows(NodeCapability.UI))
        assertTrue(verdict.allows(NodeCapability.CATALOG))
    }

    @Test
    fun aStoreFormatMinorDifferenceIsCompatible() {
        val verdict = NodeCompatibilityRule.evaluate(desktop.copy(storeFormat = StoreFormatVersion(1, 7)))

        assertEquals(NodeAvailability.COMPATIBLE, verdict.availability)
    }

    @Test
    fun anotherLettaCodeVersionThanTheReferenceBlocksExecution() {
        val verdict = NodeCompatibilityRule.evaluate(host.copy(lettaCodeVersion = "0.34.39"), reference = desktop)

        assertEquals(NodeIncompatibilityKind.LETTA_CODE, verdict.reasons.single().kind)
        assertEquals("0.34.70", verdict.reasons.single().expected)
        assertEquals("0.34.39", verdict.reasons.single().actual)
        assertEquals(setOf(NodeCapability.EXECUTE, NodeCapability.STORE), verdict.blocked)
        assertTrue(verdict.allows(NodeCapability.CATALOG))
    }

    @Test
    fun anotherPatchSetThanTheReferenceBlocksExecution() {
        val stale = host.copy(runtimePatches = RuntimePatchSet(RuntimePatchSet.SOURCE_BOOT_LINE, applied = 30, skipped = 0))

        val verdict = NodeCompatibilityRule.evaluate(stale, reference = desktop)

        assertEquals(NodeIncompatibilityKind.RUNTIME_PATCHES, verdict.reasons.single().kind)
        assertEquals(NodeAvailability.LIMITED, verdict.availability)
    }

    @Test
    fun skippedPatchesAreAPatchSetDifference() {
        val degraded = host.copy(runtimePatches = RuntimePatchSet(RuntimePatchSet.SOURCE_BOOT_LINE, applied = 36, skipped = 2))

        val verdict = NodeCompatibilityRule.evaluate(degraded, reference = desktop)

        assertEquals(listOf(NodeIncompatibilityKind.RUNTIME_PATCHES), verdict.reasons.map { it.kind })
    }

    @Test
    fun digestsWinOverCountsWhenBothSidesCarryOne() {
        val a = RuntimePatchSet(RuntimePatchSet.SOURCE_BOOT_LINE, applied = 36, skipped = 0, digest = "a".repeat(64))
        val b = a.copy(digest = "b".repeat(64))

        assertEquals(true, a.differsFrom(b))
        assertEquals(false, a.differsFrom(a.copy(applied = 99)), "the same ids are the same set")
    }

    @Test
    fun unknownValuesAreNeverAMismatch() {
        val unknown = host.copy(lettaCodeVersion = NodeInfoWire.UNKNOWN, runtimePatches = RuntimePatchSet.UNKNOWN)

        val verdict = NodeCompatibilityRule.evaluate(unknown, reference = desktop)

        assertEquals(NodeAvailability.COMPATIBLE, verdict.availability)
        assertNull(RuntimePatchSet.UNKNOWN.differsFrom(desktop.runtimePatches))
    }

    @Test
    fun withoutAReferenceOnlyThisBuildsFormatsAreChecked() {
        val verdict = NodeCompatibilityRule.evaluate(host.copy(lettaCodeVersion = "0.1.0"))

        assertEquals(NodeAvailability.COMPATIBLE, verdict.availability)
    }

    @Test
    fun anotherSchemaMakesTheNodeUnavailable() {
        val verdict = NodeCompatibilityRule.evaluate(desktop.copy(schema = 2), reference = desktop)

        assertEquals(NodeAvailability.UNAVAILABLE, verdict.availability)
        assertEquals(NodeIncompatibilityKind.SCHEMA, verdict.reasons.single().kind)
        assertFalse(verdict.allows(NodeCapability.UI))
        assertFalse(verdict.allows(NodeCapability.CATALOG))
    }

    @Test
    fun aLegacyNodeIsUnverifiedAndBlockedForNothing() {
        val legacy = NodeCompatibility.LEGACY

        assertEquals(NodeAvailability.UNVERIFIED, legacy.availability)
        assertTrue(NodeCapability.entries.all(legacy::allows))
    }

    @Test
    fun capabilityNamesThisBuildDoesNotKnowAreIgnored() {
        val info = desktop.copy(capabilities = listOf("ui", "teleport"))

        assertEquals(setOf(NodeCapability.UI), info.capabilitySet)
        assertEquals(NodeCapability.ALWAYS_ON, NodeCapability.fromWire("always-on"))
    }
}
