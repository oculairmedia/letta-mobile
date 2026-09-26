package com.letta.mobile.data.runtime

import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.data.transport.appserver.snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject

/**
 * letta-mobile-qygvv.7: working-directory changes go through `change_device_state`
 * and are confirmed by the scope's `update_device_status` — never `runtime_start`.
 */
class AppServerTurnEngineDeviceStateTest {
    private val runtime = AppServerRuntimeScope("agent-1", "conv-1")

    @Test
    fun setWorkingDirectorySendsChangeDeviceStateAndConfirmsFromDeviceStatus() = runTest {
        val client = DeviceStateClient { command -> DeviceStatusFixture.inDirectory(command.payload.cwd).frame(command.runtime) }
        val engine = AppServerTurnEngine(client = client)

        assertTrue(engine.setWorkingDirectory("agent-1", "conv-1", "/work/project/"))

        val sent = client.changeCommands.single()
        assertEquals(runtime, sent.runtime)
        assertEquals("/work/project/", sent.payload.cwd)
        assertNull(sent.payload.mode)
        assertEquals(0, client.runtimeStartCount, "cwd change must not re-issue runtime_start")
    }

    @Test
    fun setWorkingDirectoryReturnsFalseWhenServerKeepsTheOldDirectory() = runTest {
        // A rejected (missing) directory is answered with a snapshot that still carries
        // the old cwd; that must not confirm, and the wait stays bounded.
        val client = DeviceStateClient { command -> DeviceStatusFixture.inDirectory("/old").frame(command.runtime) }
        val engine = AppServerTurnEngine(client = client)

        assertFalse(engine.setWorkingDirectory("agent-1", "conv-1", "/gone"))

        assertEquals(DeviceStateChanger.DEFAULT_DEVICE_STATE_TIMEOUT_MS, currentTime)
        assertEquals(1, client.changeCommands.size)
        assertEquals(0, client.runtimeStartCount, "timeout must not fall back to runtime_start")
    }

    @Test
    fun setWorkingDirectoryTimesOutWhenNoDeviceStatusArrives() = runTest {
        val client = DeviceStateClient { null }
        val engine = AppServerTurnEngine(client = client)

        assertFalse(engine.setWorkingDirectory("agent-1", "conv-1", "/work"))

        assertEquals(DeviceStateChanger.DEFAULT_DEVICE_STATE_TIMEOUT_MS, currentTime)
        assertEquals(0, client.runtimeStartCount)
    }

    @Test
    fun setWorkingDirectoryIgnoresDeviceStatusForAnotherConversation() = runTest {
        val otherConversation = AppServerRuntimeScope("agent-1", "conv-other")
        val client = DeviceStateClient { command -> DeviceStatusFixture.inDirectory(command.payload.cwd).frame(otherConversation) }
        val engine = AppServerTurnEngine(client = client)

        assertFalse(engine.setWorkingDirectory("agent-1", "conv-1", "/work"))
        assertEquals(0, client.runtimeStartCount)
    }

    @Test
    fun setWorkingDirectoryReturnsFalseWhenTheSendFails() = runTest {
        val client = DeviceStateClient(sendFailure = IllegalStateException("socket closed")) { null }
        val engine = AppServerTurnEngine(client = client)

        assertFalse(engine.setWorkingDirectory("agent-1", "conv-1", "/work"))
        assertEquals(0L, currentTime)
        assertEquals(0, client.runtimeStartCount)
    }

    @Test
    fun deviceStatusSnapshotReadsKnownFieldsAndToleratesUnknownOnes() {
        val snapshot = DeviceStatusFixture(cwd = "/w", modeWire = "acceptEdits", withUnknownField = true).frame(runtime).snapshot
        assertEquals("/w", snapshot.currentWorkingDirectory)
        assertEquals(AppServerPermissionMode.AcceptEdits, snapshot.currentPermissionMode)
        assertEquals(DEVICE_STATUS_TEST_CWD_REVISION, snapshot.cwdRevision)

        val unknownMode = DeviceStatusFixture(modeWire = "someFutureMode").frame(runtime).snapshot
        assertNull(unknownMode.currentWorkingDirectory)
        assertNull(unknownMode.currentPermissionMode)
    }

    @Test
    fun normalizeWorkingDirectoryDropsTrailingSeparatorsButKeepsRoot() {
        assertEquals("/work", normalizeWorkingDirectory(" /work/ "))
        assertEquals("/", normalizeWorkingDirectory("/"))
        assertEquals("C:\\dev", normalizeWorkingDirectory("C:\\dev\\"))
    }
}

/**
 * Answers each `change_device_state` with the frame [reply] builds (or nothing), emitted on
 * [events] synchronously from the send — the tightest ordering a real server can produce.
 */
private class DeviceStateClient(
    private val sendFailure: Throwable? = null,
    private val reply: (AppServerCommand.ChangeDeviceState) -> AppServerInboundFrame.UpdateDeviceStatus?,
) : AppServerClient {
    override val events: Flow<AppServerReceivedFrame> = MutableSharedFlow(extraBufferCapacity = 16)
    val changeCommands = mutableListOf<AppServerCommand.ChangeDeviceState>()
    var runtimeStartCount = 0
        private set

    override suspend fun changeDeviceState(command: AppServerCommand.ChangeDeviceState) {
        sendFailure?.let { throw it }
        changeCommands += command
        val frame = reply(command) ?: return
        (events as MutableSharedFlow<AppServerReceivedFrame>).tryEmit(
            AppServerReceivedFrame(channel = AppServerChannel.Control, frame = frame, raw = JsonObject(emptyMap())),
        )
    }

    override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart): AppServerInboundFrame.RuntimeStartResponse {
        runtimeStartCount += 1
        return AppServerInboundFrame.RuntimeStartResponse(requestId = command.requestId, success = true)
    }

    override suspend fun input(command: AppServerCommand.Input) = error("input is not used by these tests")

    override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse =
        error("sync is not used by these tests")

    override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse =
        error("abort is not used by these tests")

    override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse =
        error("admin_rpc is not used by these tests")

    override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) =
        error("external tools are not used by these tests")
}
