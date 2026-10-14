package com.letta.mobile.data.meridian.endpoint

import com.letta.mobile.data.meridian.endpoint.FakeRuntimeStream.OTHER_CONVERSATION
import com.letta.mobile.data.meridian.endpoint.FakeRuntimeStream.SCOPE
import com.letta.mobile.data.meridian.endpoint.FakeRuntimeStream.ended
import com.letta.mobile.data.meridian.endpoint.FakeRuntimeStream.returned
import com.letta.mobile.data.meridian.endpoint.FakeRuntimeStream.started
import com.letta.mobile.data.meridian.endpoint.FakeRuntimeStream.turnFinished
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeridianLiveCallsTest {
    private var now = 0L
    private var minted = 0
    private val calls = MeridianLiveCalls(nowMs = { now }, newToken = { "token-${++minted}" }, maxAgeMs = 1_000)
    private val list = ShellCall("call-1", "meridian canvas list")

    @Test
    fun aRunningMeridianShellCallIssuesOneTokenThatBindsItAndCountsInvocations() = runTest {
        calls.observe(started(list))

        val token = calls.issueToken("call-1")
        val first = calls.claim("token-1")
        val second = calls.claim("token-1")

        assertEquals("token-1", token)
        assertEquals(MeridianLiveCall("call-1", SCOPE, 1), first)
        assertEquals(2, second?.invocation)
    }

    @Test
    fun aTokenIsIssuedOnlyOncePerCall() = runTest {
        calls.observe(started(list))

        calls.issueToken("call-1")

        assertNull(calls.issueToken("call-1", waitMs = 0))
    }

    @Test
    fun issuingWaitsForAStartFrameStillOnItsWay() = runTest {
        val token = async { calls.issueToken("call-1", waitMs = 500) }
        runCurrent()
        calls.observe(started(list))

        assertEquals("token-1", token.await())
    }

    @Test
    fun unknownAndNonMeridianCallsGetNoToken() = runTest {
        calls.observe(started(ShellCall("call-ls", "ls -la")))
        calls.observe(started(ShellCall("call-read", "cat notes.txt", toolName = "Read")))

        assertNull(calls.issueToken("call-ls", waitMs = 100))
        assertNull(calls.issueToken("call-read", waitMs = 100))
        assertNull(calls.issueToken("call-nobody-started", waitMs = 100))
    }

    @Test
    fun theEndTheToolReturnOrTheTurnsEndRevokesTheToken() = runTest {
        val scene = ShellCall("call-2", "meridian canvas scene")
        val layout = ShellCall("call-3", "meridian canvas layout")
        listOf(list, scene, layout).forEach { calls.observe(started(it)) }
        val tokens = listOf("call-1", "call-2", "call-3").map { calls.issueToken(it) }
        calls.observe(ended(list))
        calls.observe(returned(scene))
        calls.observe(turnFinished(SCOPE))

        tokens.forEach { assertNull(calls.claim(requireNotNull(it))) }
        assertEquals(0, calls.size())
    }

    @Test
    fun onlyTheIssuedTokenBindsNotTheConversationOrAnotherCallsToken() = runTest {
        calls.observe(started(list))
        calls.observe(started(ShellCall("call-b", "meridian canvas list", scope = OTHER_CONVERSATION)))
        calls.issueToken("call-1")

        assertNull(calls.claim("conv-a"))
        assertNull(calls.claim("token-2"))
        assertNull(calls.claim(""))
        assertEquals("call-1", calls.claim("token-1")?.toolCallId)
        assertEquals("call-b", calls.claim(requireNotNull(calls.issueToken("call-b")))?.toolCallId)
    }

    @Test
    fun subagentFramesAreIgnored() = runTest {
        calls.observe(started(list.copy(subagentId = "sub-1")))

        assertNull(calls.issueToken("call-1", waitMs = 100))
    }

    @Test
    fun aCallWhoseEndFrameWasLostExpiresWithItsToken() = runTest {
        calls.observe(started(list))
        val token = requireNotNull(calls.issueToken("call-1"))
        now = 1_000

        assertNull(calls.claim(token))
    }

    @Test
    fun stringToolArgsAndOtherShellToolSpellingsAreRead() = runTest {
        calls.observe(started(ShellCall("call-1", "cd /tmp && /usr/local/bin/meridian canvas list", "shell_command", argsAsString = true)))

        assertEquals("token-1", calls.issueToken("call-1"))
    }

    @Test
    fun meridianMustBeTheProgramWord() {
        assertTrue(MeridianShellCallSignal.runsMeridian("meridian canvas list"))
        assertTrue(MeridianShellCallSignal.runsMeridian("echo x | meridian canvas compose"))
        assertFalse(MeridianShellCallSignal.runsMeridian("meridian-iroh-wrapper --help"))
        assertFalse(MeridianShellCallSignal.runsMeridian("cat meridian.txt"))
    }
}
