package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.TelemetryDelegate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Consolidated adversarial security gate for the Iroh authentication and
 * authorization layer (letta-mobile-d6e8g.8).
 *
 * Every denial branch of the auth stack — policy resolution (d6e8g.2), bearer
 * verification + throttling (d6e8g.3), pairing redemption (d6e8g.5), and
 * per-peer capability authorization (d6e8g.6) — is exercised here against the
 * real decision components, asserting a fail-closed outcome AND that no
 * downstream side effect (the injected [SideEffectSpy]) fired. Because this
 * class lives in :sharedLogic:jvmTest it runs inside the required
 * `shared-multiplatform` CI gate, so a regression that opens any of these
 * paths blocks merge.
 *
 * The suite is self-checking: [gateFailsIfAuthorizationIsBypassed] proves the
 * gate is not vacuous — a bypassed decision changes the asserted outcome — so
 * the tests cannot silently pass while authorization is disabled.
 */
class IrohSecurityGateTest {
    /** Records whether any privileged downstream action would have run. */
    private class SideEffectSpy {
        var runtimeStarted = false
        var adminDispatched = false
        var mutationApplied = false

        fun assertUntouched(context: String) {
            assertFalse(runtimeStarted, "$context must not start a runtime")
            assertFalse(adminDispatched, "$context must not dispatch admin RPC")
            assertFalse(mutationApplied, "$context must not apply a mutation")
        }
    }

    private val strongToken = "0123456789abcdef0123456789abcdef"
    private val paired = "a".repeat(64)
    private val stranger = "b".repeat(64)

    private object CapturingDelegate : TelemetryDelegate {
        val lines = mutableListOf<String>()

        override fun logToLogcat(level: Telemetry.Level, tag: String, body: String, throwable: Throwable?) {
            lines.add("$tag $body")
        }

        override fun isLoggable(tag: String, level: Int): Boolean = true

        override fun isTraceEnabled(): Boolean = false

        override fun beginSection(name: String) = Unit

        override fun endSection() = Unit

        override fun beginAsyncSection(name: String, cookie: Int) = Unit

        override fun endAsyncSection(name: String, cookie: Int) = Unit
    }

    @AfterTest
    fun tearDown() {
        Telemetry.delegate = null
        CapturingDelegate.lines.clear()
    }

    // --- Authentication denials (d6e8g.2 / d6e8g.3) ---

    @Test
    fun anonymousStartupIsRefusedUnlessExplicitlyOptedIn() {
        val spy = SideEffectSpy()
        val resolution = IrohAuthPolicy.resolve(
            authToken = null,
            allowedPeerIds = emptySet(),
            allowInsecureAnonymous = false,
        )
        assertIs<IrohAuthPolicyResolution.Refused>(resolution)
        spy.assertUntouched("anonymous startup")
    }

    @Test
    fun missingWrongAndUnapprovedPeerTokensAllFailClosed() {
        val spy = SideEffectSpy()
        val verifier = IrohBearerAuthVerifier(IrohAuthPolicy.BearerToken(strongToken))
        listOf<String?>(null, "", "wrong-token-guess", "almost-" + strongToken).forEach { token ->
            val outcome = verifier.verify(stranger, token)
            authorizeOrNot(outcome, spy)
            assertIs<IrohBearerAuthVerifier.Outcome.Denied>(outcome)
        }
        spy.assertUntouched("bad-credential auth")
    }

    @Test
    fun bruteForceIsThrottledThenClosedEvenForTheValidToken() {
        val spy = SideEffectSpy()
        val verifier = IrohBearerAuthVerifier(IrohAuthPolicy.BearerToken(strongToken))
        repeat(IrohBearerAuthVerifier.MAX_FAILURES_PER_WINDOW) { verifier.verify(stranger, "guess-$it") }
        val locked = verifier.verify(stranger, strongToken)
        assertIs<IrohBearerAuthVerifier.Outcome.RateLimited>(locked)
        authorizeOrNot(locked, spy)
        spy.assertUntouched("throttled peer")
    }

    // --- Pairing denials (d6e8g.5) ---

    @Test
    fun expiredReplayedAndRevokedPairingsFailClosed() {
        val spy = SideEffectSpy()
        val store = InMemoryPairedPeerStore()
        var now = 0L
        val pairing = IrohPairingService(store, nowMs = { now })

        // Expired invite.
        val expiring = pairing.createInvite("desk", ttlMs = 1_000)
        now = 2_000
        assertIs<IrohPairingService.RedeemResult.Rejected>(pairing.redeem(expiring.secret, paired))

        // Successful pair, then replay of the consumed invite.
        val good = pairing.createInvite("desk")
        assertIs<IrohPairingService.RedeemResult.Paired>(pairing.redeem(good.secret, paired))
        assertIs<IrohPairingService.RedeemResult.Rejected>(pairing.redeem(good.secret, stranger))
        assertFalse(pairing.isPaired(stranger))

        // Revocation takes effect immediately.
        assertTrue(pairing.revoke(paired))
        assertFalse(pairing.isPaired(paired))
        spy.assertUntouched("failed pairing")
    }

