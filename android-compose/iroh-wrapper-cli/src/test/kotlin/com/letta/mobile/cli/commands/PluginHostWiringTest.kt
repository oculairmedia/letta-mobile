package com.letta.mobile.cli.commands

import com.letta.mobile.data.canvas.CanvasRelayHost
import com.letta.mobile.data.canvas.HostCanvasBackend
import com.letta.mobile.data.canvas.InMemoryCanvasRelayStore
import com.letta.mobile.data.canvas.InMemoryHostCanvasDirectory
import com.letta.mobile.data.canvas.plugin.PluginKindCatalog
import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.plugin.PluginActionTools
import com.letta.mobile.data.plugin.host.LateBoundPluginKinds
import com.letta.mobile.data.storage.InMemoryAssetStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** serve-iroh's plugin host wiring (letta-mobile-cdgpr.6): `--plugins=off` is the global kill switch. */
class PluginHostWiringTest {
    private fun canvas(): PluginHostCanvas {
        val store = InMemoryCanvasRelayStore()
        val kinds = LateBoundPluginKinds()
        val backend = HostCanvasBackend(CanvasRelayHost(store, hostId = { "host-1" }), store, InMemoryHostCanvasDirectory(), kinds = kinds)
        return PluginHostCanvas(backend, InMemoryAssetStore(), kinds)
    }

    private fun withScope(block: suspend (CoroutineScope, Path) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dir = Files.createTempDirectory("plugins-wiring")
        try {
            block(scope, dir)
        } finally {
            scope.cancel()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun pluginsOffConstructsNothingAndPublishesNoTools() = withScope { scope, dir ->
        val canvas = canvas()
        val registry = ExternalToolRegistry.factoryDefault()
        val pluginsDir = dir.resolve("plugins")
        val runtime = startPluginHost(PluginHostFlags(PluginHostFlags.OFF, pluginsDir.toString()), canvas, registry, scope)
        assertNull(runtime)
        assertFalse(registry.removeSource(PluginActionTools.SOURCE_ID), "no plugin tool source is registered")
        assertSame(PluginKindCatalog.Empty, canvas.kinds.target)
        assertFalse(Files.exists(pluginsDir), "the plugins dir is not touched")
    }

    @Test
    fun pluginsOnStartsTheRuntimeAndRegistersItsToolSource() = withScope { scope, dir ->
        val canvas = canvas()
        val registry = ExternalToolRegistry.factoryDefault()
        val runtime = startPluginHost(PluginHostFlags(PluginHostFlags.ON, dir.toString()), canvas, registry, scope)
        assertNotNull(runtime)
        try {
            assertNotSame(PluginKindCatalog.Empty, canvas.kinds.target)
            assertTrue(runtime!!.health().isEmpty())
            assertTrue(registry.removeSource(PluginActionTools.SOURCE_ID), "the plugin tool source is registered")
        } finally {
            runtime?.close()
        }
    }

    @Test
    fun flagsDefaultOnUnderTheHomeLettaDir() {
        val flags = PluginHostFlags()
        assertFalse(flags.off)
        assertTrue(PluginHostFlags("OFF").off)
        assertEquals(Path.of("/home/u", ".letta", "plugins"), flags.root("/home/u"))
        assertEquals(Path.of("/x/p"), PluginHostFlags(dir = "/x/p").root("/home/u"))
    }
}
