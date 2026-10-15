package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.PluginId
import com.letta.mobile.data.plugin.PluginCapability
import com.letta.mobile.data.plugin.PluginHealth
import com.letta.mobile.data.plugin.PluginState
import com.letta.mobile.data.plugin.ZipPluginPackageReader
import com.letta.mobile.data.plugin.catalog.PluginAdminRpc
import com.letta.mobile.data.plugin.catalog.PluginCatalogResult
import com.letta.mobile.data.plugin.catalog.PluginGrants
import com.letta.mobile.data.plugin.catalog.PluginInstallRequest
import com.letta.mobile.data.plugin.catalog.PluginPageWire
import com.letta.mobile.data.plugin.host.FakeLcpPlugin
import com.letta.mobile.data.plugin.host.PluginHostFixture
import com.letta.mobile.data.plugin.host.PluginHostRuntime
import com.letta.mobile.data.plugin.host.ProcessDriverFixture
import com.letta.mobile.data.plugin.remote.AdminRpcPluginCatalog
import com.letta.mobile.data.plugin.remote.AdminRpcPluginViewTransport
import com.letta.mobile.data.plugin.remote.PLUGINS_UNSUPPORTED
import com.letta.mobile.data.plugin.remote.PluginAdminLink
import com.letta.mobile.data.plugin.remote.PluginPackageUploader
import com.letta.mobile.data.plugin.view.PluginViewActionCall
import com.letta.mobile.data.plugin.view.PluginViewActionOutcome
import com.letta.mobile.data.plugin.view.PluginViewPageRef
import com.letta.mobile.data.plugin.view.PluginViewUnavailableException
import com.letta.mobile.data.storage.InMemoryAssetStore
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.iroh.IrohFrameCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * letta-mobile-cdgpr.7: the client's [AdminRpcPluginCatalog] and [AdminRpcPluginViewTransport]
 * against a host's real admin_rpc stream server (frame codec, capability gate, router) and
 * [PluginAdminHandlers] over a real [PluginHostRuntime] whose plugin is [FakeLcpPlugin]. The
 * package goes up the way a client's relay `AssetPut` puts it: into the host's asset store.
 */
class PluginAdminRpcRoundTripTest {
    private val admin = setOf(IrohPeerCapabilities.ADMIN_FULL)
    private val grants = PluginGrants(setOf(PluginCapability.CANVAS_PLACE, PluginCapability.UI_PAGES), emptySet(), setOf(PAGE))
    private val id = PluginId(PLUGIN)

    @Test
    fun installConsentEnableDisableAndRemoveRoundTripThroughTheHost() = withHost { host ->
        val catalog = host.catalog(admin)
        val installed = catalog.install(PACKAGE, SHA256)
        val request = assertIs<PluginCatalogResult.Ok<*>>(installed, "$installed").value
        assertEquals(
            PluginInstallRequest(
                id, "Fake", "1.0.0", "letta", SHA256, "process",
                listOf(PluginCapability.CANVAS_PLACE, PluginCapability.UI_PAGES), emptyList(), listOf(PAGE),
            ),
            request,
        )
        assertEquals(listOf(PluginState.Installed), catalog.plugins.value.map { it.state })
        assertContains(assertIs<PluginCatalogResult.Refused>(catalog.enable(id)).reason, "consent")

        assertEquals(PluginCatalogResult.Ok(Unit), catalog.consent(id, grants))
        assertEquals(PluginCatalogResult.Ok(Unit), catalog.enable(id))
        val running = catalog.plugins.value.single()
        assertEquals(PluginState.Active, running.state)
        assertTrue(running.enabled)
        assertEquals(PluginCatalogResult.Ok(PluginHealth.Ok), catalog.health(id))

        assertEquals(PluginCatalogResult.Ok(Unit), catalog.disable(id))
        assertEquals(PluginState.Installed, catalog.plugins.value.single().state)
        assertEquals(PluginCatalogResult.Ok(Unit), catalog.enable(id))
        assertEquals(PluginCatalogResult.Ok(Unit), catalog.disableAll())
        assertFalse(catalog.plugins.value.single().enabled)
        assertEquals(PluginCatalogResult.Ok(Unit), catalog.remove(id))
        assertTrue(catalog.plugins.value.isEmpty())
        assertTrue(host.runtime.state.value.plugins.isEmpty())
    }

