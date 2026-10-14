package com.letta.mobile.data.plugin.host

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/** letta-mobile-cdgpr.15: a plugin's asset charges of the trailing day survive a host restart. */
class PluginAssetQuotaFileTest {
    private val dir: Path = Files.createTempDirectory("asset-quota")
    private val path: Path = dir.resolve(PluginAssetQuotaFile.FILE)
    private var now = 1_000_000L
    private val limit = PluginRateLimit(150, 1.days)

    /** A quota as a freshly started host builds it: same file, same clock, usage loaded. */
    private fun restarted() = PluginAssetQuota(limit, nowMs = { now }, file = PluginAssetQuotaFile(path)).also { it.load() }

    @Test
    fun chargesSurviveARestartAndStillHoldTheLimit() {
        val before = restarted()
        assertTrue(before.tryCharge(PLUGIN, 100))
        assertTrue(before.tryCharge(OTHER, 40))
        assertTrue(Files.isRegularFile(path), "the usage is on disk beside the registry")

        val after = restarted()

        assertEquals(100L, after.used(PLUGIN))
        assertEquals(40L, after.used(OTHER))
        assertFalse(after.tryCharge(PLUGIN, 100), "the restart did not reset the day's budget")
        assertTrue(after.tryCharge(PLUGIN, 50))
        assertEquals(150L, restarted().used(PLUGIN))
    }

    @Test
    fun aRefundIsKeptAndChargesOutOfTheWindowAreForgotten() {
        val before = restarted()
        assertTrue(before.tryCharge(PLUGIN, 100))
        now += 12.hours.inWholeMilliseconds
        assertTrue(before.tryCharge(PLUGIN, 30))
        before.refund(PLUGIN, 30)
        assertEquals(100L, restarted().used(PLUGIN), "the refund was saved")

        now += 13.hours.inWholeMilliseconds

        assertEquals(0L, restarted().used(PLUGIN), "a charge a day old no longer counts after a restart")
        assertTrue(restarted().tryCharge(PLUGIN, 150))
    }

    @Test
    fun anUnreadableFileStartsEmptyAndIsReplacedOnTheNextCharge() {
        Files.writeString(path, "{not json")

        val quota = restarted()

        assertEquals(0L, quota.used(PLUGIN))
        assertTrue(quota.tryCharge(PLUGIN, 20))
        assertEquals(20L, restarted().used(PLUGIN))
    }

    @Test
    fun withoutAFileTheUsageIsInMemoryOnly() {
        val quota = PluginAssetQuota(limit, nowMs = { now })
        quota.load()
        assertTrue(quota.tryCharge(PLUGIN, 100))

        assertEquals(0L, PluginAssetQuota(limit, nowMs = { now }).used(PLUGIN))
        assertFalse(Files.exists(path))
    }

    private companion object {
        const val PLUGIN = "letta.chart"
        const val OTHER = "acme.other"
    }
}
