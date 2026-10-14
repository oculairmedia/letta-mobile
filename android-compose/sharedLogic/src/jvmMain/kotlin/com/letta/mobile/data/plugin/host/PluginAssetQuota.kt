package com.letta.mobile.data.plugin.host

import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * How many asset bytes each plugin may store (plan: trust minimum, letta-mobile-cdgpr.5): at most
 * [limit] (default [DEFAULT_BYTES_PER_DAY] per trailing 24 hours), counted per plugin over a
 * sliding window. Bytes the store already has cost nothing (assets are content-addressed, so a
 * re-upload writes nothing), and an upload that would cross the limit is refused whole.
 *
 * TODO(letta-mobile-cdgpr.15): in memory, so a host restart forgets the day's usage; persist it with
 * the plugin registry once PluginHostRuntime owns one. A crash-looping plugin is already held by
 * the driver's restart backoff and Faulted state.
 */
class PluginAssetQuota(
    val limit: PluginRateLimit = PluginRateLimit(DEFAULT_BYTES_PER_DAY, 1.days),
    nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val window = SlidingWindowCounter(limit, nowMs)

    /** Charges [bytes] to [pluginId]; false (and nothing charged) when that would cross [limit]. */
    fun tryCharge(pluginId: String, bytes: Long): Boolean = window.tryTake(pluginId, bytes)

    /** Gives back a charge of [bytes] whose store failed. */
    fun refund(pluginId: String, bytes: Long) = window.refund(pluginId, bytes)

    /** The bytes [pluginId] has stored in the current window. */
    fun used(pluginId: String): Long = window.used(pluginId)

    companion object {
        const val DEFAULT_BYTES_PER_DAY: Long = 64L * 1024 * 1024
    }
}