    @Test
    fun aLiveViewReadsItsPageAndRunsOnlyViewActionsWithValidInput() = withHost { host ->
        val catalog = host.catalog(admin)
        assertIs<PluginCatalogResult.Ok<*>>(catalog.install(PACKAGE, SHA256))
        assertEquals(PluginCatalogResult.Ok(Unit), catalog.consent(id, grants))
        assertEquals(PluginCatalogResult.Ok(Unit), catalog.enable(id))
        // A chat-only peer: what a phone viewing the canvas holds.
        val view = AdminRpcPluginViewTransport(host.link(IrohPeerCapabilities.CHAT_ROLE))

        assertEquals(PAGE_HTML, view.readPage(PluginViewPageRef(PLUGIN, "1.0.0", PAGE)).decodeToString())
        val page = assertIs<PluginCatalogResult.Ok<PluginPageWire>>(view.page(PluginViewPageRef(PLUGIN, "1.0.0", PAGE))).value
        assertEquals(setOf("plot", "toggle"), page.viewActions.keys)
        assertFailsWith<PluginViewUnavailableException> { view.readPage(PluginViewPageRef(PLUGIN, "0.9.0", PAGE)) }

        assertEquals(PluginViewActionOutcome.Done("ran toggle"), view.action(viewCall("toggle", buildJsonObject { put("on", true) })))
        val agentOnly = assertIs<PluginViewActionOutcome.Failed>(view.action(viewCall("secret")))
        assertContains(agentOnly.message, "not an action a view of $PLUGIN may call")
        val badInput = assertIs<PluginViewActionOutcome.Failed>(view.action(viewCall("toggle", buildJsonObject { put("on", "yes") })))
        assertContains(badInput.message, "does not hold")

        catalog.disable(id)
        assertContains(assertIs<PluginViewActionOutcome.Failed>(view.action(viewCall("toggle", buildJsonObject { put("on", true) }))).message, "not enabled")
    }

    @Test
    fun installWithAWrongSha256IsRefusedAndNothingIsInstalled() = withHost { host ->
        val catalog = host.catalog(admin)
        val wrong = ZipPluginPackageReader.sha256Hex(byteArrayOf(1, 2, 3))

        val refused = assertIs<PluginCatalogResult.Refused>(catalog.install(PACKAGE, wrong))

        assertContains(refused.reason, "install_refused")
        assertContains(refused.reason, "not the pinned $wrong")
        assertTrue(host.runtime.state.value.plugins.isEmpty())
        val malformed = assertIs<PluginCatalogResult.Refused>(host.catalog(admin).install(PACKAGE, "not-a-hash"))
        assertContains(malformed.reason, "sha256")
    }

    @Test
    fun cancellingConsentRemovesTheNewlyInstalledPluginOnly() = withHost { host ->
        val catalog = host.catalog(admin)
        assertIs<PluginCatalogResult.Ok<*>>(catalog.install(PACKAGE, SHA256))
        assertEquals(1, host.runtime.state.value.plugins.size)

        assertEquals(PluginCatalogResult.Ok(Unit), catalog.cancelInstall(id))

        assertTrue(host.runtime.state.value.plugins.isEmpty())
        assertEquals(PluginCatalogResult.Ok(Unit), catalog.cancelInstall(id), "nothing staged: nothing removed")
    }

