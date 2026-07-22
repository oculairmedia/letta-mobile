package com.letta.mobile.data.controller.node.iroh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IrohBearerAuthVerifierTest {
    private val token = "0123456789abcdef0123456789abcdef"
    private val policy = IrohAuthPolicy.BearerToken(token)

    private class FakeClock(var now: Long = 0L)

    private fun verifier(clock: FakeClock = FakeClock()) =
        IrohBearerAuthVerifier(policy = policy, nowMs = { clock.now })

    @Test
    fun validTokenAuthenticates() {
        assertEquals(IrohBearerAuthVerifier.Outcome.Authenticated, verifier().verify("peer-1", token))
    }

    @Test
    fun missingAndWrongTokensAreDeniedWithFixedReasons() {
        val v = verifier()
        assertEquals(IrohBearerAuthVerifier.Outcome.Denied("missing_token"), v.verify("peer-1", null))
        assertEquals(IrohBearerAuthVerifier.Outcome.Denied("missing_token"), v.verify("peer-1", ""))
        assertEquals(IrohBearerAuthVerifier.Outcome.Denied("invalid_token"), v.verify("peer-1", "wrong-token-value"))
    }

    @Test
    fun deniedOutcomesNeverContainSecretMaterial() {
        val v = verifier()
        val denied = v.verify("peer-1", "attacker-guess-1234") as IrohBearerAuthVerifier.Outcome.Denied
        assertFalse(denied.reason.contains(token))
        assertFalse(denied.reason.contains("attacker-guess-1234"))
    }

    @Test
    fun tokenComparisonHandlesLengthAndUnicodeDifferences() {
        val v = verifier()
        assertIs<IrohBearerAuthVerifier.Outcome.Denied>(v.verify("peer-1", token.dropLast(1)))
        assertIs<IrohBearerAuthVerifier.Outcome.Denied>(v.verify("peer-2", token + "x"))
        assertIs<IrohBearerAuthVerifier.Outcome.Denied>(v.verify("peer-3", token.dropLast(1) + "é"))
    }

    @Test
    fun policiesWithoutTokensAuthenticateWithoutRateTracking() {
        val anonymous = IrohBearerAuthVerifier(IrohAuthPolicy.InsecureAnonymousForTestOnly)
        val allowlist = IrohBearerAuthVerifier(IrohAuthPolicy.PeerAllowlist(setOf("peer-1")))
        repeat(20) {
            assertEquals(IrohBearerAuthVerifier.Outcome.Authenticated, anonymous.verify("peer-1", null))
            assertEquals(IrohBearerAuthVerifier.Outcome.Authenticated, allowlist.verify("peer-1", null))
        }
    }

    @Test
    fun repeatedFailuresFromOnePeerAreRateLimitedEvenForTheValidToken() {
        val clock = FakeClock()
        val v = verifier(clock)
        repeat(IrohBearerAuthVerifier.MAX_FAILURES_PER_WINDOW) {
            v.verify("peer-1", "wrong-$it")
            clock.now += 1_000
        }
        // Locked out: even the correct token is refused, fail closed.
        assertEquals(IrohBearerAuthVerifier.Outcome.RateLimited, v.verify("peer-1", token))
        assertEquals(IrohBearerAuthVerifier.Outcome.RateLimited, v.verify("peer-1", "still-wrong"))
    }

    @Test
    fun rateLimitIsScopedPerPeer() {
        val clock = FakeClock()
        val v = verifier(clock)
        repeat(IrohBearerAuthVerifier.MAX_FAILURES_PER_WINDOW) { v.verify("attacker", "wrong-$it") }
        assertEquals(IrohBearerAuthVerifier.Outcome.RateLimited, v.verify("attacker", token))
        assertEquals(IrohBearerAuthVerifier.Outcome.Authenticated, v.verify("legit-peer", token))
    }

    @Test
    fun lockoutExpiresAfterTheWindow() {
        val clock = FakeClock()
        val v = verifier(clock)
        repeat(IrohBearerAuthVerifier.MAX_FAILURES_PER_WINDOW) { v.verify("peer-1", "wrong-$it") }
        assertEquals(IrohBearerAuthVerifier.Outcome.RateLimited, v.verify("peer-1", token))

        clock.now += IrohBearerAuthVerifier.LOCKOUT_WINDOW_MS + 1
        assertEquals(IrohBearerAuthVerifier.Outcome.Authenticated, v.verify("peer-1", token))
    }

    @Test
    fun successesDoNotAccumulateTowardTheLimit() {
        val clock = FakeClock()
        val v = verifier(clock)
        repeat(50) {
            assertEquals(IrohBearerAuthVerifier.Outcome.Authenticated, v.verify("peer-1", token))
        }
        assertTrue(v.verify("peer-1", "wrong") is IrohBearerAuthVerifier.Outcome.Denied)
    }
}
