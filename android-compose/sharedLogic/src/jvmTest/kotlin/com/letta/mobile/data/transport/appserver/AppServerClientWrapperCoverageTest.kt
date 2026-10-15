package com.letta.mobile.data.transport.appserver

import com.letta.mobile.data.controller.reconnect.AppServerClientGeneration
import com.letta.mobile.data.controller.reconnect.ReconnectingAppServerClient
import com.letta.mobile.data.controller.reconnect.ReconnectingClientState
import com.letta.mobile.data.transport.appserver.AppServerClientForwardingProbe.lanesReached
import com.letta.mobile.data.transport.appserver.AppServerClientForwardingProbe.newLog
import com.letta.mobile.data.transport.appserver.AppServerClientForwardingProbe.recordingClient
import com.letta.mobile.data.transport.appserver.AppServerClientForwardingProbe.requests
import com.letta.mobile.data.transport.appserver.AppServerClientForwardingProbe.unforwarded
import com.letta.mobile.data.transport.iroh.IrohDialSwitchingClient
import com.letta.mobile.data.workspace.relay.WorkspaceRelayClient
import java.io.File
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * letta-mobile-by4vm.83 (replaces the by4vm.82 reflection check, which could not fail): every
 * [AppServerClient] wrapper must hand every request to the client it wraps. A request a wrapper
 * does not override falls to the interface default, which throws UnsupportedOperationException:
 * DualLane's `execute_command` did (by4vm.82, the Iroh host answered `/context-limit` with
 * capability_unavailable) and ReconnectingAppServerClient lacked six (#1990).
 *
 * Each request is called on the wrapper over recording clients ([AppServerClientForwardingProbe]).
 * Adding a request to [AppServerClient] makes this fail until every wrapper forwards it; adding an
 * [AppServerClient] implementation makes [everyImplementationIsClassified] fail until it is listed
 * here as a wrapper (and covered) or as a leaf.
 */
class AppServerClientWrapperCoverageTest {
    @Test
    fun probeCatchesAWrapperThatForwardsOnlyTheAbstractRequests() {
        val seen = newLog()
        val missing = unforwarded(AbstractOnlyWrapper(recordingClient("inner", seen)), seen)

        val defaults = requests.map { it.name } - AbstractOnlyWrapper.FORWARDED
        assertTrue(defaults.isNotEmpty(), "AppServerClient has no default requests left to probe")
        assertEquals(defaults, missing)
    }

    @Test
    fun dualLaneSendsEveryRequestToExactlyOneLane() {
        val seen = newLog()
        val dual = DualLaneAppServerClient(runtime = recordingClient("runtime", seen), admin = recordingClient("admin", seen))

        val lanes = requests.associate { it.name to lanesReached(dual, it, seen) }

        assertEquals(emptyMap(), lanes.filterValues { it.size != 1 })
    }

    @Test
    fun reconnectingForwardsEveryRequestToTheReadyGeneration() = runBlocking {
        val seen = newLog()
        val generation = AppServerClientGeneration(
            client = recordingClient("generation", seen),
            connectionState = MutableStateFlow<AppServerConnectionState>(AppServerConnectionState.Ready),
            close = {},
        )
        val reconnecting = ReconnectingAppServerClient(connect = { generation })
        val supervisor = reconnecting.start(this)
        try {
            withTimeout(READY_TIMEOUT_MS) { reconnecting.state.first { it is ReconnectingClientState.Ready } }
            assertEquals(emptyList(), unforwarded(reconnecting, seen))
        } finally {
            supervisor.cancel()
        }
    }

    @Test
    fun irohDialSwitchingForwardsEveryRequestToTheBoundDial() = runBlocking {
        val seen = newLog()
        val switching = IrohDialSwitchingClient(this)
        val dial = switching.bind(recordingClient("dial", seen))
        try {
            assertEquals(emptyList(), unforwarded(switching, seen))
        } finally {
            switching.unbind(dial)
        }
    }

    @Test
    fun everyImplementationIsClassified() {
        val classified = (WRAPPERS + LEAVES).map { it.name }.toSortedSet()
        assertEquals(classified, sharedLogicImplementations().toSortedSet())
    }

    /** Concrete [AppServerClient] classes compiled from sharedLogic's main source sets. */
    private fun sharedLogicImplementations(): List<String> {
        val root = File(AppServerClient::class.java.protectionDomain.codeSource.location.toURI())
        require(root.isDirectory) { "expected sharedLogic main classes as a directory, got $root" }
        val loader = AppServerClient::class.java.classLoader
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .map { it.relativeTo(root).path.removeSuffix(".class").replace(File.separatorChar, '.') }
            .map { Class.forName(it, false, loader) }
            .filter { AppServerClient::class.java.isAssignableFrom(it) && it != AppServerClient::class.java }
            .filterNot { it.isInterface || Modifier.isAbstract(it.modifiers) }
            .map { it.name }
            .toList()
    }

    /** Implements only the abstract requests, so every default request falls to its stub. */
    private class AbstractOnlyWrapper(private val inner: AppServerClient) : AppServerClient {
        override val events: Flow<AppServerReceivedFrame> = inner.events
        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart) = inner.runtimeStart(command)
        override suspend fun input(command: AppServerCommand.Input) = inner.input(command)
        override suspend fun sync(command: AppServerCommand.Sync) = inner.sync(command)
        override suspend fun abort(command: AppServerCommand.AbortMessage) = inner.abort(command)
        override suspend fun adminRpc(command: AppServerCommand.AdminRpc) = inner.adminRpc(command)
        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) =
            inner.sendExternalToolResponse(command)

        companion object {
            val FORWARDED = setOf("runtimeStart", "input", "sync", "abort", "adminRpc", "sendExternalToolResponse")
        }
    }

    private companion object {
        const val READY_TIMEOUT_MS = 5_000L

        /** Clients that wrap another [AppServerClient] and must forward every request. */
        val WRAPPERS = listOf(
            DualLaneAppServerClient::class.java,
            ReconnectingAppServerClient::class.java,
            IrohDialSwitchingClient::class.java,
        )

        /**
         * Implementations that are not wrappers: the socket client itself, and the workspace relay,
         * which serves only workspace requests over its own call (the rest stay unsupported).
         */
        val LEAVES = listOf(DefaultAppServerClient::class.java, WorkspaceRelayClient::class.java)
    }
}
