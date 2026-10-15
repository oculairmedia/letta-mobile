package com.letta.mobile.data.plugin.host

import com.letta.mobile.data.controller.extras.ExternalToolResult
import com.letta.mobile.data.model.PluginId
import com.letta.mobile.data.plugin.PluginCapability
import com.letta.mobile.data.plugin.PluginPackageRef
import com.letta.mobile.data.plugin.PluginRegistryState
import com.letta.mobile.data.plugin.PluginState
import com.letta.mobile.data.plugin.host.PluginHostFixture.Companion.ID
import com.letta.mobile.data.plugin.host.PluginHostFixture.Companion.TOOL
import com.letta.mobile.data.plugin.host.PluginHostFixture.Companion.callTool
import com.letta.mobile.data.plugin.host.PluginHostFixture.Companion.pkg
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** [PluginHostRuntime] end to end over a real package, unpack and [FakeLcpPlugin] driver (letta-mobile-cdgpr.6). */
class PluginHostRuntimeTest {
    private val canvasOnly = setOf(PluginCapability.CANVAS_PLACE)

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

    private suspend fun PluginHostRuntime.installed(): PluginHostResult.Done = assertIs(install(pkg()))

    private suspend fun PluginHostRuntime.running() {
        installed()
        assertIs<PluginHostResult.Done>(consent(PluginId(ID), canvasOnly, emptySet()))
        assertIs<PluginHostResult.Done>(enable(PluginId(ID)))
    }

    private fun PluginHostRuntime.pid(): Long = requireNotNull(assertIs<PluginDriverState.Running>(health().single().driver).pid)

    private fun alive(pid: Long): Boolean = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)

    @Test
    fun installUnpacksAndRegistersThePackageAwaitingConsent() = withHost { fixture, runtime ->
        val plugin = assertNotNull(runtime.installed().plugin)
        assertEquals(PluginState.Installed, plugin.state)
        assertFalse(plugin.consented)
        val dir = fixture.pluginsDir.resolve(ID).resolve("1.0.0")
        assertTrue(Files.isRegularFile(dir.resolve("letta-plugin.json")) && Files.isRegularFile(dir.resolve("run.sh")))
        val saved = PluginRegistryState.decode(Files.readString(fixture.pluginsDir.resolve(PluginPackageStore.REGISTRY_FILE)))
        assertEquals(listOf(ID), saved.plugins.map { it.id })
        assertNotNull(runtime.kinds.schemaFor("ext:$ID/chart", 1))
        assertTrue(runtime.toolSource.tools.value.isEmpty())
    }

    @Test
    fun aBrokenOrMismatchedPackageIsRefused() = withHost { _, runtime ->
        assertIs<PluginHostResult.Refused>(runtime.install(byteArrayOf(1, 2, 3)))
        assertIs<PluginHostResult.Refused>(runtime.install(pkg(), expected = PluginPackageRef("0".repeat(64))))
        assertTrue(runtime.health().isEmpty())
    }

    @Test
    fun enableWithoutConsentIsRefusedAndPartialConsentIsNotRecorded() = withHost { _, runtime ->
        runtime.installed()
        val refused = assertIs<PluginHostResult.Refused>(runtime.enable(PluginId(ID)))
        assertEquals(canvasOnly, refused.consentDiff?.addedCapabilities)
        assertIs<PluginHostResult.Refused>(runtime.consent(PluginId(ID), emptySet(), emptySet()))
        assertIs<PluginHostResult.Refused>(runtime.enable(PluginId(ID)))
        assertNull(runtime.health().single().driver)
        assertTrue(runtime.toolSource.tools.value.isEmpty())
    }

    @Test
    fun consentThenEnableStartsTheDriverAndTheToolRoundTrips() = withHost { _, runtime ->
        runtime.running()
        val health = runtime.health().single()
        assertEquals(PluginState.Active, health.state)
        assertIs<PluginDriverState.Running>(health.driver)
        assertEquals(listOf(TOOL), runtime.toolSource.tools.value.map { it.name })
        assertEquals(ExternalToolResult.Success("ran plot"), runtime.callTool())
    }

    @Test
    fun disableStopsTheDriverAndUnpublishesItsTools() = withHost { _, runtime ->
        runtime.running()
        val pid = runtime.pid()
        assertIs<PluginHostResult.Done>(runtime.disable(PluginId(ID)))
        assertFalse(alive(pid))
        assertTrue(runtime.toolSource.tools.value.isEmpty())
        assertEquals(PluginState.Installed, runtime.health().single().state)
        assertNull(runtime.health().single().driver)
    }

    @Test
    fun removeStopsAndDeletesThePackageAndTheEntry() = withHost { fixture, runtime ->
        runtime.running()
        val pid = runtime.pid()
        assertIs<PluginHostResult.Done>(runtime.remove(PluginId(ID)))
        assertFalse(alive(pid))
        assertFalse(Files.exists(fixture.pluginsDir.resolve(ID)))
        assertTrue(runtime.health().isEmpty())
        assertTrue(runtime.toolSource.tools.value.isEmpty())
    }

    @Test
    fun anUpdateAddingACapabilityNeedsConsentAgain() = withHost { fixture, runtime ->
        runtime.running()
        val oldPid = runtime.pid()
        val update = assertNotNull(assertIs<PluginHostResult.Done>(runtime.install(pkg("1.1.0", listOf("canvas:place", "assets:write")))).plugin)
        assertEquals("1.1.0", update.version)
        assertEquals(PluginState.Installed, update.state)
        assertFalse(update.consented)
        assertFalse(alive(oldPid))
        assertTrue(runtime.toolSource.tools.value.isEmpty())
        assertTrue(Files.isRegularFile(fixture.pluginsDir.resolve(ID).resolve("1.1.0").resolve("run.sh")))
        val refused = assertIs<PluginHostResult.Refused>(runtime.enable(PluginId(ID)))
        assertEquals(setOf(PluginCapability.ASSETS_WRITE), refused.consentDiff?.addedCapabilities)
        assertIs<PluginHostResult.Done>(runtime.consent(PluginId(ID), canvasOnly + PluginCapability.ASSETS_WRITE, emptySet()))
        assertIs<PluginHostResult.Done>(runtime.enable(PluginId(ID)))
        assertEquals(ExternalToolResult.Success("ran plot"), runtime.callTool())
    }

    @Test
    fun anUpdateAskingNothingNewRestartsOnTheNewVersion() = withHost { _, runtime ->
        runtime.running()
        val update = assertNotNull(assertIs<PluginHostResult.Done>(runtime.install(pkg("1.0.1"))).plugin)
        assertEquals(PluginState.Active, update.state)
        assertTrue(update.consented)
        assertEquals(ExternalToolResult.Success("ran plot"), runtime.callTool())
    }

    @Test
    fun theRegistrySurvivesARuntimeRestart() = withHost { fixture, first ->
        first.running()
        first.close()
        val second = fixture.runtime()
        try {
            second.start()
            assertEquals(PluginState.Active, second.health().single().state)
            assertEquals(ExternalToolResult.Success("ran plot"), second.callTool())
        } finally {
            second.close()
        }
    }

    @Test
    fun disableAllIsTheRuntimeKillSwitch() = withHost { _, runtime ->
        runtime.running()
        val pid = runtime.pid()
        runtime.disableAll()
        assertFalse(alive(pid))
        assertEquals(PluginState.Installed, runtime.health().single().state)
        assertTrue(runtime.toolSource.tools.value.isEmpty())
    }
}
