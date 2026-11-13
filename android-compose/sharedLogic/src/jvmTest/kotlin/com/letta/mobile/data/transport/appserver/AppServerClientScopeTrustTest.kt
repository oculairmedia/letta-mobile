package com.letta.mobile.data.transport.appserver

import com.letta.mobile.data.controller.reconnect.AppServerClientGeneration
import com.letta.mobile.data.controller.reconnect.ReconnectingAppServerClient
import com.letta.mobile.data.controller.reconnect.ReconnectingClientState
import com.letta.mobile.data.transport.iroh.IrohDialSwitchingClient
import com.letta.mobile.data.workspace.relay.WorkspaceRelayCall
import com.letta.mobile.data.workspace.relay.WorkspaceRelayClient
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Which clients may vouch for the runtime scope on a tool call frame, which is what makes the
 * dispatcher stamp a caller verified: only a [DefaultAppServerClient] constructed as the owner of
 * its App Server connection, and wrappers whose current runtime connection is one. A relay
 * ([WorkspaceRelayClient]), an Iroh dial and a bare client never are, and in production only the
 * wrapper host's own socket to its local App Server is constructed as an owner.
 */
class AppServerClientScopeTrustTest {
    @Test
    fun aSocketClientVouchesOnlyWhenConstructedAsTheOwnerOfItsConnection() {
        assertFalse(DefaultAppServerClient(SilentTransport()).vouchesForRuntimeScope)
        assertTrue(DefaultAppServerClient(SilentTransport(), vouchesForRuntimeScope = true).vouchesForRuntimeScope)
    }

    @Test
    fun theWorkspaceRelayAndAnyClientThatDoesNotSayOtherwiseAreClaimed() {
        val relay = WorkspaceRelayClient(WorkspaceRelayCall { _, _ -> error("unused") })
        assertFalse(relay.vouchesForRuntimeScope)
        assertFalse(Bare().vouchesForRuntimeScope)
    }

    @Test
    fun dualLaneFollowsItsRuntimeLaneOnly() {
        assertTrue(DualLaneAppServerClient(runtime = owner(), admin = Bare()).vouchesForRuntimeScope)
        assertFalse(DualLaneAppServerClient(runtime = Bare(), admin = owner()).vouchesForRuntimeScope)
    }

    @Test
    fun anIrohDialNeverVouchesEvenOverAnOwningClient() = runBlocking {
        val switching = IrohDialSwitchingClient(this)
        val dial = switching.bind(owner())
        try {
            assertFalse(switching.vouchesForRuntimeScope)
        } finally {
            switching.unbind(dial)
        }
    }

    @Test
    fun aReconnectingClientFollowsItsReadyGenerationAndIsClaimedWithoutOne() = runBlocking {
        val generation = AppServerClientGeneration(
            client = owner(),
            connectionState = MutableStateFlow<AppServerConnectionState>(AppServerConnectionState.Ready),
            close = {},
        )
        val reconnecting = ReconnectingAppServerClient(connect = { generation })
        assertFalse(reconnecting.vouchesForRuntimeScope, "no generation yet")
        val supervisor = reconnecting.start(this)
        try {
            withTimeout(READY_TIMEOUT_MS) { reconnecting.state.first { it is ReconnectingClientState.Ready } }
            assertTrue(reconnecting.vouchesForRuntimeScope)
        } finally {
            supervisor.cancel()
        }
    }

    @Test
    fun onlyTheWrapperHostsOwnLocalSocketIsConstructedAsAnOwner() {
        val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val owners = root.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".gradle" }
            .filter { it.isFile && it.extension == "kt" && "${File.separator}src${File.separator}" in it.path }
            .filterNot { file -> file.path.split(File.separator).any { it.endsWith("Test") || it == "test" } }
            .filter { OWNER_ARGUMENT.containsMatchIn(it.readText()) }
            .map { it.name }
            .toSortedSet()
        assertEquals(sortedSetOf("AppServerServeIrohCommand.kt"), owners)
    }

    private fun owner(): AppServerClient = DefaultAppServerClient(SilentTransport(), vouchesForRuntimeScope = true)

    /** A client that does not override the trust. */
    private class Bare : AppServerClient {
        override val events: Flow<AppServerReceivedFrame> = emptyFlow()
        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart) = error("unused")
        override suspend fun input(command: AppServerCommand.Input) = error("unused")
        override suspend fun sync(command: AppServerCommand.Sync) = error("unused")
        override suspend fun abort(command: AppServerCommand.AbortMessage) = error("unused")
        override suspend fun adminRpc(command: AppServerCommand.AdminRpc) = error("unused")
        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = error("unused")
    }

    private class SilentTransport : AppServerTransport {
        override val controlFrames = MutableSharedFlow<AppServerReceivedFrame>()
        override val streamFrames = MutableSharedFlow<AppServerReceivedFrame>()

        override suspend fun sendControl(command: AppServerCommand) = Unit
    }

    private companion object {
        const val READY_TIMEOUT_MS = 5_000L
        val OWNER_ARGUMENT = Regex("""vouchesForRuntimeScope\s*=\s*true""")
    }
}
