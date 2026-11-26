package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.controller.node.FakeAppServerController
import com.letta.mobile.data.controller.node.iroh.IrohNodeEndpoint
import com.letta.mobile.data.controller.node.iroh.IrohNodeProtocolHandler
import com.letta.mobile.data.controller.node.iroh.auth.IrohAuthPolicy
import computer.iroh.Connection
import computer.iroh.PathChangeCallback
import computer.iroh.PathEvent
import computer.iroh.PathEventCallback
import computer.iroh.PathSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Pins the connection-level path watchers that [IrohAppServerTransport.attachPathWatchers]
 * registers in production: watchPaths and watchPathEvents must attach on a real dialed
 * connection and deliver real path observations — the current path list snapshot and the
 * path events fired when the connection closes.
 *
 * The sync watcher registration used to spawn its task on the caller's JVM thread with
 * no Tokio runtime context, which panicked ("there is no reactor running"); iroh-ffi 1.2.0
 * spawns watcher tasks on the runtime handle captured while the connection is created.
 * [IrohEndpointWatcherAttachTest] pins the same mechanism hermetically (endpoint watchers,
 * no dial); this test exercises the exact production connection surface.
 *
 * Relays are disabled on both ends, so the dial never leaves this machine: it runs in the default jvmTest.
 */
class IrohConnectionPathWatcherLiveE2ETest {

    @Test
    fun connectionPathWatchersAttachAndDeliverObservations(): Unit = runBlocking {
        val alpn = "/letta/pw/1".encodeToByteArray()
        val serverAccepted = CompletableDeferred<Unit>()
        val server = IrohNodeEndpoint(
            binding = loopbackHostBinding(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            authPolicy = IrohAuthPolicy.PeerAllowlist(setOf("unused-app-server-peer")),
            protocolHandlers = listOf(object : IrohNodeProtocolHandler {
                override val alpn = alpn

                override fun authorize(remoteEndpointId: String) = true

                override suspend fun accept(connection: Connection, remoteEndpointId: String) {
                    // Park this connection so the client stays connected while the
                    // watchers are exercised; the endpoint scope is torn down at the end.
                    serverAccepted.await()
                }
            }),
        )
        val client = bindLoopbackEndpoint()
        try {
            server.create()
            server.start(FakeAppServerController())
            val connection = client.connect(server.addr(), alpn)

            val pathsObserved = CompletableDeferred<List<PathSnapshot>>()
            connection.watchPaths(object : PathChangeCallback {
                override suspend fun onChange(paths: List<PathSnapshot>) {
                    pathsObserved.complete(paths)
                }
            })
            val pathEventObserved = CompletableDeferred<PathEvent>()
            connection.watchPathEvents(object : PathEventCallback {
                override suspend fun onEvent(event: PathEvent) {
                    pathEventObserved.complete(event)
                }
            })

            withTimeout(PATH_OBSERVATION_TIMEOUT) {
                // The path list watchable delivers its current (non-empty) state on attach.
                val paths = pathsObserved.await()
                assertTrue(paths.isNotEmpty(), "watchPaths must deliver at least one active path snapshot")
                // Closing the connection fires a deterministic PathEvent the watcher must deliver.
                connection.close(0L, ByteArray(0))
                val closedEvent = pathEventObserved.await()
                assertNotNull(closedEvent, "watchPathEvents must deliver a close PathEvent")
                serverAccepted.complete(Unit)
                Unit
            }
        } finally {
            client.shutdown()
            client.close()
            server.shutdown()
        }
    }

    private companion object {
        val PATH_OBSERVATION_TIMEOUT = 15.seconds
    }
}
