package com.letta.mobile.data.plugin.host

import com.letta.mobile.data.plugin.PluginCapability
import com.letta.mobile.data.plugin.wire.LcpCallException
import com.letta.mobile.data.plugin.wire.LcpErrorCode
import com.letta.mobile.util.Telemetry
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Why the host refused something a plugin asked of it: the [wire] reason the plugin reads in
 * `data.reason` (or, for an emit entry, as the prefix of its receipt reason), the LCP error [code]
 * a whole-call refusal answers, and the [capability] a `-32006` names.
 */
enum class PluginRefusal(val wire: String, val code: Int, val capability: PluginCapability? = null) {
    /** An emit placed a kind that is not `ext:<its own id>/...`; the whole emit is refused. */
    FOREIGN_KIND("foreign_kind", LcpErrorCode.CAPABILITY_REFUSED, PluginCapability.CANVAS_PLACE),

    /** An emit updated or removed an element the plugin did not place; the whole emit is refused. */
    FOREIGN_ELEMENT("foreign_element", LcpErrorCode.CAPABILITY_REFUSED, PluginCapability.CANVAS_PLACE),

    /** The access policy gave the plugin no board, or the board refused it. */
    NO_CANVAS("no_canvas", LcpErrorCode.CAPABILITY_REFUSED, PluginCapability.CANVAS_PLACE),

    /** The entries named more than one board; v1 places on one board per emit. */
    MIXED_CANVAS("mixed_canvas", LcpErrorCode.INVALID_PARAMS),

    /** The canvas validator refused the compiled batch; nothing was written. */
    INVALID_BATCH("invalid_batch", LcpErrorCode.INVALID_PARAMS),

    /** Entry refusals (the receipt): a kind/version the host has no schema for. */
    UNKNOWN_KIND("unknown_kind", LcpErrorCode.INVALID_PARAMS),

    /** Entry refusal: props over the size limit. */
    PROPS_TOO_LARGE("props_too_large", LcpErrorCode.INVALID_PARAMS),

    /** Entry refusal: inline snapshot bytes (v1 takes `host.putAsset` refs only). */
    INLINE_BYTES("inline_bytes_unsupported", LcpErrorCode.INVALID_PARAMS),

    /** Entry refusal: `placeImages` (not served in v1). */
    PLACE_IMAGES("place_images_unsupported", LcpErrorCode.INVALID_PARAMS),

    RATE_LIMITED("rate_limited", LcpErrorCode.OVERLOADED),
    ASSET_QUOTA("asset_quota", LcpErrorCode.UPLOAD_REFUSED),
    CONTENT_TYPE("content_type", LcpErrorCode.UPLOAD_REFUSED),
    CANVAS_READ("canvas_read_not_granted", LcpErrorCode.CAPABILITY_REFUSED, PluginCapability.CANVAS_READ),

    /** A `host.log` line dropped over the log rate (a notification has no answer to refuse with). */
    LOG_DROPPED("log_rate_limited", LcpErrorCode.OVERLOADED),
}

/**
 * Counts every refusal per plugin and reason, and reports it to [Telemetry], so a misbehaving
 * plugin shows up in diagnostics. One instance may serve every plugin of a host.
 */
class PluginRefusals {
    private val lock = SynchronizedObject()
    private val counts = mutableMapOf<Pair<String, PluginRefusal>, Int>()

    fun record(pluginId: String, refusal: PluginRefusal) {
        synchronized(lock) { counts[pluginId to refusal] = (counts[pluginId to refusal] ?: 0) + 1 }
        Telemetry.event(TAG, "refused", "plugin" to pluginId, "reason" to refusal.wire, level = Telemetry.Level.WARN)
    }

    fun count(pluginId: String, refusal: PluginRefusal): Int = synchronized(lock) { counts[pluginId to refusal] ?: 0 }

    /** Records [refusal] and builds the error the plugin is answered: `data {reason, index?, capability?}`. */
    fun refuse(pluginId: String, refusal: PluginRefusal, message: String, index: Int? = null): LcpCallException {
        record(pluginId, refusal)
        val data = buildJsonObject {
            put("reason", refusal.wire)
            index?.let { put("index", it) }
            refusal.capability?.let { put("capability", it.wire) }
        }
        return LcpCallException(refusal.code, message, data)
    }

    companion object {
        const val TAG: String = "PluginHost"
    }
}
