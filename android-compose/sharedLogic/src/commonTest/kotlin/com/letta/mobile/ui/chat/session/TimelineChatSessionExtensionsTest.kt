package com.letta.mobile.ui.chat.session

import com.letta.mobile.data.chat.runtime.PermissionModeGateway
import com.letta.mobile.data.runtime.ModeChangeResult
import com.letta.mobile.data.runtime.PermissionModeRegistry
import com.letta.mobile.data.runtime.RuntimePermissionDefaults
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** letta-mobile-by4vm.98: the host extension points a TimelineChatSessionPort reads. */
@OptIn(ExperimentalCoroutinesApi::class)
class TimelineChatSessionExtensionsTest {
    private val target = TimelineChatTarget(agentId = "agent-1", agentName = "Nora", conversationId = "conv-1")

    @Test
    fun aHostWithoutWorkingDirectorySupportShowsNoChip() = runTest {
        val directory = ChatWorkingDirectoryState(backgroundScope) { FakeDirectoryTarget("/work") }
        directory.refresh()
        runCurrent()

        assertNull(directory.current.value)
    }

    @Test
    fun aSupportedHostReadsTheConversationsDirectory() = runTest {
        val directory = ChatWorkingDirectoryState(backgroundScope) { FakeDirectoryTarget("/work") }
        directory.bindSupport(true)
        directory.refresh()
        runCurrent()

        assertEquals(ChatWorkingDirectoryUiState(path = "/work", isLoading = false), directory.current.value)
    }

    @Test
    fun aRefusedChangeKeepsTheDirectoryAndAnAcceptedOneMovesIt() = runTest {
        val runtime = FakeDirectoryTarget("/work", accepts = false)
        val directory = ChatWorkingDirectoryState(backgroundScope) { runtime }
        directory.bindSupport(true)
        directory.refresh()
        runCurrent()

        directory.changeWorkingDirectory(ChatWorkingDirectory("/elsewhere"))
        runCurrent()
        assertEquals("/work", directory.current.value?.path)

        runtime.accepts = true
        directory.changeWorkingDirectory(ChatWorkingDirectory("/elsewhere"))
        runCurrent()
        assertEquals("/elsewhere", directory.current.value?.path)
        assertEquals(listOf("/elsewhere", "/elsewhere"), runtime.writes)
    }

    @Test
    fun noConversationToReadClearsThePathButKeepsTheChip() = runTest {
        var runtime: ChatWorkingDirectoryTarget? = FakeDirectoryTarget("/work")
        val directory = ChatWorkingDirectoryState(backgroundScope) { runtime }
        directory.bindSupport(true)
        directory.refresh()
        runCurrent()

        runtime = null
        directory.refresh()
        runCurrent()
        assertEquals(ChatWorkingDirectoryUiState(path = null), directory.current.value)
    }

    @Test
    fun theTimelinePortShowsAndChangesTheHostsWorkingDirectory() = runTest {
        val runtime = FakeDirectoryTarget("/work")
        val directory = ChatWorkingDirectoryState(backgroundScope) { runtime }
        directory.bindSupport(true)
        directory.refresh()
        val port = port(TimelineChatSessionOptions(workingDirectory = directory))
        runCurrent()

        assertEquals("/work", port.composer.value.workingDirectory?.path)
        assertTrue(port.capabilities.value.workingDirectory)

        port.actions.changeWorkingDirectory(ChatWorkingDirectory("/next"))
        runCurrent()
        assertEquals(listOf("/next"), runtime.writes)
        assertEquals("/next", port.composer.value.workingDirectory?.path)
    }

    @Test
    fun theTimelinePortWithoutAWorkingDirectoryHidesIt() = runTest {
        val port = port(TimelineChatSessionOptions())
        runCurrent()

        assertNull(port.composer.value.workingDirectory)
        assertFalse(port.capabilities.value.workingDirectory)
    }

    @Test
    fun theTimelinePortShowsAndChangesTheGatewaysPermissionMode() = runTest {
        val gateway = FakeModeGateway()
        val runtime = AppServerRuntimeScope("agent-1", "conv-1")
        val modeTarget = ChatPermissionModeTarget(gateway, runtime)
        val modes = GatewayPermissionModes(backgroundScope, flowOf(modeTarget)) { modeTarget }
        val port = port(TimelineChatSessionOptions(permissionModes = modes))
        runCurrent()

        assertEquals(AppServerPermissionMode.Unrestricted, port.composer.value.permissionMode?.selected)
        assertEquals(listOf(runtime), gateway.synced, "opening the conversation syncs a relaying host's mode")

        port.actions.setPermissionMode(AppServerPermissionMode.Standard)
        runCurrent()
        assertEquals(listOf(runtime to AppServerPermissionMode.Standard), gateway.requested)
        assertEquals(AppServerPermissionMode.Standard, port.composer.value.permissionMode?.selected)
    }

    @Test
    fun aGatewayWithoutARegistryShowsNoChipAndIgnoresRequests() = runTest {
        val gateway = FakeModeGateway(permissionModes = null)
        val modeTarget = ChatPermissionModeTarget(gateway, AppServerRuntimeScope("agent-1", "conv-1"))
        val modes = GatewayPermissionModes(backgroundScope, flowOf(modeTarget)) { modeTarget }
        val port = port(TimelineChatSessionOptions(permissionModes = modes))
        runCurrent()

        assertNull(port.composer.value.permissionMode)
        port.actions.setPermissionMode(AppServerPermissionMode.Strict)
        runCurrent()
        assertEquals(emptyList(), gateway.requested)
    }

    @Test
    fun anAcknowledgedErrorIsHiddenUntilTheOwnerClearsItThenShowsAgain() {
        val shown = TimelineLocalState().acknowledge("boom")
        assertNull(shown.pageError("boom"))
        assertEquals("other", shown.pageError("other"))
        assertEquals(shown, shown.forgetAcknowledgedOnceCleared("boom"), "kept while the owner still holds it")

        val cleared = shown.forgetAcknowledgedOnceCleared(null)
        assertEquals("boom", cleared.pageError("boom"))
        assertEquals(cleared, cleared.acknowledge(null))
    }

    private fun TestScope.port(options: TimelineChatSessionOptions): TimelineChatSessionPort {
        val port = TimelineChatSessionPort(FakeTimelineChatSession(), target, backgroundScope, options)
        backgroundScope.launch { port.uiState.collect {} }
        backgroundScope.launch { port.composer.collect {} }
        backgroundScope.launch { port.capabilities.collect {} }
        return port
    }
}

private class FakeModeGateway(
    override val permissionModes: PermissionModeRegistry? =
        PermissionModeRegistry(MutableStateFlow(RuntimePermissionDefaults.DEFAULT_MODE)),
) : PermissionModeGateway {
    val requested = mutableListOf<Pair<AppServerRuntimeScope, AppServerPermissionMode>>()
    val synced = mutableListOf<AppServerRuntimeScope>()

    override suspend fun setPermissionMode(runtime: AppServerRuntimeScope, mode: AppServerPermissionMode): Boolean {
        requested += runtime to mode
        return permissionModes?.change(runtime, mode) { ModeChangeResult.Confirmed } ?: false
    }

    override suspend fun syncPermissionMode(runtime: AppServerRuntimeScope) {
        synced += runtime
    }
}

private class FakeDirectoryTarget(
    private val path: String,
    var accepts: Boolean = true,
) : ChatWorkingDirectoryTarget {
    val writes = mutableListOf<String>()

    override suspend fun read(): String = path

    override suspend fun write(path: String): Boolean {
        writes += path
        return accepts
    }
}
