package com.letta.mobile.ui.chat.session

import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    private fun TestScope.port(options: TimelineChatSessionOptions): TimelineChatSessionPort {
        val port = TimelineChatSessionPort(FakeTimelineChatSession(), target, backgroundScope, options)
        backgroundScope.launch { port.uiState.collect {} }
        backgroundScope.launch { port.composer.collect {} }
        backgroundScope.launch { port.capabilities.collect {} }
        return port
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
