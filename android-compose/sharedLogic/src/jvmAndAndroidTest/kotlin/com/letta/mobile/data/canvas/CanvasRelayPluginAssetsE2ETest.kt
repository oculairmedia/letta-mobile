package com.letta.mobile.data.canvas

import com.letta.mobile.data.canvas.plugin.CanvasPluginFallback
import com.letta.mobile.data.canvas.plugin.CanvasPluginSnapshot
import com.letta.mobile.data.controller.extras.ExternalToolCaller
import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.controller.extras.ExternalToolResult
import com.letta.mobile.data.storage.InMemoryAssetStore
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * letta-mobile-s416w.5: a plugin element's snapshot travels like an image (CanvasRelayAssetsE2ETest):
 * the asset goes up before the op that refers to it, the host keeps it, and a second app that
 * receives the op fetches the picture over AssetGet.
 */
class CanvasRelayPluginAssetsE2ETest {
    private fun place(opId: String, ref: String, actor: String = CanvasSession.LOCAL_USER_ACTOR_ID) = CanvasOp.SetPluginElementOp(
        opId = opId, actorId = actor, lamport = 3L, elementId = "pe-1",
        elementType = "ext:letta.example/widget", v = 1, frame = CanvasDocumentFrame(40f, 60f, 320f, 240f),
        snapshot = CanvasPluginSnapshot(ref, "image/png", 64, 64, rev = 1),
        fallback = CanvasPluginFallback("Example widget"),
    )

    @Test
    fun aSnapshotGoesUpBeforeItsOpAndASecondAppFetchesIt() = runTest {
        val hostAssets = InMemoryAssetStore()
        val host = CanvasRelayHost(InMemoryCanvasRelayStore(), hostId = { "host-1" }, assets = hostAssets)
        val phoneAssets = InMemoryAssetStore()
        val desktopAssets = InMemoryAssetStore()
        val phone = TestApp("phone", backgroundScope, assets = phoneAssets).open(CONVERSATION)
        val desktop = TestApp("desktop", backgroundScope, assets = desktopAssets).open(CONVERSATION)
        val phoneLink = phone.connect(host)
        desktop.connect(host)
        runCurrent()

        val bytes = ByteArray(CanvasRelayHost.ASSET_CHUNK_BYTES + 17) { (it % 241).toByte() }
        val snapshot = phoneAssets.put("image/png", bytes)
        phone.edit(place("op-pe", snapshot.ref))
        runCurrent()

        val sent = phoneLink.sent
        val lastPut = sent.indexOfLast { it is CanvasRelayMessage.AssetPut }
        val publish = sent.indexOfFirst { it is CanvasRelayMessage.Publish && it.op.opId == "op-pe" }
        assertTrue(lastPut in 0 until publish, "the snapshot goes up before the op: puts end at $lastPut, publish at $publish")
        assertContentEquals(bytes, hostAssets.get(snapshot.ref))

        val onDesktop = CanvasOpProjector.pluginElementsOf(desktop.scene()).single()
        assertEquals(snapshot.ref, onDesktop.snapshot?.assetRef, "the second app has the element")
        assertTrue(!desktopAssets.has(snapshot.ref), "but not yet the picture")

        val fetched = async { desktop.client.fetchAsset(desktop.canvasId, snapshot.ref) }
        runCurrent()
        assertContentEquals(bytes, fetched.await())
        assertTrue(desktopAssets.has(snapshot.ref), "kept, so it is fetched once")
    }

    /**
     * The host's own canvas tools place an element whose snapshot the host already holds (a plugin
     * on the host wrote it): an app on the board receives the op and fetches the picture.
     */
    @Test
    fun anAgentsPlacementOnTheHostReachesAnAppThatFetchesTheSnapshot() = runTest {
        val hostAssets = InMemoryAssetStore()
        val relayStore = InMemoryCanvasRelayStore()
        val host = CanvasRelayHost(relayStore, hostId = { "host-1" }, assets = hostAssets)
        val tools = ExternalToolRegistry.hostTools(HostCanvasTools.all(HostCanvasBackend(host, relayStore, InMemoryHostCanvasDirectory())))
        val desktopAssets = InMemoryAssetStore()
        val desktop = TestApp("desktop", backgroundScope, assets = desktopAssets).open(CONVERSATION, agentId = AGENT)
        desktop.connect(host)
        runCurrent()

        val bytes = ByteArray(4096) { (it % 13).toByte() }
        val snapshot = hostAssets.put("image/png", bytes)
        val op = """{"type":"set_plugin_element","elementId":"pe-1","elementType":"ext:letta.example/widget","v":1,""" +
            """"frame":{"x":40,"y":60,"width":320,"height":240},"snapshot":{"assetRef":"${snapshot.ref}","mediaType":"image/png"},""" +
            """"fallback":{"title":"Example widget"}}"""
        val input = Json.parseToJsonElement("""{"ops":[$op]}""").jsonObject
        assertIs<ExternalToolResult.Success>(tools.invoke(CanvasToolContract.APPLY_OPS, input, ExternalToolCaller(AGENT, CONVERSATION, "toolu_1")))
        runCurrent()

        val onDesktop = CanvasOpProjector.pluginElementsOf(desktop.scene()).single()
        assertEquals(AGENT, desktop.session.opLog.getOps(desktop.canvasId).single().actorId, "published as the agent")
        val fetched = async { desktop.client.fetchAsset(desktop.canvasId, assertIs<String>(onDesktop.snapshot?.assetRef)) }
        runCurrent()
        assertContentEquals(bytes, fetched.await())
    }

    private companion object {
        const val CONVERSATION = "conv-plugin"
        const val AGENT = "agent-1"
    }
}
