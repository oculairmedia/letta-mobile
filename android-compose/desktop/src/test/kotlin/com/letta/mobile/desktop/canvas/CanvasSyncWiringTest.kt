@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop.canvas

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.canvas.CanvasConversationOptions
import com.letta.mobile.data.canvas.CanvasId
import com.letta.mobile.data.canvas.CanvasOp
import com.letta.mobile.data.canvas.CanvasRelayClient
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.CanvasSyncHealth
import com.letta.mobile.data.canvas.CanvasSyncTransport
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.data.canvas.LoopbackCanvasSyncTransport
import com.letta.mobile.ui.canvas.CanvasWorkspace
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CanvasSyncStatusBadgeTest {

    private class ScriptedTransport(val state: MutableStateFlow<CanvasSyncHealth>) : CanvasSyncTransport {
        override suspend fun publish(canvasId: CanvasId, op: CanvasOp) = Unit
        override fun subscribe(canvasId: CanvasId): Flow<CanvasOp> = emptyFlow()
        override fun health(canvasId: CanvasId): StateFlow<CanvasSyncHealth> = state
    }

    @Test
    fun localOnlyAndOfflineQueuedAreVisibleToSemantics() = runComposeUiTest {
        val health = MutableStateFlow<CanvasSyncHealth>(CanvasSyncHealth.LocalOnly("no host"))
        val session = runBlocking {
            CanvasSession.getOrCreateForConversation(
                InMemoryCanvasDocumentStore(),
                "conv-badge",
                CanvasConversationOptions(syncTransport = ScriptedTransport(health)),
            )
        }
        setContent { CanvasWorkspace(session = session) }

        onNode(hasContentDescription("Local only — not syncing", substring = true)).assertExists()

        health.value = CanvasSyncHealth.OfflineQueued(3)
        waitForIdle()
        onNode(hasContentDescription("Offline — 3 edits queued", substring = true)).assertExists()

        health.value = CanvasSyncHealth.Synced
        waitForIdle()
        onNode(hasContentDescription("Synced", substring = true)).assertExists()
        onNode(hasContentDescription("Offline", substring = true)).assertDoesNotExist()
    }

    @Test
    fun aLoopbackBoardSaysItIsLocalOnly() = runComposeUiTest {
        val session = runBlocking {
            CanvasSession.getOrCreateForConversation(
                InMemoryCanvasDocumentStore(),
                "conv-loopback",
                CanvasConversationOptions(syncTransport = LoopbackCanvasSyncTransport()),
            )
        }
        setContent { CanvasWorkspace(session = session) }
        onNode(hasContentDescription("Local only — not syncing", substring = true)).assertExists()
    }
}

class CanvasProductionWiringTest {

    @Test
    fun canvasUsesActiveAuthenticatedChannelAndNeverBindsEndpoint() {
        // Exact class and one instance: the desktop's canvas transport is the relay client the Iroh
        // attachment drives, never a loopback that merely behaves like one.
        val relay = DesktopCanvasHostSync.relay
        assertEquals(CanvasRelayClient::class, DesktopCanvasHostSync.syncTransport::class)
        assertSame(relay, DesktopCanvasHostSync.syncTransport)
        assertSame(relay, DesktopCanvasHostSync.presenceTransport)
        assertSame(relay, DesktopCanvasHostSync.client.relay)

        // The production wiring on both apps reaches the host through the app's existing, already
        // authenticated channel: no endpoint, accept loop, direct-peer transport or loopback of its own.
        val root = File("..").canonicalFile
        val wiring = listOf(
            "desktop/src/main/kotlin/com/letta/mobile/desktop/canvas/DesktopCanvasHostSync.kt",
            "desktop/src/main/kotlin/com/letta/mobile/desktop/DesktopIrohBindings.kt",
            "app/src/main/java/com/letta/mobile/di/AppModule.kt",
            "core/android-data/src/main/java/com/letta/mobile/data/session/SessionChannelTransportFactory.kt",
            "sharedLogic/src/jvmAndAndroid/kotlin/com/letta/mobile/data/transport/iroh/IrohCanvasRelayClient.kt",
            "sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/canvas/CanvasRelayClient.kt",
        ).associateWith { File(root, it).readText() }
        val forbidden = listOf(
            "Endpoint.bind(",
            ".acceptNext(",
            "IrohCanvasSyncTransport(",
            "IrohCanvasPresenceTransport(",
            "LoopbackCanvasSyncTransport(",
            "InMemoryCanvasPresenceTransport(",
        )
        for ((path, source) in wiring) {
            for (pattern in forbidden) {
                if (path.endsWith("CanvasRelayClient.kt") && pattern == "InMemoryCanvasPresenceTransport(") continue // its in-process cursors
                assertFalse(pattern in source, "$path must not use $pattern")
            }
        }
        val appModule = wiring.getValue("app/src/main/java/com/letta/mobile/di/AppModule.kt")
        assertTrue("): com.letta.mobile.data.canvas.CanvasSyncTransport = relay" in appModule, "Android provides the relay client as its canvas transport")
        assertTrue("): com.letta.mobile.data.canvas.CanvasPresenceTransport = relay" in appModule, "Android relays presence through it too")
        assertTrue(
            "canvasClient?.attach(transport.readyHandle, scope)" in wiring.getValue("core/android-data/src/main/java/com/letta/mobile/data/session/SessionChannelTransportFactory.kt"),
            "Android attaches the relay to the session graph's own Iroh transport",
        )
        assertTrue(
            "DesktopCanvasHostSync.client.attach(transport.readyHandle)" in wiring.getValue("desktop/src/main/kotlin/com/letta/mobile/desktop/DesktopIrohBindings.kt"),
            "desktop attaches the relay to its own Iroh transport",
        )
        assertTrue(
            "handle?.openConnection" in wiring.getValue("sharedLogic/src/jvmAndAndroid/kotlin/com/letta/mobile/data/transport/iroh/IrohCanvasRelayClient.kt"),
            "the relay connection is opened from the host connection's own endpoint",
        )
    }
}
