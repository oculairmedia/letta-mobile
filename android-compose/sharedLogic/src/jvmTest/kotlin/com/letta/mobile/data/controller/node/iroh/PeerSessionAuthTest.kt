package com.letta.mobile.data.controller.node.iroh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fail-closed regression suite for one connection's auth/grant state
 * (letta-mobile-dpv7m.1): forged NodeIds, replayed/expired invites, revoked
 * peers (new AND live sessions), invite guessing, pairing-only hosts, and
 * least-privilege scoped grants.
 */
class PeerSessionAuthTest {
    private val alice = "a".repeat(64)
    private val mallory = "f".repeat(64)
    private val token = "0123456789abcdef0123456789abcdef"

    private var now = 1_000_000L
    private val pairing = IrohPairingService(InMemoryPairedPeerStore(), nowMs = { now })

    private fun session(
        remoteId: String,
        policy: IrohAuthPolicy = IrohAuthPolicy.PairedPeersOnly,
        verifier: IrohBearerAuthVerifier = IrohBearerAuthVerifier(policy, nowMs = { now }),
        onRevoked: () -> Unit = {},
    ) = PeerSessionAuth(policy, verifier, pairing, remoteId, onRevoked)

    private fun inviteToken(request: IrohPairingService.InviteRequest = IrohPairingService.InviteRequest("phone")): String =
        IrohPairingService.INVITE_TOKEN_PREFIX + pairing.createInvite(request).secret

    private fun assertDenied(decision: PeerSessionAuth.Decision, reason: String) {
        val rejected = assertIs<PeerSessionAuth.Decision.Rejected>(decision)
        assertEquals(reason, assertIs<IrohBearerAuthVerifier.Outcome.Denied>(rejected.outcome).reason)
    }

    @Test
    fun pairingOnlyHostDeniesAnUnknownPeerThatOnlyKnowsTheDialAddress() {
        val stranger = session(mallory)
        assertFalse(stranger.authenticated.get(), "dialing must not pre-authenticate")
        assertDenied(stranger.authenticate(token = "", peerName = null), "pairing_required")
        assertDenied(stranger.authenticate(token = token, peerName = null), "pairing_required")
        assertTrue(stranger.effectiveCapabilities().isEmpty())
    }

    @Test
    fun redeemingAnInviteBindsTheNodeIdWithTheChatRoleOnly() {
        val decision = session(alice).authenticate(inviteToken(), peerName = "pixel")
        assertEquals(PeerSessionAuth.Decision.Accepted("invite_redeemed", newlyPaired = true), decision)
        val peer = pairing.peer(alice)!!
        assertEquals(IrohPeerCapabilities.CHAT_ROLE, peer.capabilities)
        assertNull(peer.agentIds)

        // Next session authenticates by NodeId alone, with exactly the stored grant.
        val next = session(alice)
        assertTrue(next.authenticated.get())
        assertEquals(IrohPeerCapabilities.CHAT_ROLE, next.effectiveCapabilities())
        assertFalse(IrohPeerCapabilities.ADMIN_FULL in next.effectiveCapabilities())
    }

    @Test
    fun forgedNodeIdCannotRideAnotherDevicesBinding() {
        session(alice).authenticate(inviteToken(), peerName = null)
        // Mallory is a different key: the QUIC handshake gives a different
        // remote id, and that id was never paired.
        val forged = session(mallory)
        assertFalse(forged.authenticated.get())
        assertDenied(forged.authenticate(token = "", peerName = null), "pairing_required")
        assertTrue(forged.effectiveCapabilities().isEmpty())
    }

    @Test
    fun replayedInviteIsRejectedForASecondDevice() {
        val invite = inviteToken()
        assertIs<PeerSessionAuth.Decision.Accepted>(session(alice).authenticate(invite, null))
        assertDenied(session(mallory).authenticate(invite, null), "invite_invalid")
        assertFalse(pairing.isPaired(mallory))
    }

    @Test
    fun expiredInviteIsRejectedAndBindsNothing() {
        val invite = inviteToken(IrohPairingService.InviteRequest("phone", ttlMs = 60_000))
        now += 60_000
        assertDenied(session(alice).authenticate(invite, null), "invite_expired")
        assertFalse(pairing.isPaired(alice))
    }

    @Test
    fun revokedPeerLosesItsLiveSessionInsteadOfFallingBackToAdmin() {
        session(alice).authenticate(inviteToken(), null)
        var closed = 0
        val live = session(alice, onRevoked = { closed++ })
        assertEquals(IrohPeerCapabilities.CHAT_ROLE, live.effectiveCapabilities())

        pairing.revoke(alice)

        // Pre-dpv7m.1 this returned admin.full (record gone => bootstrap role).
        assertTrue(live.effectiveCapabilities().isEmpty(), "a revoked session must get nothing")
        assertFalse(live.authenticated.get())
        assertEquals(1, closed, "the connection is closed exactly once")
        live.effectiveCapabilities()
        assertEquals(1, closed)
    }

    @Test
    fun revokedPeerCannotStartANewSession() {
        session(alice).authenticate(inviteToken(), null)
        pairing.revoke(alice)
        val redial = session(alice)
        assertFalse(redial.authenticated.get())
        assertDenied(redial.authenticate("", null), "pairing_required")
    }

