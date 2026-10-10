package com.letta.mobile.data.meridian.endpoint

import com.letta.mobile.data.meridian.endpoint.FakeRuntimeStream.OTHER_CONVERSATION
import com.letta.mobile.data.meridian.endpoint.FakeRuntimeStream.SCOPE
import com.letta.mobile.data.meridian.endpoint.FakeRuntimeStream.ended
import com.letta.mobile.data.meridian.endpoint.FakeRuntimeStream.returned
import com.letta.mobile.data.meridian.endpoint.FakeRuntimeStream.started
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
    private val list = ShellCall("call-1", "meridian canvas list")

    @Test
    fun aRunningMeridianShellCallBindsItsConversationAndCountsInvocations() = runTest {
        calls.observe(started(list))

        val first = calls.claim("conv-a", agentId = null)
        val second = calls.claim("conv-a", agentId = "agent-a")

        assertEquals(MeridianLiveCall("call-1", SCOPE, 1), first)
        assertEquals(2, second?.invocation)
    }

    @Test
    fun theEndTheToolReturnOrTheTurnsEndClosesTheCall() = runTest {
        val scene = ShellCall("call-2", "meridian canvas scene")
        val layout = ShellCall("call-3", "meridian canvas layout")
        calls.observe(started(list))
        calls.observe(ended(list))
        calls.observe(started(scene))
        calls.observe(returned(scene))
        calls.observe(started(layout))
        calls.observe(turnFinished(SCOPE))

        assertNull(calls.claim("conv-a", null))
        assertEquals(0, calls.size())
    }

    @Test
    fun otherConversationsOtherAgentsAndNonMeridianCommandsDoNotBind() = runTest {
        calls.observe(started(ShellCall("call-b", "meridian canvas list", scope = OTHER_CONVERSATION)))
        calls.observe(started(ShellCall("call-ls", "ls -la")))
        calls.observe(started(ShellCall("call-read", "cat notes.txt", toolName = "Read")))

        assertNull(calls.claim("conv-a", null))
        assertNull(calls.claim("conv-b", agentId = "agent-z"))
        assertEquals("call-b", calls.claim("conv-b", agentId = "agent-a")?.toolCallId)
    }

    @Test
    fun subagentFramesAreIgnored() = runTest {
        calls.observe(started(list.copy(subagentId = "sub-1")))

        assertNull(calls.claim("conv-a", null))
    }

    @Test
    fun aCallWhoseEndFrameWasLostExpires() = runTest {
        calls.observe(started(list))
        now = 1_000

        assertNull(calls.claim("conv-a", null))
    }

    @Test
    fun stringToolArgsAndOtherShellToolSpellingsAreRead() = runTest {
        calls.observe(started(ShellCall("call-1", "cd /tmp && /usr/local/bin/meridian canvas list", "shell_command", argsAsString = true)))

        assertEquals("call-1", calls.claim("conv-a", null)?.toolCallId)
    }

    @Test
    fun meridianMustBeTheProgramWord() {
        assertTrue(MeridianShellCallSignal.runsMeridian("meridian canvas list"))
        assertTrue(MeridianShellCallSignal.runsMeridian("echo x | meridian canvas compose"))
        assertFalse(MeridianShellCallSignal.runsMeridian("meridian-iroh-wrapper --help"))
        assertFalse(MeridianShellCallSignal.runsMeridian("cat meridian.txt"))
    }
}
