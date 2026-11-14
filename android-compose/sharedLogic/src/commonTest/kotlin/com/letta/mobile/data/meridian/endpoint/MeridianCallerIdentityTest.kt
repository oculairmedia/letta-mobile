package com.letta.mobile.data.meridian.endpoint

import com.letta.mobile.data.controller.extras.identity.CallerIdentity
import com.letta.mobile.data.meridian.endpoint.FakeRuntimeStream.started
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** A live-call token vouches for its call's scope (verified); an env-scoped claim is only claimed. */
class MeridianCallerIdentityTest {
    private var minted = 0
    private val liveCalls = MeridianLiveCalls(nowMs = { 0L }, newToken = { "tok-${++minted}" })

    @Test
    fun aLiveCallTokenBindsAVerifiedCaller() = runTest {
        liveCalls.observe(started(ShellCall("call-1", "meridian canvas list")))
        val binder = MeridianCallerBinder(MeridianCallerBindingMode.LIVE_CALL, liveCalls)
        val token = binder.issue("call-1").getOrThrow()

        val caller = binder.bind(token, agentId = "agent-other", conversationId = "conv-other").getOrThrow()

        assertEquals(CallerIdentity.VERIFIED, caller.identity)
        assertEquals("agent-a" to "conv-a", caller.agentId to caller.conversationId, "the call's own scope, never the claim")
    }

    @Test
    fun anEnvScopedClaimBindsAClaimedCaller() = runTest {
        val binder = MeridianCallerBinder(MeridianCallerBindingMode.ENV_SCOPED, liveCalls)

        val caller = binder.bind(null, agentId = "agent-a", conversationId = "conv-a").getOrThrow()

        assertEquals(CallerIdentity.CLAIMED, caller.identity)
    }
}
