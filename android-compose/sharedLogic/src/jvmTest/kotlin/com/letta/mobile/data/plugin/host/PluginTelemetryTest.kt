package com.letta.mobile.data.plugin.host

import com.letta.mobile.data.canvas.compose.Sha256
import com.letta.mobile.data.controller.extras.ExternalToolResult
import com.letta.mobile.data.model.PluginId
import com.letta.mobile.data.plugin.PluginCapability
import com.letta.mobile.data.plugin.SecretScrubber
import com.letta.mobile.data.plugin.host.PluginHostFixture.Companion.ID
import com.letta.mobile.data.plugin.host.PluginHostFixture.Companion.callTool
import com.letta.mobile.data.plugin.host.PluginHostFixture.Companion.pkg
import com.letta.mobile.data.plugin.host.PluginHostRig.Companion.PLUGIN_ID
import com.letta.mobile.data.plugin.view.FakeViewHost
import com.letta.mobile.data.plugin.view.FakeViewPort
import com.letta.mobile.data.plugin.view.FakeViewTransport
import com.letta.mobile.data.plugin.view.ViewBridge
import com.letta.mobile.data.plugin.view.ViewBridgeFixtures
import com.letta.mobile.data.plugin.view.ViewBridgeOptions
import com.letta.mobile.data.plugin.view.ViewBridgeServices
import com.letta.mobile.data.plugin.wire.LcpAssetUploads
import com.letta.mobile.data.plugin.wire.LcpCallException
import com.letta.mobile.data.plugin.wire.LcpUploadedAsset
import com.letta.mobile.data.plugin.wire.LogParams
import com.letta.mobile.plugin.api.ElementQuery
import com.letta.mobile.plugin.api.LogLevel
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The plugin events [Telemetry] recorded from the moment this tap was made, oldest first. */
class PluginTelemetryTap {
    private val mark: Telemetry.Event? = Telemetry.snapshot().firstOrNull()

    init {
        check(Telemetry.enabled.get()) { "telemetry is off in this JVM" }
    }

    fun events(): List<Telemetry.Event> =
        Telemetry.snapshot().takeWhile { it !== mark }.filter { it.tag == PluginHostEvents.TAG }.reversed()

    fun named(event: PluginHostEvent): List<Telemetry.Event> = events().filter { it.name == event.eventName }
}

/** The plugin host's typed telemetry (letta-mobile-cdgpr.10): every event in [PluginHostEvent] has a live emitter. */
class PluginTelemetryTest {
    @Test
    fun everyEventInTheSetIsEmittedBySomePath() {
        val tap = PluginTelemetryTap()
        runBlocking {
            handlerEvents()
            PluginPackageStore(unreadableRegistry()).load()
            PluginAssetQuota(file = PluginAssetQuotaFile(unreadableRegistry().resolve(PluginPackageStore.REGISTRY_FILE))).load()
            viewEvents()
        }
        runtimeEvents(tap)

        val emitted = tap.events().map { it.name }.toSet()
        val missing = PluginHostEvent.entries.map { it.eventName }.toSet() - emitted
        assertTrue(missing.isEmpty(), "dead events, nothing emitted them: $missing")
        assertEquals(PluginHostEvent.entries.map { it.eventName }.toSet(), emitted, "every plugin event is one of the typed set")

        val invoke = tap.named(PluginHostEvent.INVOKE).first { it.attrs["plugin"] == ID }
        assertEquals("plot", invoke.attrs["action"])
        assertEquals("ok", invoke.attrs["outcome"])
        assertNotNull(invoke.durationMs, "an invoke carries its latency")
        assertTrue(tap.named(PluginHostEvent.REFUSED).any { it.attrs["reason"] == PluginRefusal.CANVAS_READ.wire })
        assertEquals("100", tap.named(PluginHostEvent.ASSET).single().attrs["bytes"])
        assertEquals("runtime", tap.named(PluginHostEvent.KILL_SWITCH).single().attrs["scope"])
        assertTrue(tap.named(PluginHostEvent.STDERR).all { it.level == Telemetry.Level.WARN })
    }

    /** ASSET, REFUSED and LOG, from one plugin's handlers. */
    private suspend fun handlerEvents() {
        val rig = PluginHostRig()
        val handlers = HostLcpHandlers(PLUGIN_ID, rig.placement, rig.assets, SecretScrubber(emptyList()), rig.limits, rig.quota, rig.refusals)
        val bytes = ByteArray(100) { it.toByte() }
        handlers.storeAsset(LcpUploadedAsset("image/png", bytes, LcpAssetUploads.hex(Sha256.digest(bytes))))
        assertFailsWith<LcpCallException> { handlers.readElements(ElementQuery()) }
        handlers.log(LogParams(LogLevel.INFO, "hello"))
    }

    private fun unreadableRegistry() = Files.createTempDirectory("plugins-unreadable").also {
        Files.writeString(it.resolve(PluginPackageStore.REGISTRY_FILE), "{not json")
    }

    /** VIEW_OPEN and VIEW_FAIL: a page that completes its handshake, and one naming the wrong page. */
    private suspend fun CoroutineScope.viewEvents() {
        listOf("widget", "not-this-page").forEach { pageId ->
            val port = FakeViewPort()
            val bridge = ViewBridge(ViewBridgeFixtures.spec(), port, ViewBridgeServices(FakeViewHost(), FakeViewTransport()), ViewBridgeOptions(clockMs = { 0L }))
            val running = launch { bridge.run() }
            port.post("""{"jsonrpc":"2.0","id":0,"method":"view.ready","params":{"pageId":"$pageId","viewVersion":"1"}}""")
            port.next()
            running.cancel()
        }
    }

    /** The lifecycle events, the invoke, stderr, the kill switch, and a crash loop's restarts and fault. */
    private fun runtimeEvents(tap: PluginTelemetryTap) = runBlocking {
        val fixture = PluginHostFixture()
        val runtime = fixture.runtime()
        val canvasOnly = setOf(PluginCapability.CANVAS_PLACE)
        try {
            withTimeout(3.minutes) {
                runtime.start()
                assertIs<PluginHostResult.Refused>(runtime.install(byteArrayOf(1, 2, 3)))
                runtime.install(pkg())
                runtime.consent(PluginId(ID), canvasOnly, emptySet())
                runtime.enable(PluginId(ID))
                assertEquals(ExternalToolResult.Success("ran plot"), runtime.callTool())
                runtime.install(pkg("1.0.1"))
                runtime.disable(PluginId(ID))
                runtime.enable(PluginId(ID))
                withTimeout(30.seconds) { while (tap.named(PluginHostEvent.STDERR).isEmpty()) delay(20) }
                runtime.disableAll()
                runtime.remove(PluginId(ID))
                runtime.install(pkg(mode = "crash", id = CRASHING))
                runtime.consent(PluginId(CRASHING), canvasOnly, emptySet())
                runtime.enable(PluginId(CRASHING))
                // FAULT is recorded just after the registry commits the Faulted state; await the event itself.
                withTimeout(60.seconds) { while (tap.named(PluginHostEvent.FAULT).none { it.attrs["plugin"] == CRASHING }) delay(20) }
            }
        } finally {
            runtime.close()
            fixture.close()
        }
    }

    private companion object {
        const val CRASHING = "letta.crashy"
    }
}
