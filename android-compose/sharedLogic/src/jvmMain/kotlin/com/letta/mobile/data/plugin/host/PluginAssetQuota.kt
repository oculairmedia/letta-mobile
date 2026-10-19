package com.letta.mobile.data.plugin.host

import com.letta.mobile.data.model.PluginId
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * How many asset bytes each plugin may store (plan: trust minimum,): at most
 * [limit] (default [DEFAULT_BYTES_PER_DAY] per trailing 24 hours), counted per plugin over a
 * sliding window. Bytes the store already has cost nothing (assets are content-addressed, so a
 * re-upload writes nothing), and an upload that would cross the limit is refused whole.
 *
 * With a [file]  the window's charges are kept on disk: [load] reads them at
 * host start and every charge or refund rewrites the file atomically, so a host restart does not
 * reset the day's budget. Without one the usage lives in memory only.
 */
class PluginAssetQuota(
    val limit: PluginRateLimit = PluginRateLimit(DEFAULT_BYTES_PER_DAY, 1.days),
    nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val file: PluginAssetQuotaFile? = null,
) {
    private val window = SlidingWindowCounter(limit, nowMs)
    private val saving = SynchronizedObject()

    /** Restores the usage [file] holds (nothing without one); a file that does not load starts the window empty. */
    fun load() {
        file?.load()?.let(window::restore)
    }

    /** Charges [bytes] to [pluginId]; false (and nothing charged) when that would cross [limit]. */
    fun tryCharge(pluginId: PluginId, bytes: Long): Boolean = window.tryTake(pluginId.value, bytes).also { charged -> if (charged) save() }

    /** Gives back a charge of [bytes] whose store failed. */
    fun refund(pluginId: PluginId, bytes: Long) {
        window.refund(pluginId.value, bytes)
        save()
    }

    /** The bytes [pluginId] has stored in the current window. */
    fun used(pluginId: PluginId): Long = window.used(pluginId.value)

    /** Writes the current window; serialized, so the last write always holds the latest usage. */
    private fun save() {
        val target = file ?: return
        synchronized(saving) { target.save(window.snapshot()) }
    }

    companion object {
        const val DEFAULT_BYTES_PER_DAY: Long = 64L * 1024 * 1024
    }
}