    @Test
    fun managementNeedsAdminFullWhileAViewNeedsOnlyChat() = withHost { host ->
        val chat = host.link(IrohPeerCapabilities.CHAT_ROLE)
        val catalog = AdminRpcPluginCatalog(chat, host.uploader)

        assertIs<PluginCatalogResult.Refused>(catalog.refresh())
        assertIs<PluginCatalogResult.Refused>(catalog.install(PACKAGE, SHA256))
        assertTrue(host.runtime.state.value.plugins.isEmpty(), "the gate refuses before the handler runs")
        // The view call passes the gate and reaches the handler, which refuses an unknown plugin.
        val outcome = assertIs<PluginViewActionOutcome.Failed>(AdminRpcPluginViewTransport(chat).action(viewCall("toggle")))
        assertContains(outcome.message, "$PLUGIN is not installed")
    }

    @Test
    fun aHostWithoutAPluginRuntimeAdvertisesNothingAndClientsSendNothing() = runBlocking {
        val bare = AdminRpcRouter().also { CapabilityUnavailable.register(it, PluginAdminRpc.METHODS, "plugin host") }
        val link = RelayLink(hostCall(bare, admin), IrohNodeConnection.advertisedCapabilities(bare).toSet())
        val catalog = AdminRpcPluginCatalog(link) { _, _ -> error("never uploaded") }
        val view = AdminRpcPluginViewTransport(link)

        assertFalse(catalog.supported)
        assertEquals(PluginCatalogResult.Refused(PLUGINS_UNSUPPORTED), catalog.install(PACKAGE, SHA256))
        assertEquals(PluginCatalogResult.Refused(PLUGINS_UNSUPPORTED), catalog.refresh())
        assertEquals(PluginViewActionOutcome.Unavailable(PLUGINS_UNSUPPORTED), view.action(viewCall("toggle")))
        assertFailsWith<PluginViewUnavailableException> { view.readPage(PluginViewPageRef(PLUGIN, "1.0.0", PAGE)) }
        assertTrue(link.calls.isEmpty(), "a host without plugins_v1 is never sent plugins.*")
    }

    private fun viewCall(action: String, input: JsonObject = JsonObject(emptyMap())) =
        PluginViewActionCall(PLUGIN, "canvas-1", "element-1", action, input)

    /** One host: a plugin runtime, its asset store, and a router with [PluginAdminHandlers]. */
    private class Host(val runtime: PluginHostRuntime, val hostCall: (Set<String>) -> HostCall) {
        val assets = InMemoryAssetStore()
        val router = AdminRpcRouter().also { PluginAdminHandlers(runtime, assets).register(it) }

        /** What a client's relay `AssetPut` does: the bytes land in the host's store under their ref. */
        val uploader = PluginPackageUploader { bytes, _ -> assets.put("application/zip", bytes).ref }

        fun link(capabilities: Set<String>) = RelayLink(hostCall(capabilities), IrohNodeConnection.advertisedCapabilities(router).toSet())

        fun catalog(capabilities: Set<String>) = AdminRpcPluginCatalog(link(capabilities), uploader)
    }

    private fun withHost(block: suspend (Host) -> Unit) = runBlocking {
        val fixture = PluginHostFixture()
        val runtime = fixture.runtime()
        lateinit var host: Host
        host = Host(runtime) { capabilities -> hostCall(host.router, capabilities) }
        try {
            withTimeout(2.minutes) {
                runtime.start()
                assertTrue(PluginAdminRpc.CAPABILITY in IrohNodeConnection.advertisedCapabilities(host.router))
                block(host)
            }
        } finally {
            runtime.close()
            fixture.close()
        }
    }

    private fun interface HostCall {
        suspend operator fun invoke(method: String, params: JsonObject): AppServerInboundFrame.AdminRpcResponse
    }

    /** The client's end of the connection: each call goes to [host]; [hostCapabilities] are what it advertised. */
    private class RelayLink(private val host: HostCall, override val hostCapabilities: Set<String>) : PluginAdminLink {
        val calls = mutableListOf<String>()

        override suspend fun adminRpc(method: String, params: JsonObject): AppServerInboundFrame.AdminRpcResponse {
            calls += method
            return host(method, params)
        }
    }

