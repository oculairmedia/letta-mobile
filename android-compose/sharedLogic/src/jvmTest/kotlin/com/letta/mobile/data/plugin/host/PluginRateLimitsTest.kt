package com.letta.mobile.data.plugin.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/** letta-mobile-cdgpr.5: per-plugin sliding windows for emits, logs and asset bytes, on a test clock. */
class PluginRateLimitsTest {
    private var now = 0L

    @Test
    fun emitsAreLimitedPerPluginOverASlidingSecond() {
        val limits = PluginRateLimits(emit = PluginRateLimit(2), nowMs = { now })

        assertTrue(limits.tryEmit("a"))
        now = 600
        assertTrue(limits.tryEmit("a"))
        assertFalse(limits.tryEmit("a"))
        assertTrue(limits.tryEmit("b"), "each plugin has its own budget")
        now = 999
        assertFalse(limits.tryEmit("a"), "the first emit is still inside the window")
        now = 1000
        assertTrue(limits.tryEmit("a"), "the first emit has left the window")
        assertFalse(limits.tryEmit("a"))
    }

    @Test
    fun logsHaveTheirOwnBudget() {
        val limits = PluginRateLimits(emit = PluginRateLimit(1), log = PluginRateLimit(3), nowMs = { now })

        assertTrue(limits.tryEmit("a"))
        assertEquals(listOf(true, true, true, false), List(4) { limits.tryLog("a") })
    }

    @Test
    fun theAssetQuotaDefaultsTo64MiBPerDay() {
        val quota = PluginAssetQuota(nowMs = { now })

        assertEquals(64L * 1024 * 1024, quota.limit.amount)
        assertEquals(1.days, quota.limit.window)
        assertTrue(quota.tryCharge("a", 60L * 1024 * 1024))
        assertFalse(quota.tryCharge("a", 5L * 1024 * 1024))
        assertTrue(quota.tryCharge("a", 4L * 1024 * 1024))
        now = 1.days.inWholeMilliseconds
        assertEquals(0L, quota.used("a"))
        assertTrue(quota.tryCharge("a", 64L * 1024 * 1024))
    }

    @Test
    fun aRefundedChargeFreesItsBytes() {
        val quota = PluginAssetQuota(PluginRateLimit(100, 1.hours), nowMs = { now })

        assertTrue(quota.tryCharge("a", 80))
        quota.refund("a", 80)

        assertEquals(0L, quota.used("a"))
        assertTrue(quota.tryCharge("a", 100))
    }

    @Test
    fun aLimitMustBeSane() {
        assertFailsWith<IllegalArgumentException> { PluginRateLimit(-1) }
        assertFailsWith<IllegalArgumentException> { PluginRateLimit(1, 0.days) }
    }
}
