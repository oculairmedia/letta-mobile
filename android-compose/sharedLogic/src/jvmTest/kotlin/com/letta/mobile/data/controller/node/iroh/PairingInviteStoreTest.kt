package com.letta.mobile.data.controller.node.iroh

import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Hashed, file-backed pending invites shared by the host `pair` CLI and the server (dpv7m.1). */
class PairingInviteStoreTest {
    private val dir = Files.createTempDirectory("pairing-invites")
    private val peers = dir.resolve("paired-peers.json")
    private val invites = FilePairingInviteStore.besidePeerStore(peers)

    @Test
    fun inviteFileLivesBesideThePeerStoreAndNeverHoldsTheSecret() {
        assertEquals(dir.resolve("paired-peers.invites.json"), invites)
        val cli = IrohPairingService(FilePairedPeerStore(peers), FilePairingInviteStore(invites))
        val invite = cli.createInvite("phone")
        val onDisk = String(Files.readAllBytes(invites))
        assertFalse(invite.secret in onDisk, "the invite file must hold only a hash of the secret")
        assertTrue(invite.id.value in onDisk)
    }

    @Test
    fun anInviteMintedByTheCliProcessIsRedeemedOnceByTheServerProcess() {
        // Two independent service instances over the same files = the CLI and the server.
        val cli = IrohPairingService(FilePairedPeerStore(peers), FilePairingInviteStore(invites))
        val server = IrohPairingService(FilePairedPeerStore(peers), FilePairingInviteStore(invites))
        val invite = cli.createInvite("phone")

        assertIs<IrohPairingService.RedeemResult.Paired>(server.redeem(invite.secret, "a".repeat(64)))
        assertTrue(cli.isPaired("a".repeat(64)), "the binding is visible to every process")
        val replay = server.redeem(invite.secret, "b".repeat(64))
        assertEquals("invalid", assertIs<IrohPairingService.RedeemResult.Rejected>(replay).reason)
    }

    @Test
    fun concurrentRedemptionAcrossInstancesHasOneWinner() {
        val minted = IrohPairingService(FilePairedPeerStore(peers), FilePairingInviteStore(invites)).createInvite("race")
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = (0 until 8).map { i ->
                Callable {
                    val instance = IrohPairingService(FilePairedPeerStore(peers), FilePairingInviteStore(invites))
                    instance.redeem(minted.secret, i.toString().repeat(64))
                }
            }.let(pool::invokeAll).map { it.get() }
            assertEquals(1, results.count { it is IrohPairingService.RedeemResult.Paired })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun unredeemedInviteCanBeListedWithoutSecretsAndRevokedById() {
        val service = IrohPairingService(FilePairedPeerStore(peers), FilePairingInviteStore(invites))
        val invite = service.createInvite(IrohPairingService.InviteRequest("phone", agentIds = setOf("agent-1")))
        val listed = service.listInvites().single()
        assertEquals(invite.id, listed.id)
        assertEquals(setOf("agent-1"), listed.agentIds)
        assertFalse(invite.secret in listed.toString())

        assertTrue(service.revokeInvite(invite.id))
        assertIs<IrohPairingService.RedeemResult.Rejected>(service.redeem(invite.secret, "a".repeat(64)))
    }

    @Test
    fun ttlDefaultsToFiveMinutesAndIsCapped() {
        var now = 0L
        val service = IrohPairingService(InMemoryPairedPeerStore(), nowMs = { now })
        assertEquals(IrohPairingService.DEFAULT_TTL_MS, service.createInvite("a").expiresAtMs)
        assertEquals(IrohPairingService.MAX_TTL_MS, service.createInvite("b", ttlMs = 365L * 24 * 3_600_000).expiresAtMs)
        assertEquals(IrohPairingService.DEFAULT_TTL_MS, service.createInvite("c", ttlMs = -1).expiresAtMs)
        now = IrohPairingService.MAX_TTL_MS + 1
        assertTrue(service.listInvites().isEmpty(), "expired invites are pruned from listings")
    }

    @Test
    fun legacyPeerRecordsKeepTheirGrantsAndAreUnscoped() {
        // A paired-peers.json written before dpv7m.1 (deployed host) must load unchanged.
        Files.writeString(
            peers,
            """[{"nodeId":"${"a".repeat(64)}","name":"desk","pairedAtMs":1,"capabilities":["admin.full"]}]""",
        )
        val peer = FilePairedPeerStore(peers).get("a".repeat(64))!!
        assertEquals(setOf("admin.full"), peer.capabilities)
        assertEquals(null, peer.agentIds)
    }
}
