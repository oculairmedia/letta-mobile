package com.letta.mobile.data.controller.node.iroh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import com.letta.mobile.data.controller.node.iroh.auth.IrohAuthPolicy

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
    fun emptyAllowlistKeepsTheTokenValidFromAnyNodeId() {
        val v = verifier()
        assertEquals(IrohBearerAuthVerifier.Outcome.Authenticated, v.verify("peer-1", token))
        assertEquals(IrohBearerAuthVerifier.Outcome.Authenticated, v.verify("peer-unlisted", token))
    }

    @Test
    fun allowlistBindsTheTokenToListedNodeIds() {
        val v = IrohBearerAuthVerifier(IrohAuthPolicy.BearerToken(token, setOf("peer-1")))
        assertEquals(IrohBearerAuthVerifier.Outcome.Authenticated, v.verify("peer-1", token))
        assertEquals(IrohBearerAuthVerifier.Outcome.Denied("peer_not_allowlisted"), v.verify("peer-2", token))
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

    /** The lockout rule on its own, no flood: it holds identically before and after the table got a ceiling. */
    @Test
    fun fiveFailuresInTheWindowLockOutUntilTheWindowPasses() {
        val clock = FakeClock()
        val v = verifier(clock)
        repeat(IrohBearerAuthVerifier.MAX_FAILURES_PER_WINDOW - 1) {
            assertEquals(IrohBearerAuthVerifier.Outcome.Denied("invalid_token"), v.verify("peer-1", "wrong-$it"), "inside the budget")
            clock.now += 1_000
        }
        assertEquals(IrohBearerAuthVerifier.Outcome.RateLimited, v.verify("peer-1", "wrong-last"), "the fifth failure locks out")
        assertEquals(IrohBearerAuthVerifier.Outcome.RateLimited, v.verify("peer-1", token), "locked out even for the valid token")

        clock.now += IrohBearerAuthVerifier.LOCKOUT_WINDOW_MS + 1
        assertEquals(IrohBearerAuthVerifier.Outcome.Authenticated, v.verify("peer-1", token), "admitted after the window")
    }

    private fun boundedVerifier(clock: FakeClock) = verifier(clock)

    private fun freshNodeId(i: Int): String = "%064x".format(i)

    @Test
    fun aFloodOfFreshNodeIdsStaysWithinTheCeiling() {
        val clock = FakeClock()
        val v = boundedVerifier(clock)
        repeat(FLOOD_SIZE) { i ->
            v.verify(freshNodeId(i), "wrong")
            clock.now += 1
        }
        assertTrue(v.trackedPeerCount() <= FLOOD_CEILING, "tracked ${v.trackedPeerCount()} NodeIds, ceiling $FLOOD_CEILING")
    }

    @Test
    fun aLockedOutOffenderStaysLockedOutThroughAFlood() {
        val clock = FakeClock()
        val v = boundedVerifier(clock)
        repeat(IrohBearerAuthVerifier.MAX_FAILURES_PER_WINDOW) { v.verify("offender", "wrong-$it") }
        assertEquals(IrohBearerAuthVerifier.Outcome.RateLimited, v.verify("offender", token))

        repeat(FLOOD_SIZE) { i -> v.verify(freshNodeId(i), "wrong") }

        assertEquals(IrohBearerAuthVerifier.Outcome.RateLimited, v.verify("offender", token), "a flood must not lift a lockout")
        assertTrue(v.trackedPeerCount() <= FLOOD_CEILING, "ceiling: tracked ${v.trackedPeerCount()} NodeIds, ceiling $FLOOD_CEILING")
    }

    @Test
    fun aFloodDoesNotResetAPartlySpentBudget() {
        val clock = FakeClock()
        val v = boundedVerifier(clock)
        repeat(IrohBearerAuthVerifier.MAX_FAILURES_PER_WINDOW - 1) { v.verify("guesser", "wrong-$it") }

        repeat(FLOOD_SIZE) { i -> v.verify(freshNodeId(i), "wrong") }

        assertEquals(
            IrohBearerAuthVerifier.Outcome.RateLimited,
            v.verify("guesser", "one-more"),
            "the last failure in the budget still locks out",
        )
    }

    @Test
    fun aSaturatedTableFailsClosedForNewFailuresButStillAdmitsValidCredentials() {
        val clock = FakeClock()
        val v = boundedVerifier(clock)
        repeat(FLOOD_CEILING) { i -> v.verify(freshNodeId(i), "wrong") }

        assertEquals(IrohBearerAuthVerifier.Outcome.RateLimited, v.verify("newcomer", "wrong"), "no room to count it: refuse at once")
        assertEquals(IrohBearerAuthVerifier.Outcome.Authenticated, v.verify("legit-peer", token), "a valid token is still admitted")
    }

    @Test
    fun expiredFailuresMakeRoomAgain() {
        val clock = FakeClock()
        val v = boundedVerifier(clock)
        repeat(FLOOD_CEILING) { i -> v.verify(freshNodeId(i), "wrong") }
        clock.now += IrohBearerAuthVerifier.LOCKOUT_WINDOW_MS + 1

        assertEquals(
            IrohBearerAuthVerifier.Outcome.Denied("invalid_token"),
            v.verify("newcomer", "wrong"),
            "room again: counted, not refused",
        )
        assertTrue(v.trackedPeerCount() <= FLOOD_CEILING, "ceiling: tracked ${v.trackedPeerCount()} NodeIds, ceiling $FLOOD_CEILING")
    }

    private companion object {
        const val FLOOD_CEILING = IrohBearerAuthVerifier.MAX_TRACKED_PEERS
        const val FLOOD_SIZE = 5 * FLOOD_CEILING
    }
}