    private companion object {
        const val PLUGIN = "letta.fake"
        const val PAGE = "chart"
        const val PAGE_HTML = "<!doctype html><title>chart</title><div id=chart></div>"

        val PACKAGE: ByteArray by lazy { pkg() }
        val SHA256: String by lazy { ZipPluginPackageReader.sha256Hex(PACKAGE) }

        /** The host end of one Iroh connection: its admin_rpc stream server with the real capability gate. */
        fun CoroutineScope.hostCall(router: AdminRpcRouter, capabilities: Set<String>): HostCall {
            val server = AdminRpcStreamServer(
                router = router,
                authenticated = AtomicBoolean(true),
                peerSupportsFrameParts = { true },
                capabilityGate = { method ->
                    val required = IrohPeerCapabilities.forAdminMethod(method)
                    if (IrohPeerCapabilities.isAllowed(capabilities, required)) null else required
                },
            )
            val scope = this
            return HostCall { method, params ->
                val stream = FakeBiStream()
                val job = with(server) { scope.launchHandler(stream) }
                val request = AppServerProtocol.encodeCommand(AppServerCommand.AdminRpc(requestId = "admin-1", method = method, params = params))
                stream.recv.chunks.send(IrohFrameCodec.encodeFrame(request))
                stream.recv.chunks.send(ByteArray(0))
                job.join()
                val raw = IrohFrameCodec.Decoder().let { decoder -> stream.send.writes.flatMap { decoder.feed(it) } }.single()
                AppServerProtocol.decodeFrame(raw, AppServerChannel.Control).frame as AppServerInboundFrame.AdminRpcResponse
            }
        }

        /** [FakeLcpPlugin] with a page and three actions: `plot` (agent and view), `toggle` (view), `secret` (agent only). */
        fun pkg(): ByteArray {
            val manifest = """
                {"manifestVersion":1,"id":"$PLUGIN","name":"Fake","version":"1.0.0","publisher":"letta","contract":{"version":1},
                 "runtime":{"kind":"process","command":"sh","args":["run.sh"]},
                 "capabilities":["canvas:place","ui:pages"],
                 "elements":{"chart":{"schemaVersion":1,"page":"$PAGE","props":{"type":"object","additionalProperties":false,"properties":{"title":{"type":"string","maxLength":200}}}}},
                 "actions":{"plot":{"description":"Plot a chart","visibility":["agent","view"],"input":{"type":"object"}},
                            "toggle":{"visibility":["view"],"input":{"type":"object","additionalProperties":false,"properties":{"on":{"type":"boolean"}}}},
                            "secret":{"description":"Agent only","visibility":["agent"],"input":{"type":"object"}}},
                 "pages":{"$PAGE":{"html":"pages/chart.html"}}}
            """.trimIndent()
            val java = File(System.getProperty("java.home"), "bin/java").absolutePath
            val run = "exec '$java' -Xshare:auto -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xmx64m " +
                "-cp '${ProcessDriverFixture.classpath}' ${FakeLcpPlugin::class.java.name} --mode=normal\n"
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                mapOf("letta-plugin.json" to manifest, "run.sh" to run, "pages/chart.html" to PAGE_HTML).forEach { (name, text) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
            }
            return out.toByteArray()
        }
    }

    private class FakeBiStream : AdminRpcBiStream {
        val recv = FakeRecvStream()
        val send = FakeSendStream()
        override fun recv(): AdminRpcRecvStream = recv
        override fun send(): AdminRpcSendStream = send
    }

    private class FakeRecvStream : AdminRpcRecvStream {
        val chunks = Channel<ByteArray>(Channel.UNLIMITED)
        override suspend fun read(maxBytes: UInt): ByteArray = chunks.receive()
    }

    private class FakeSendStream : AdminRpcSendStream {
        val writes = mutableListOf<ByteArray>()
        override suspend fun writeAll(bytes: ByteArray) {
            writes += bytes
        }

        override suspend fun finish() = Unit
    }
}
