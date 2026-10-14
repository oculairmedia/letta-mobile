package com.letta.mobile.data.plugin.host

import com.letta.mobile.data.plugin.SecretScrubber
import com.letta.mobile.data.plugin.wire.LcpHostHandlers
import com.letta.mobile.data.plugin.wire.LcpSecretGuard
import com.letta.mobile.data.plugin.wire.LcpUploadedAsset
import com.letta.mobile.data.plugin.wire.LcpWire
import com.letta.mobile.data.plugin.wire.LogParams
import com.letta.mobile.data.storage.AssetRefs
import com.letta.mobile.data.storage.AssetStore
import com.letta.mobile.plugin.api.ElementQuery
import com.letta.mobile.plugin.api.EmitReceipt
import com.letta.mobile.plugin.api.LogLevel
import com.letta.mobile.plugin.api.PluginElementView
import com.letta.mobile.plugin.api.PluginEmit
import com.letta.mobile.util.Telemetry
import kotlinx.serialization.json.JsonObject

/** Where a plugin's scrubbed `host.log` lines go. [TELEMETRY] is the host's [Telemetry]. */
fun interface PluginLogSink {
    fun write(pluginId: String, line: LogParams)

    companion object {
        val TELEMETRY = PluginLogSink { pluginId, line ->
            val fields = line.fields.map { (key, value) -> "field.$key" to value }.toTypedArray()
            PluginHostEvents.log(pluginId, levelOf(line.level), "message" to line.message, *fields)
        }

        private fun levelOf(level: LogLevel): Telemetry.Level = when (level) {
            LogLevel.DEBUG -> Telemetry.Level.DEBUG
            LogLevel.INFO -> Telemetry.Level.INFO
            LogLevel.WARN -> Telemetry.Level.WARN
            LogLevel.ERROR -> Telemetry.Level.ERROR
        }
    }
}

/**
 * What the host does for one plugin's calls (letta-mobile-cdgpr.5), behind the session's
 * capability and secret guards:
 *  - `host.emit` is rate-limited ([PluginRateLimits.emit], `-32008` over it) and compiled by
 *    [placement] onto the board;
 *  - `host.putAsset.end` stores into [assets] (the relay's `FileAssetStore`) only an allowed media
 *    type ([ALLOWED_MEDIA_TYPES]) within the plugin's [quota] (`-32011` otherwise); bytes already
 *    stored cost nothing;
 *  - `host.log` goes to [logSink] scrubbed by [LcpSecretGuard] here as well as on the wire (this
 *    handler may be called without the session), over [PluginRateLimits.log] dropped and counted;
 *  - `host.readElements` is refused: `canvas:read` is not granted in contract v1.
 */
@Suppress("LongParameterList")
class HostLcpHandlers(
    private val pluginId: String,
    private val placement: PlacementCompiler,
    private val assets: AssetStore,
    scrubber: SecretScrubber,
    private val limits: PluginRateLimits,
    private val quota: PluginAssetQuota,
    private val refusals: PluginRefusals,
    private val logSink: PluginLogSink = PluginLogSink.TELEMETRY,
) : LcpHostHandlers {
    private val secrets = LcpSecretGuard(scrubber)

    override suspend fun emit(emit: PluginEmit): EmitReceipt {
        if (!limits.tryEmit(pluginId)) throw refusals.refuse(pluginId, PluginRefusal.RATE_LIMITED, "over ${limits.emit.amount} emits per ${limits.emit.window}")
        return placement.emit(emit)
    }

    override suspend fun storeAsset(asset: LcpUploadedAsset): String {
        val mediaType = asset.mediaType.substringBefore(';').trim().lowercase()
        if (mediaType !in ALLOWED_MEDIA_TYPES) {
            throw refusals.refuse(pluginId, PluginRefusal.CONTENT_TYPE, "'${asset.mediaType}' is not one of $ALLOWED_MEDIA_TYPES")
        }
        val ref = AssetRefs.PREFIX + asset.sha256
        if (assets.has(ref)) return ref.also { recordAsset(0L, mediaType) }
        val size = asset.bytes.size.toLong()
        if (!quota.tryCharge(pluginId, size)) {
            throw refusals.refuse(pluginId, PluginRefusal.ASSET_QUOTA, "$size bytes would cross ${quota.limit.amount} bytes per ${quota.limit.window}")
        }
        return runCatching { assets.put(mediaType, asset.bytes).ref }
            .onFailure { quota.refund(pluginId, size) }
            .getOrThrow()
            .also { recordAsset(size, mediaType) }
    }

    override suspend fun readElements(query: ElementQuery): List<PluginElementView> =
        throw refusals.refuse(pluginId, PluginRefusal.CANVAS_READ, "host.readElements needs canvas:read, which contract v1 does not grant")

    override suspend fun log(line: LogParams) {
        if (!limits.tryLog(pluginId)) return refusals.record(pluginId, PluginRefusal.LOG_DROPPED)
        logSink.write(pluginId, scrub(line))
    }

    private fun recordAsset(bytes: Long, mediaType: String) =
        PluginHostEvents.record(PluginHostEvent.ASSET, pluginId, "bytes" to bytes.toString(), "mediaType" to mediaType)

    private fun scrub(line: LogParams): LogParams {
        val encoded = LcpWire.json.encodeToJsonElement(LogParams.serializer(), line)
        return LcpWire.json.decodeFromJsonElement(LogParams.serializer(), secrets.scrub(encoded) as JsonObject)
    }

    companion object {
        /** What a plugin may store as an asset in v1: pictures and JSON. */
        val ALLOWED_MEDIA_TYPES: Set<String> = setOf("image/png", "image/jpeg", "image/svg+xml", "application/json")
    }
}