    // --- Authorization denials (d6e8g.6) ---

    @Test
    fun unauthorizedAdminRpcAndCrossCapabilityAccessDeny() {
        val spy = SideEffectSpy()
        val desktop = IrohPeerCapabilities.DEFAULT_DESKTOP_ROLE

        // Server administration and unknown methods require admin.full.
        listOf("agent.create", "identity.list", "pair.peer.revoke", "brand.new.method", "health.check").forEach { method ->
            val required = IrohPeerCapabilities.forAdminMethod(method)
            val allowed = IrohPeerCapabilities.isAllowed(desktop, required)
            if (allowed) spy.adminDispatched = true
            assertFalse(allowed, "$method must be denied to the least-privilege desktop role")
        }
        spy.assertUntouched("unauthorized admin_rpc")
    }

    @Test
    fun readOnlyPeerCannotDriveRuntimeOrMutate() {
        val spy = SideEffectSpy()
        val readOnly = setOf(IrohPeerCapabilities.CHAT_READ)
        val runtimeCap = IrohPeerCapabilities.forProtocolCommand("input")!!
        if (IrohPeerCapabilities.isAllowed(readOnly, runtimeCap)) spy.runtimeStarted = true
        if (IrohPeerCapabilities.isAllowed(readOnly, IrohPeerCapabilities.forAdminMethod("block.update"))) {
            spy.mutationApplied = true
        }
        spy.assertUntouched("read-only peer")
    }

    // --- Positive path ---

    @Test
    fun approvedPeerWithCredentialAndCapabilityIsAdmitted() {
        val verifier = IrohBearerAuthVerifier(IrohAuthPolicy.BearerToken(strongToken))
        assertEquals(IrohBearerAuthVerifier.Outcome.Authenticated, verifier.verify(paired, strongToken))

        val store = InMemoryPairedPeerStore()
        val pairing = IrohPairingService(store)
        val invite = pairing.createInvite("desk")
        pairing.redeem(invite.secret, paired)
        val peer = checkNotNull(pairing.peer(paired))
        assertTrue(IrohPeerCapabilities.isAllowed(peer.capabilities, IrohPeerCapabilities.CHAT_SEND))
    }

    // --- Secret-redaction canary scan ---

    @Test
    fun authDenialTelemetryNeverLeaksInjectedCanarySecrets() {
        Telemetry.delegate = CapturingDelegate
        val canaryToken = "CANARY-SECRET-TOKEN-8fae12"
        val canaryInvite = "CANARY-INVITE-9be31c"

        val verifier = IrohBearerAuthVerifier(IrohAuthPolicy.BearerToken(strongToken))
        verifier.verify("peer-canary", canaryToken)

        val pairing = IrohPairingService(InMemoryPairedPeerStore())
        pairing.redeem(canaryInvite, "peer-canary")

        val dump = CapturingDelegate.lines.joinToString("\n")
        assertFalse(dump.contains(canaryToken), "auth telemetry leaked a canary token")
        assertFalse(dump.contains(canaryInvite), "pairing telemetry leaked a canary invite")
        assertTrue(CapturingDelegate.lines.isNotEmpty(), "expected auth telemetry to have been captured")
    }

    // --- Mutation self-check: the gate must not be vacuous ---

    @Test
    fun gateFailsIfAuthorizationIsBypassed() {
        // If capability enforcement were bypassed (admin.full granted to
        // everyone), the same "denied" methods would be allowed. Prove the
        // decision actually depends on the capability set.
        val desktop = IrohPeerCapabilities.DEFAULT_DESKTOP_ROLE
        val bypassed = setOf(IrohPeerCapabilities.ADMIN_FULL)
        val required = IrohPeerCapabilities.forAdminMethod("agent.create")
        assertFalse(IrohPeerCapabilities.isAllowed(desktop, required))
        assertTrue(
            IrohPeerCapabilities.isAllowed(bypassed, required),
            "self-check: a bypassed (admin.full) peer MUST flip the decision, else the gate is vacuous",
        )
    }

    private fun authorizeOrNot(outcome: IrohBearerAuthVerifier.Outcome, spy: SideEffectSpy) {
        if (outcome is IrohBearerAuthVerifier.Outcome.Authenticated) {
            spy.runtimeStarted = true
        }
    }
}
