package com.letta.mobile.data.meridian.endpoint

import com.letta.mobile.data.meridian.endpoint.FakeRuntimeStream.scope
import com.letta.mobile.data.meridian.endpoint.FakeRuntimeStream.toolEnd
import com.letta.mobile.data.meridian.endpoint.FakeRuntimeStream.toolReturn
import com.letta.mobile.data.meridian.endpoint.FakeRuntimeStream.toolStart
import com.letta.mobile.data.meridian.endpoint.FakeRuntimeStream.turnFinished
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeridianLiveCallsTest {
    private var now = 0L
    private val calls = MeridianLiveCalls(nowMs = { now }, maxAgeMs = 1_000)

    @Test
    fun `a running meridian shell call binds its conversation and counts invocations`() = runTest {
        calls.observe(toolStart(scope(), "call-1", "meridian canvas list"))

        val first = calls.claim("conv-a", agentId = null)
        val second = calls.claim("conv-a", agentId = "agent-a")

        assertEquals(MeridianLiveCall("call-1", scope(), 1), first)
        assertEquals(2, second?.invocation)
    }

    @Test
    fun `the end, the tool return or the turn's end closes the call`() = runTest {
        calls.observe(toolStart(scope(), "call-1", "meridian canvas list"))
        calls.observe(toolEnd(scope(), "call-1"))
        calls.observe(toolStart(scope(), "call-2", "meridian canvas scene"))
        calls.observe(toolReturn(scope(), "call-2"))
        calls.observe(toolStart(scope(), "call-3", "meridian canvas layout"))
        calls.observe(turnFinished(scope()))

        assertNull(calls.claim("conv-a", null))
        assertEquals(0, calls.size())
    }

    @Test
    fun `other conversations, other agents and non-meridian commands do not bind`() = runTest {
        calls.observe(toolStart(scope(conversation = "conv-b"), "call-b", "meridian canvas list"))
        calls.observe(toolStart(scope(), "call-ls", "ls -la"))
        calls.observe(toolStart(scope(), "call-read", "cat notes.txt", toolName = "Read"))

        assertNull(calls.claim("conv-a", null))
        assertNull(calls.claim("conv-b", agentId = "agent-z"))
        assertEquals("call-b", calls.claim("conv-b", agentId = "agent-a")?.toolCallId)
    }

    @Test
    fun `subagent frames are ignored`() = runTest {
        calls.observe(toolStart(scope(), "call-sub", "meridian canvas list", subagentId = "sub-1"))

        assertNull(calls.claim("conv-a", null))
    }

    @Test
    fun `a call whose end frame was lost expires`() = runTest {
        calls.observe(toolStart(scope(), "call-1", "meridian canvas list"))
        now = 1_000

        assertNull(calls.claim("conv-a", null))
    }

    @Test
    fun `string tool args and other shell tool spellings are read`() = runTest {
        calls.observe(toolStart(scope(), "call-1", "cd /tmp && /usr/local/bin/meridian canvas list", toolName = "shell_command", argsAsString = true))

        assertEquals("call-1", calls.claim("conv-a", null)?.toolCallId)
    }

    @Test
    fun `meridian must be the program word`() {
        assertTrue(MeridianShellCallSignal.runsMeridian("meridian canvas list"))
        assertTrue(MeridianShellCallSignal.runsMeridian("echo x | meridian canvas compose"))
        assertFalse(MeridianShellCallSignal.runsMeridian("meridian-iroh-wrapper --help"))
        assertFalse(MeridianShellCallSignal.runsMeridian("cat meridian.txt"))
    }
}
