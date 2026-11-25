package com.letta.mobile.data.transport.iroh

import computer.iroh.AddrChangeCallback
import computer.iroh.Endpoint
import computer.iroh.EndpointAddr
import computer.iroh.EndpointOptions
import computer.iroh.HomeRelayCallback
import computer.iroh.NetworkChangeCallback
import computer.iroh.RelayMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

/**
 * The Iroh watcher registration API (watchAddr / watchHomeRelay / watchNetworkChange,
 * and the connection-level watchPaths / watchPathEvents it shares its task-spawn
 * mechanism with) must be callable from a plain JVM thread.
 *
 * iroh-ffi 1.1.0 spawned the watcher task with tokio::spawn on the CALLER's thread.
 * Every async iroh-ffi call runs its future through async_compat, which enters a
 * global tokio runtime context for the duration of its polls — but a sync FFI method
 * like watchAddr has no such context, so Handle::current() panicked with "there is
 * no reactor running, must be called from the context of a Tokio 1.x runtime":
 * the attach threw and the transport logged it as its watch_failed telemetry at
 * every connect.
 * iroh-ffi 1.2.0 fixed this at the root: it captures the runtime handle while an
 * async call runs and spawns watcher tasks on that handle instead.
 *
 * No JVM or Android thread can ever hold a tokio context, so the Kotlin side cannot
 * work around this; the failure was identical on desktop JVM, Android JNI and Windows.
 * This test binds a real endpoint hermetically (relays disabled, no dial, no accept
 * loop — same shape as [IrohEndpointSecretKeyRoundtripTest]) and asserts the watcher
 * attach succeeds AND delivers its initial value. It must fail if the binding ever
 * regresses to spawning on the caller's thread.
 */
class IrohEndpointWatcherAttachTest {

    @Test
    fun watchersAttachFromForeignThreadAndDeliverInitialState() = runBlocking {
        val endpoint = Endpoint.bind(EndpointOptions(relayMode = RelayMode.disabled()))
        try {
            val addrDelivered = CompletableDeferred<EndpointAddr>()
            val relaysDelivered = CompletableDeferred<List<String>>()
            // Hold the handles: dropping them aborts the watcher task.
            val handles = listOf(
                endpoint.watchAddr(object : AddrChangeCallback {
                    override suspend fun onChange(addr: EndpointAddr) {
                        addrDelivered.complete(addr)
                    }
                }),
                endpoint.watchHomeRelay(object : HomeRelayCallback {
                    override suspend fun onChange(relays: List<String>) {
                        relaysDelivered.complete(relays)
                    }
                }),
                // network_change() has no initial value: it only fires on a real
                // network-stack change, so this watcher is pinned to attach without
                // throwing. The spawn mechanism is the one already proven by the
                // two deliveries awaited below.
                endpoint.watchNetworkChange(object : NetworkChangeCallback {
                    override suspend fun onChange() = Unit
                }),
            )
            // n0_watcher streams yield the current value first, so both watchables
            // deliver immediately without any network activity.
            val (addr, relays) = withTimeout(WATCH_INITIAL_EVENT_TIMEOUT) {
                addrDelivered.await() to relaysDelivered.await()
            }
            assertNotNull(addr, "watchAddr must deliver the initial EndpointAddr")
            assertNotNull(relays, "watchHomeRelay must deliver the initial relay list")
            assertEquals(3, handles.size, "all three watcher handles must remain live")
        } finally {
            endpoint.shutdown()
        }
    }

    private companion object {
        val WATCH_INITIAL_EVENT_TIMEOUT = 10.seconds
    }
}
