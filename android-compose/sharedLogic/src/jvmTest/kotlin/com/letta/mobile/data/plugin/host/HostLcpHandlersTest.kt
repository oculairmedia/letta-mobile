package com.letta.mobile.data.plugin.host

import com.letta.mobile.data.plugin.SecretScrubber
import com.letta.mobile.data.plugin.host.PluginHostRig.Companion.PLUGIN
import com.letta.mobile.data.plugin.host.PluginHostRig.Companion.PLUGIN_ID
import com.letta.mobile.data.plugin.host.PluginHostRig.Companion.place
import com.letta.mobile.data.plugin.wire.LcpAssetUploads
import com.letta.mobile.data.plugin.wire.LcpCallException
import com.letta.mobile.data.plugin.wire.LcpErrorCode
import com.letta.mobile.data.plugin.wire.LcpUploadedAsset
import com.letta.mobile.data.plugin.wire.LogParams
import com.letta.mobile.data.canvas.compose.Sha256
import com.letta.mobile.plugin.api.ElementQuery
import com.letta.mobile.plugin.api.LogLevel
import com.letta.mobile.plugin.api.PluginEmit
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** letta-mobile-cdgpr.5: assets within quota and type, scrubbed and rate-limited logs, emits rate-limited, no canvas reads. */
class HostLcpHandlersTest {
    private fun asset(bytes: ByteArray, mediaType: String = "image/png") =
        LcpUploadedAsset(mediaType, bytes, LcpAssetUploads.hex(Sha256.digest(bytes)))

    private fun LcpCallException.reason(): String? = (error.data as? JsonObject)?.get("reason")?.jsonPrimitive?.content

    @Test
    fun anAllowedAssetIsStoredAndCharged() = runTest {
        val rig = PluginHostRig()
        val bytes = ByteArray(100) { it.toByte() }

        val ref = rig.handlers.storeAsset(asset(bytes, "image/svg+xml; charset=utf-8"))

        assertTrue(rig.assets.has(ref))
        assertEquals("image/svg+xml", rig.assets.mediaType(ref))
        assertEquals(100L, rig.quota.used(PLUGIN_ID))
    }

    @Test
    fun anAssetOverTheQuotaIsRefusedAndNotStored() = runTest {
        val rig = PluginHostRig(quota = PluginAssetQuota(PluginRateLimit(150, kotlin.time.Duration.parse("1d")), nowMs = { 0L }))
        rig.handlers.storeAsset(asset(ByteArray(100) { 1 }))
        val second = asset(ByteArray(100) { 2 })

        val error = assertFailsWith<LcpCallException> { rig.handlers.storeAsset(second) }

        assertEquals(LcpErrorCode.UPLOAD_REFUSED, error.code)
        assertEquals("asset_quota", error.reason())
        assertFalse(rig.assets.has("sha256:${second.sha256}"))
        assertEquals(100L, rig.quota.used(PLUGIN_ID))
        assertEquals(1, rig.refusals.count(PLUGIN_ID, PluginRefusal.ASSET_QUOTA))
    }

    @Test
    fun bytesAlreadyStoredCostNothing() = runTest {
        val rig = PluginHostRig(quota = PluginAssetQuota(PluginRateLimit(150, kotlin.time.Duration.parse("1d")), nowMs = { 0L }))
        val same = ByteArray(100) { 1 }

        val first = rig.handlers.storeAsset(asset(same))
        val again = rig.handlers.storeAsset(asset(same))

        assertEquals(first, again)
        assertEquals(100L, rig.quota.used(PLUGIN_ID))
    }

    @Test
    fun aDisallowedContentTypeIsRefusedAndNotStored() = runTest {
        val rig = PluginHostRig()
        val html = asset("<script>".encodeToByteArray(), "text/html")

        val error = assertFailsWith<LcpCallException> { rig.handlers.storeAsset(html) }

        assertEquals(LcpErrorCode.UPLOAD_REFUSED, error.code)
        assertEquals("content_type", error.reason())
        assertFalse(rig.assets.has("sha256:${html.sha256}"))
        assertEquals(0L, rig.quota.used(PLUGIN_ID))
    }

    @Test
    fun aLogLineWithASecretIsScrubbed() = runTest {
        val rig = PluginHostRig(secrets = listOf("hunter2-token"))

        rig.handlers.log(LogParams(LogLevel.INFO, "auth with hunter2-token", mapOf("key" to "hunter2-token", "ok" to "fine")))

        val (plugin, line) = rig.logged.single()
        assertEquals(PLUGIN, plugin)
        assertEquals(SecretScrubber.REFUSED, line.message)
        assertEquals(mapOf("key" to SecretScrubber.REFUSED, "ok" to "fine"), line.fields)
    }

    @Test
    fun logLinesOverTheRateAreDroppedAndCounted() = runTest {
        val rig = PluginHostRig(limits = PluginRateLimits(log = PluginRateLimit(2), nowMs = { 0L }))

        repeat(3) { rig.handlers.log(LogParams(LogLevel.INFO, "line $it")) }

        assertEquals(listOf("line 0", "line 1"), rig.logged.map { it.second.message })
        assertEquals(1, rig.refusals.count(PLUGIN_ID, PluginRefusal.LOG_DROPPED))
    }

    @Test
    fun emitsOverTheRateAreRefusedAfterN() = runTest {
        val rig = PluginHostRig(limits = PluginRateLimits(emit = PluginRateLimit(2), nowMs = { 0L })).apply { openBoard() }
        repeat(2) { rig.handlers.emit(PluginEmit(place = listOf(place()))) }

        val error = assertFailsWith<LcpCallException> { rig.handlers.emit(PluginEmit(place = listOf(place()))) }

        assertEquals(LcpErrorCode.OVERLOADED, error.code)
        assertEquals("rate_limited", error.reason())
        assertEquals(2, rig.written().count { it is com.letta.mobile.data.canvas.CanvasOp.SetPluginElementOp })
    }

    @Test
    fun readElementsIsRefusedForWantOfCanvasRead() = runTest {
        val rig = PluginHostRig()

        val error = assertFailsWith<LcpCallException> { rig.handlers.readElements(ElementQuery()) }

        assertEquals(LcpErrorCode.CAPABILITY_REFUSED, error.code)
        assertEquals("canvas:read", (error.error.data as JsonObject)["capability"]?.jsonPrimitive?.content)
    }
}
