package com.letta.mobile.data.controller.node.iroh

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IrohPairingServiceTest {
    private class FakeClock(var now: Long = 1_000L)

    private fun service(
        store: PairedPeerStore = InMemoryPairedPeerStore(),
        clock: FakeClock = FakeClock(),
    ): Pair<IrohPairingService, FakeClock> =
        IrohPairingService(store = store, nowMs = { clock.now }) to clock

    @Test
    fun redemptionBindsNodeIdAndFutureSessionsAuthenticateByNodeId() {
        val store = InMemoryPairedPeerStore()
        val (pairing, _) = service(store)
        val invite = pairing.createInvite("emmanuel-desktop")

        val result = pairing.redeem(invite.secret, nodeId = "a".repeat(64))

        val paired = assertIs<IrohPairingService.RedeemResult.Paired>(result)
        assertEquals("emmanuel-desktop", paired.peer.name)
        assertTrue(pairing.isPaired("a".repeat(64)), "future sessions must authenticate by NodeId")
        assertTrue(store.isPaired("a".repeat(64)))
    }

    @Test
    fun wrongExpiredAndReplayedInvitesAllFail() {
        val (pairing, clock) = service()
        val invite = pairing.createInvite("desk", ttlMs = 5_000)

        assertIs<IrohPairingService.RedeemResult.Rejected>(pairing.redeem("not-the-secret", "b".repeat(64)))

        // Expired: past the TTL the (still unredeemed) invite must fail.
        clock.now += 6_000
        val expired = assertIs<IrohPairingService.RedeemResult.Rejected>(pairing.redeem(invite.secret, "b".repeat(64)))
        assertEquals("expired", expired.reason)

        // Replay of a fresh invite after successful redemption fails.
        val invite2 = pairing.createInvite("desk2")
        assertIs<IrohPairingService.RedeemResult.Paired>(pairing.redeem(invite2.secret, "c".repeat(64)))
        val replayed = assertIs<IrohPairingService.RedeemResult.Rejected>(pairing.redeem(invite2.secret, "d".repeat(64)))
        assertEquals("invalid", replayed.reason)
        assertFalse(pairing.isPaired("d".repeat(64)))
    }

    @Test
    fun concurrentDoubleRedemptionHasExactlyOneWinner() {
        repeat(20) {
            val (pairing, _) = service()
            val invite = pairing.createInvite("racer")
            val start = CountDownLatch(1)
            val wins = AtomicInteger(0)
            val threads = (0 until 2).map { i ->
                thread {
                    start.await()
                    if (pairing.redeem(invite.secret, nodeId = "peer-$i".padEnd(64, 'e')) is IrohPairingService.RedeemResult.Paired) {
                        wins.incrementAndGet()
                    }
                }
            }
            start.countDown()
            threads.forEach { it.join() }
            assertEquals(1, wins.get(), "exactly one redemption may win")
        }
    }

    @Test
    fun inviteSecretsNeverAppearInToStringOrRejectedReasons() {
        val (pairing, _) = service()
        val invite = pairing.createInvite("quiet")
        assertFalse(invite.toString().contains(invite.secret))
        val rejected = pairing.redeem("wrong-secret", "f".repeat(64)) as IrohPairingService.RedeemResult.Rejected
        assertFalse(rejected.reason.contains("wrong-secret"))
    }

    @Test
    fun revokeRemovesThePairingImmediately() {
        val (pairing, _) = service()
        val invite = pairing.createInvite("temp")
        pairing.redeem(invite.secret, "1".repeat(64))
        assertTrue(pairing.isPaired("1".repeat(64)))
        assertTrue(pairing.revoke("1".repeat(64)))
        assertFalse(pairing.isPaired("1".repeat(64)))
        assertFalse(pairing.revoke("1".repeat(64)))
    }

    @Test
    fun renameAndSetCapabilitiesUpdatePairedPeerInPlace() {
        val (pairing, _) = service()
        val nodeId = "7".repeat(64)
        pairing.redeem(pairing.createInvite("laptop").secret, nodeId)

        val renamed = pairing.rename(nodeId, "emmanuel-laptop")
        assertEquals("emmanuel-laptop", renamed?.name)
        assertEquals("emmanuel-laptop", pairing.peer(nodeId)?.name)
        // NodeId binding + default capabilities preserved by a rename.
        assertEquals(IrohPeerCapabilities.DEFAULT_DESKTOP_ROLE, pairing.peer(nodeId)?.capabilities)

        val recapped = pairing.setCapabilities(nodeId, setOf(IrohPeerCapabilities.CHAT_READ, IrohPeerCapabilities.ADMIN_FULL))
        assertEquals(setOf(IrohPeerCapabilities.CHAT_READ, IrohPeerCapabilities.ADMIN_FULL), recapped?.capabilities)
        assertEquals("emmanuel-laptop", pairing.peer(nodeId)?.name, "re-scoping capabilities must not lose the name")
    }

    @Test
    fun renameAndSetCapabilitiesReturnNullForUnknownPeer() {
        val (pairing, _) = service()
        assertEquals(null, pairing.rename("z".repeat(64), "ghost"))
        assertEquals(null, pairing.setCapabilities("z".repeat(64), setOf(IrohPeerCapabilities.CHAT_READ)))
    }

    @Test
    fun aPairedPeerStaysPairedEvenWhenItReplaysItsConsumedInvite() {
        // Invariant the handleAuth stale-invite fall-through relies on (d6e8g.7):
        // a device that paired but still carries the now-consumed invite in its
        // token field re-presents it on reconnect. Redemption is Rejected, but
        // isPaired stays true — so auth can fall through to NodeId instead of
        // locking the device out.
        val (pairing, _) = service()
        val nodeId = "8".repeat(64)
        val invite = pairing.createInvite("pixel")
        assertIs<IrohPairingService.RedeemResult.Paired>(pairing.redeem(invite.secret, nodeId))
        assertTrue(pairing.isPaired(nodeId))

        val replay = assertIs<IrohPairingService.RedeemResult.Rejected>(pairing.redeem(invite.secret, nodeId))
        assertEquals("invalid", replay.reason)
        assertTrue(pairing.isPaired(nodeId), "a consumed-invite replay must not un-pair the device")
    }

    @Test
    fun filePairedPeerStoreSurvivesRestartAndHoldsNoSecrets() {
        val path = Files.createTempDirectory("pairing-test").resolve("paired-peers.json")
        val store = FilePairedPeerStore(path)
        store.save(PairedPeer(nodeId = "9".repeat(64), name = "desk", pairedAtMs = 42))

        val reloaded = FilePairedPeerStore(path)
        assertTrue(reloaded.isPaired("9".repeat(64)))
        assertEquals("desk", reloaded.get("9".repeat(64))?.name)
        assertEquals(1, reloaded.list().size)

        assertTrue(reloaded.remove("9".repeat(64)))
        assertFalse(FilePairedPeerStore(path).isPaired("9".repeat(64)))
    }

    @Test
    fun pruneExpiredDropsAbandonedInvites() {
        val (pairing, clock) = service()
        val invite = pairing.createInvite("old", ttlMs = 1_000)
        clock.now += 2_000
        pairing.pruneExpired()
        assertIs<IrohPairingService.RedeemResult.Rejected>(pairing.redeem(invite.secret, "2".repeat(64)))
    }
}
