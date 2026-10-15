package com.letta.mobile.data.plugin.host

import com.letta.mobile.data.canvas.compose.Sha256
import com.letta.mobile.data.model.PluginId
import com.letta.mobile.data.plugin.PluginCapability
import com.letta.mobile.data.plugin.PluginOrigin
import com.letta.mobile.data.plugin.PluginRegistryState
import com.letta.mobile.data.plugin.PluginRuntime
import com.letta.mobile.data.plugin.PluginState
import com.letta.mobile.data.plugin.ResolvedTemplates
import com.letta.mobile.data.plugin.SecretScrubber
import com.letta.mobile.data.plugin.host.PluginHostFixture.Companion.ID
import com.letta.mobile.data.plugin.host.PluginHostFixture.Companion.pkg
import com.letta.mobile.data.plugin.host.PluginHostRig.Companion.PLUGIN_ID
import com.letta.mobile.data.plugin.host.PluginHostRig.Companion.place
import com.letta.mobile.data.plugin.host.ProcessDriverFixture.Companion.SECRET
import com.letta.mobile.data.plugin.host.ProcessDriverFixture.Companion.call
import com.letta.mobile.data.plugin.view.FakeViewHost
import com.letta.mobile.data.plugin.view.FakeViewPort
import com.letta.mobile.data.plugin.view.FakeViewTransport
import com.letta.mobile.data.plugin.view.LcpView
import com.letta.mobile.data.plugin.view.PluginViewConsent
import com.letta.mobile.data.plugin.view.PluginViewSpec
import com.letta.mobile.data.plugin.view.ViewBridge
import com.letta.mobile.data.plugin.view.ViewBridgeFixtures
import com.letta.mobile.data.plugin.view.ViewBridgeOptions
import com.letta.mobile.data.plugin.view.ViewBridgeServices
import com.letta.mobile.data.plugin.view.ViewErrorCode
import com.letta.mobile.data.plugin.view.ViewLinkPolicy
import com.letta.mobile.data.plugin.wire.LcpAssetUploads
import com.letta.mobile.data.plugin.wire.LcpCallException
import com.letta.mobile.data.plugin.wire.LcpErrorCode
import com.letta.mobile.data.plugin.wire.LcpHostHandlers
import com.letta.mobile.data.plugin.wire.LcpUploadedAsset
import com.letta.mobile.plugin.api.ActionResult
import com.letta.mobile.plugin.api.ElementQuery
import com.letta.mobile.plugin.api.PluginEmit
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The plugin trust minimum (epic letta-mobile-cdgpr, letta-mobile-cdgpr.10): one test per guarantee,
 * each named for it, against real subprocesses ([FakeLcpPlugin]), the real runtime, handlers and
 * view bridge. `docs/reference/plugin-trust-model.md` maps every guarantee to its test here.
 */
class PluginTrustTest {
    private val canvasOnly = setOf(PluginCapability.CANVAS_PLACE)

    private fun withDriver(
        mode: String = "normal",
        options: (ProcessDriverOptions) -> ProcessDriverOptions = { it },
        handlers: LcpHostHandlers? = null,
        block: suspend CoroutineScope.(ProcessDriverFixture) -> Unit,
    ) = runBlocking {
        val fixture = ProcessDriverFixture(this, mode, options, handlers = handlers)
        try {
            withTimeout(2.minutes) { block(fixture) }
        } finally {
            fixture.driver.stop()
            fixture.delete()
        }
    }

    private fun withHost(block: suspend (PluginHostFixture, PluginHostRuntime) -> Unit) = runBlocking {
        val fixture = PluginHostFixture()
        val runtime = fixture.runtime()
        try {
            withTimeout(2.minutes) {
                runtime.start()
                block(fixture, runtime)
            }
        } finally {
            runtime.close()
            fixture.close()
        }
    }

    private suspend fun PluginHostRuntime.running(id: String = ID, mode: String = "normal") {
        assertIs<PluginHostResult.Done>(install(pkg(mode = mode, id = id)))
        assertIs<PluginHostResult.Done>(consent(PluginId(id), canvasOnly, emptySet()))
        assertIs<PluginHostResult.Done>(enable(PluginId(id)))
    }