    @Test
    fun capabilityReScopeAppliesToTheLiveSession() {
        session(alice).authenticate(inviteToken(), null)
        val live = session(alice)
        pairing.setCapabilities(alice, setOf(IrohPeerCapabilities.CHAT_READ))
        assertEquals(setOf(IrohPeerCapabilities.CHAT_READ), live.effectiveCapabilities())
    }

    @Test
    fun alreadyPairedDeviceDoesNotBurnAnInviteMeantForAnotherDevice() {
        session(alice).authenticate(inviteToken(), null)
        val second = inviteToken()
        val again = session(alice).authenticate(second, null)
        assertEquals(PeerSessionAuth.Decision.Accepted("paired_node_id", alreadyPaired = true), again)
        // The invite is still redeemable by the device it was minted for.
        assertIs<PeerSessionAuth.Decision.Accepted>(session(mallory).authenticate(second, null))
        assertTrue(pairing.isPaired(mallory))
    }

    @Test
    fun inviteGuessingIsRateLimitedLikeBearerGuessing() {
        val verifier = IrohBearerAuthVerifier(IrohAuthPolicy.PairedPeersOnly, nowMs = { now })
        val valid = inviteToken()
        repeat(IrohBearerAuthVerifier.MAX_FAILURES_PER_WINDOW) {
            session(mallory, verifier = verifier).authenticate("invite:${"0".repeat(64)}", null)
        }
        val limited = session(mallory, verifier = verifier).authenticate(valid, null)
        assertIs<IrohBearerAuthVerifier.Outcome.RateLimited>(assertIs<PeerSessionAuth.Decision.Rejected>(limited).outcome)
        assertFalse(pairing.isPaired(mallory), "a throttled peer must not redeem even a valid invite")
    }

    @Test
    fun agentScopedInviteConfinesTheDevice() {
        val scoped = IrohPairingService.InviteRequest("phone", agentIds = setOf("agent-1"))
        session(alice).authenticate(inviteToken(scoped), null)
        assertEquals(setOf("agent-1"), session(alice).agentScope())
    }

    @Test
    fun bootstrapBearerKeepsTheExplicitAdminAllowance() {
        val policy = IrohAuthPolicy.BearerToken(token)
        val bootstrap = session(mallory, policy = policy)
        assertFalse(bootstrap.authenticated.get())
        assertIs<PeerSessionAuth.Decision.Accepted>(bootstrap.authenticate(token, null))
        assertEquals(setOf(IrohPeerCapabilities.ADMIN_FULL), bootstrap.effectiveCapabilities())
        assertNull(bootstrap.agentScope())
        assertDenied(session(alice, policy = policy).authenticate("wrong-token-wrong-token", null), "invalid_token")
    }

    @Test
    fun staticAllowlistPreAuthenticatesOnlyListedPeers() {
        val policy = IrohAuthPolicy.PeerAllowlist(setOf(alice))
        assertTrue(session(alice, policy = policy).authenticated.get())
        val unlisted = session(mallory, policy = policy)
        assertFalse(unlisted.authenticated.get(), "admission for invite redemption is not authentication")
        assertDenied(unlisted.authenticate("", null), "pairing_required")
    }

    @Test
    fun allowlistedBearerTokenIsDeniedFromAnUnlistedNodeIdEvenWithPairingConfigured() {
        val policy = IrohAuthPolicy.BearerToken(token, setOf(alice))
        val verifier = IrohBearerAuthVerifier(policy, nowMs = { now })
        assertDenied(session(mallory, policy, verifier).authenticate(token, null), "peer_not_allowlisted")
        repeat(IrohBearerAuthVerifier.MAX_FAILURES_PER_WINDOW - 1) {
            session(mallory, policy, verifier).authenticate(token, null)
        }
        assertTrue(verifier.isRateLimited(mallory), "allowlist denials count against the failure budget")
        assertFalse(verifier.isRateLimited(alice))
    }

    @Test
    fun allowlistedBearerTokenAuthenticatesTheListedNodeId() {
        val policy = IrohAuthPolicy.BearerToken(token, setOf(alice))
        val listed = session(alice, policy = policy)
        assertEquals(PeerSessionAuth.Decision.Accepted("bootstrap"), listed.authenticate(token, null))
        assertEquals(setOf(IrohPeerCapabilities.ADMIN_FULL), listed.effectiveCapabilities())
    }

    @Test
    fun pairedPeerOutsideTheAllowlistStillAuthenticatesByNodeId() {
        val policy = IrohAuthPolicy.BearerToken(token, setOf(alice))
        val decision = session(mallory, policy = policy).authenticate(inviteToken(), null)
        assertEquals(PeerSessionAuth.Decision.Accepted("invite_redeemed", newlyPaired = true), decision)
        val redial = session(mallory, policy = policy)
        assertTrue(redial.authenticated.get())
        assertEquals(PeerSessionAuth.Decision.Accepted("paired_node_id"), redial.authenticate("", null))
        assertEquals(IrohPeerCapabilities.CHAT_ROLE, redial.effectiveCapabilities())
    }
}