    private fun PluginHostRuntime.pid(id: String): Long =
        requireNotNull(assertIs<PluginDriverState.Running>(health().single { it.pluginId == id }.driver).pid)

    private fun alive(pid: Long): Boolean = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)

    private fun ActionResult.text(): String = assertIs<ActionResult.Ok>(this).text

    private fun LcpCallException.reason(): String? = (error.data as? JsonObject)?.get("reason")?.jsonPrimitive?.content

    private fun JsonObject.errorCode(): Int = getValue("error").jsonObject.getValue("code").jsonPrimitive.int

    private fun asset(bytes: ByteArray, mediaType: String) = LcpUploadedAsset(mediaType, bytes, LcpAssetUploads.hex(Sha256.digest(bytes)))

    @Test
    fun aConsentNotCoveringTheInstalledVersionNeverStartsTheDriver() = withHost { fixture, first ->
        first.running()
        first.close()
        val registryFile = fixture.pluginsDir.resolve(PluginPackageStore.REGISTRY_FILE)
        val plugin = PluginRegistryState.decode(Files.readString(registryFile)).plugins.single()
        val startsLog = fixture.pluginsDir.resolve(ID).resolve("1.0.0").resolve("starts.log")
        val startsBefore = Files.readAllLines(startsLog).size
        val tampered = listOf(plugin.consent.copy(forVersion = "0.9.0"), plugin.consent.copy(capabilities = emptySet()))
        tampered.forEach { consent ->
            Files.writeString(registryFile, PluginRegistryState(listOf(plugin.copy(current = plugin.current.copy(consent = consent)))).encode())
            val runtime = fixture.runtime()
            try {
                runtime.start()
                val health = runtime.health().single()
                val faulted = assertIs<PluginState.Faulted>(health.state, "consent $consent")
                assertTrue("consent does not cover" in faulted.reason, faulted.reason)
                assertNull(health.driver)
                assertTrue(runtime.toolSource.tools.value.isEmpty())
            } finally {
                runtime.close()
            }
        }
        assertEquals(startsBefore, Files.readAllLines(startsLog).size, "no plugin process was spawned")
    }

    @Test
    fun theEnvironmentIsPathAndDeclaredSubstitutionsOnlyNeverAHostSecret() = withDriver { fixture ->
        assertNotNull(System.getenv("LETTA_TOKEN"), "the jvmTest task plants LETTA_TOKEN (sharedLogic/build.gradle.kts)")
        fixture.driver.start()
        val keys = fixture.driver.invoke(call("env")).text().split(",").toSet()
        assertEquals(setOf("API_TOKEN", "GREETING", "PATH"), keys)
        assertFalse("LETTA_TOKEN" in keys, "the planted host secret reached the plugin")
    }

    @Test
    fun theWorkingDirectoryIsThePackageAndAPathEscapeIsRefusedBeforeSpawning() {
        withDriver { fixture ->
            fixture.driver.start()
            assertEquals(fixture.packageDir.toFile().canonicalPath, fixture.driver.invoke(call("cwd")).text())
        }
        val escapes = listOf(PluginRuntime.Process("../escape.sh"), PluginRuntime.Process("java", cwd = "../.."))
        escapes.forEach { escape ->
            runBlocking {
                val manifest = ProcessDriverFixture.manifest("normal").copy(runtime = escape)
                val fixture = ProcessDriverFixture(this, manifest = manifest)
                try {
                    val faulted = assertIs<PluginDriverState.Faulted>(fixture.driver.start())
                    assertTrue("leaves the package" in faulted.reason, faulted.reason)
                    assertEquals(0, fixture.starts)
                } finally {
                    fixture.delete()
                }
            }
        }
    }

    @Test
    fun memoryCpuAndTaskCapsAreInTheLaunchPlan() {
        val packageDir = Files.createTempDirectory("lcp-caps").also { it.resolve("server.mjs").toFile().writeText("") }
        val pathDir = Files.createTempDirectory("lcp-caps-path").toFile()
        File(pathDir, "node").apply { writeText(""); setExecutable(true) }
        val path = pathDir.absolutePath
        val node = PluginRuntime.Process(command = "node", args = listOf("./server.mjs"))
        val caps = ProcessCaps(memoryMax = "128M", cpuQuota = "25%", tasksMax = 16, nodeHeapMegabytes = 96)
        val bus = mapOf("DBUS_SESSION_BUS_ADDRESS" to "unix:path=/run/user/0/bus")
        val scope = ProcessConfinement.SystemdScope("/usr/bin/systemd-run", "/usr/bin/env", bus)

        val confined = ProcessLaunchPlanner.plan(node, packageDir, ResolvedTemplates(emptyMap()), ProcessHostEnvironment(path, scope, caps))
        val expected = listOf(
            "/usr/bin/systemd-run", "--user", "--scope", "--quiet",
            "-p", "MemoryMax=128M", "-p", "CPUQuota=25%", "-p", "TasksMax=16", "--",
            "/usr/bin/env", "-u", "DBUS_SESSION_BUS_ADDRESS", "$path/node", "./server.mjs",
        )
        assertEquals(expected, confined.command)

        val unconfined = ProcessLaunchPlanner.plan(node, packageDir, ResolvedTemplates(emptyMap()), ProcessHostEnvironment(path, ProcessConfinement.Unconfined, caps))
        assertEquals(listOf("$path/node", "--max-old-space-size=96", "./server.mjs"), unconfined.command)
        assertEquals(ProcessCaps("256M", "50%", 64, 256), ProcessCaps(), "the default caps")
    }

    @Test
    fun aPluginIgnoringSigtermIsSigkilledOnceTheGraceRunsOut() = withDriver("ignore-term", { it.copy(killGrace = 1.seconds) }) { fixture ->
        val running = assertIs<PluginDriverState.Running>(fixture.driver.start())
        val began = System.nanoTime()
        fixture.driver.stop()
        val waitedMillis = (System.nanoTime() - began) / 1_000_000
        assertTrue(waitedMillis in 900..10_000, "stopped after $waitedMillis ms; the grace is 1000 ms")
        assertFalse(alive(requireNotNull(running.pid)))
    }

    @Test
    fun threeCrashesFaultThePluginAndWithdrawItsTools() = withHost { fixture, runtime ->
        runtime.running(mode = "crash")
        withTimeout(60.seconds) { runtime.state.first { it[PluginId(ID)]?.state is PluginState.Faulted } }
        // The registry turns Faulted just before the tool set is republished; await the withdrawal.
        withTimeout(10.seconds) { runtime.toolSource.tools.first { it.isEmpty() } }
        assertNull(runtime.health().single().driver)
        delay(1.seconds)
        assertEquals(3, Files.readAllLines(fixture.pluginsDir.resolve(ID).resolve("1.0.0").resolve("starts.log")).size)
    }

    @Test
    fun secretsNeverReachHostLogStderrTelemetryResultsOrPlacedElements() {
        val tap = PluginTelemetryTap()
        val rig = PluginHostRig(secrets = listOf(SECRET))
        val handlers = HostLcpHandlers(PLUGIN_ID, rig.placement, rig.assets, SecretScrubber(listOf(SECRET)), rig.limits, rig.quota, rig.refusals)
        val toTelemetry: (ProcessDriverOptions) -> ProcessDriverOptions = { options ->
            options.copy(onStderr = { line -> options.onStderr(line); PluginHostEvents.record(PluginHostEvent.STDERR, PLUGIN_ID, "line" to line) })
        }
        withDriver(options = toTelemetry, handlers = handlers) { fixture ->
            rig.openBoard()
            fixture.driver.start()
            assertEquals("refused ${LcpErrorCode.SECRET_REFUSED}", fixture.driver.invoke(call("emit-secret")).text())
            assertEquals("logged", fixture.driver.invoke(call("log-secret")).text())
            val result = assertFailsWith<LcpCallException> { fixture.driver.invoke(call("result-secret")) }
            assertEquals(LcpErrorCode.SECRET_REFUSED, result.code)
            fixture.driver.invoke(call("leak"))
            withTimeout(10.seconds) { while (tap.named(PluginHostEvent.STDERR).size < 2 || tap.named(PluginHostEvent.LOG).isEmpty()) delay(20) }

            assertEquals(SecretScrubber.REFUSED, tap.named(PluginHostEvent.LOG).single().attrs["message"])
            assertTrue(tap.named(PluginHostEvent.STDERR).any { it.attrs["line"] == SecretScrubber.REFUSED })
            assertTrue(tap.named(PluginHostEvent.STDERR).all { it.level == Telemetry.Level.WARN })
            assertTrue(rig.written().none { SECRET in it.toString() }, "a secret reached the canvas")
            assertTrue(fixture.stderr.none { SECRET in it })
            assertFalse(SECRET in result.message.orEmpty())
            assertTrue(tap.events().none { SECRET in it.toLine() }, "a secret reached telemetry")
        }
    }

    @Test
    fun readElementsIsRefusedBySessionAndHandlers() {
        withDriver { fixture ->
            fixture.driver.start()
            assertEquals("refused ${LcpErrorCode.CAPABILITY_REFUSED}", fixture.driver.invoke(call("read")).text())
            assertTrue(fixture.host.emits.isEmpty())
        }
        runBlocking {
            val rig = PluginHostRig()
            val refused = assertFailsWith<LcpCallException> { rig.handlers.readElements(ElementQuery()) }
            assertEquals(LcpErrorCode.CAPABILITY_REFUSED, refused.code)
            assertEquals(PluginRefusal.CANVAS_READ.wire, refused.reason())
        }
    }

    @Test
    fun aForeignKindOrElementRefusesTheWholeEmit() = runBlocking {
        val rig = PluginHostRig()
        rig.openBoard()
        val foreignKind = assertFailsWith<LcpCallException> {
            rig.handlers.emit(PluginEmit(place = listOf(place(), place(kind = "ext:other.plugin/chart"))))
        }
        assertEquals(LcpErrorCode.CAPABILITY_REFUSED, foreignKind.code)
        assertEquals(PluginRefusal.FOREIGN_KIND.wire, foreignKind.reason())
        val foreignElement = assertFailsWith<LcpCallException> {
            rig.handlers.emit(PluginEmit(place = listOf(place()), remove = listOf("p-other.plugin-e1")))
        }
        assertEquals(PluginRefusal.FOREIGN_ELEMENT.wire, foreignElement.reason())
        assertTrue(rig.written().isEmpty(), "a refused emit wrote nothing")
    }

    @Test
    fun anAssetOfAnotherTypeOrOverTheQuotaIsRefused() = runBlocking {
        val rig = PluginHostRig(quota = PluginAssetQuota(PluginRateLimit(150, 1.minutes), nowMs = { 0L }))
        val html = asset("<script>".toByteArray(), "text/html")
        val type = assertFailsWith<LcpCallException> { rig.handlers.storeAsset(html) }
        assertEquals(LcpErrorCode.UPLOAD_REFUSED, type.code)
        assertEquals(PluginRefusal.CONTENT_TYPE.wire, type.reason())
        rig.handlers.storeAsset(asset(ByteArray(100) { 1 }, "image/png"))
        val over = asset(ByteArray(100) { 2 }, "image/png")
        val quota = assertFailsWith<LcpCallException> { rig.handlers.storeAsset(over) }
        assertEquals(PluginRefusal.ASSET_QUOTA.wire, quota.reason())
        assertFalse(rig.assets.has("sha256:${html.sha256}") || rig.assets.has("sha256:${over.sha256}"))
    }

    @Test
    fun viewMessagesAreRateLimitedByDefault() = runBlocking {
        assertEquals(LcpView.MAX_MESSAGES_PER_SECOND, ViewBridgeOptions().maxMessagesPerSecond)
        val port = FakeViewPort()
        val transport = FakeViewTransport()
        val bridge = ViewBridge(ViewBridgeFixtures.spec(), port, ViewBridgeServices(FakeViewHost(), transport), ViewBridgeOptions(clockMs = { 0L }))
        val running = launch { bridge.run() }
        try {
            port.post("""{"jsonrpc":"2.0","id":0,"method":"view.ready","params":{"pageId":"widget","viewVersion":"1"}}""")
            port.next()
            repeat(LcpView.MAX_MESSAGES_PER_SECOND - 1) { port.post("""{"jsonrpc":"2.0","method":"view.log","params":{"level":"debug","message":"$it"}}""") }
            port.post("""{"jsonrpc":"2.0","id":1,"method":"view.action","params":{"action":"refresh"}}""")
            assertEquals(ViewErrorCode.RATE_LIMITED.code, port.next().errorCode())
            assertTrue(transport.calls.isEmpty(), "a rate-limited action reached the transport")
        } finally {
            running.cancel()
        }
    }

    @Test
    fun linksAreDeniedUnlessTheOwnerConsentedToUiOpenLink() = runBlocking {
        val url = """{"jsonrpc":"2.0","id":1,"method":"view.openLink","params":{"url":"https://docs.example.test/a"}}"""
        val anyLink = ViewLinkPolicy.allowlist(listOf(requireNotNull(PluginOrigin.parse("https://*.example.test"))))
        val page = ViewBridgeFixtures.spec(ViewBridgeFixtures.linkPage())
        assertEquals(ViewLinkPolicy.DenyAll, ViewBridgeServices(FakeViewHost(), FakeViewTransport()).links)
        val cases = listOf(
            Triple(PluginViewConsent.DenyAll, ViewLinkPolicy.DenyAll, ViewErrorCode.DENIED.code),
            Triple(PluginViewConsent.DenyAll, anyLink, ViewErrorCode.DENIED.code),
            Triple(PluginViewConsent { it == PluginCapability.UI_OPEN_LINK }, ViewLinkPolicy.DenyAll, ViewErrorCode.DENIED.code),
            Triple(PluginViewConsent { it == PluginCapability.UI_OPEN_LINK }, anyLink, null),
        )
        cases.forEach { (consent, links, expected) ->
            val host = FakeViewHost()
            val answer = openLink(page, ViewBridgeServices(host, FakeViewTransport(), links, consent), url)
            assertEquals(expected, answer["error"]?.let { answer.errorCode() }, "consent=$consent links=$links")
            assertEquals(if (expected == null) listOf("https://docs.example.test/a") else emptyList(), host.links)
        }
    }

    private suspend fun CoroutineScope.openLink(spec: PluginViewSpec, services: ViewBridgeServices, request: String): JsonObject {
        val port = FakeViewPort()
        val bridge = ViewBridge(spec, port, services, ViewBridgeOptions(clockMs = { 0L }))
        val running = launch { bridge.run() }
        try {
            port.post("""{"jsonrpc":"2.0","id":0,"method":"view.ready","params":{"pageId":"widget","viewVersion":"1"}}""")
            port.next()
            port.post(request)
            return port.next()
        } finally {
            running.cancel()
        }
    }

    @Test
    fun disableAllStopsEveryDriverAndWithdrawsEveryTool() = withHost { _, runtime ->
        runtime.running(ID)
        runtime.running(OTHER)
        val pids = listOf(runtime.pid(ID), runtime.pid(OTHER))
        assertEquals(setOf("fake_plot", "other_plot"), runtime.toolSource.tools.value.map { it.name }.toSet())
        runtime.disableAll()
        assertTrue(pids.none(::alive))
        assertTrue(runtime.toolSource.tools.value.isEmpty())
        assertTrue(runtime.health().all { it.state == PluginState.Installed && it.driver == null })
    }

    private companion object {
        const val OTHER = "letta.other"
    }
}
