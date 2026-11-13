package com.letta.mobile.data.controller.node.iroh.mounts

import com.letta.mobile.data.controller.node.iroh.FilePairedPeerStore
import com.letta.mobile.data.controller.node.iroh.InMemoryPairedPeerStore
import com.letta.mobile.data.controller.node.iroh.InMemoryPairingInviteStore
import com.letta.mobile.data.controller.node.iroh.IrohPairingService
import com.letta.mobile.data.controller.node.iroh.IrohPeerCapabilities
import com.letta.mobile.data.controller.node.iroh.PairedPeer
import com.letta.mobile.data.controller.node.iroh.PairingInviteStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The canvas mount capabilities follow `--enable-canvas-mounts`: a stored record, a new pairing and a pending invite
 * gain them only on a host that runs with mounts enabled.
 */
class CanvasMountCapabilityFlagTest {
    private val mount = IrohPeerCapabilities.MOUNT_CAPABILITIES
    private val file = Files.createTempDirectory("canvas-mount-flag").resolve("paired-peers.json")

    @Test
    fun aLegacyRecordWithoutCapabilitiesReadsBackAsThePreMountDesktopRoleWithTheFlagOff() {
        Files.writeString(file, """[{"nodeId":"old-desktop","name":"old desktop","pairedAtMs":1}]""")

        val peer = checkNotNull(FilePairedPeerStore(file).get("old-desktop"))

        assertEquals(IrohPeerCapabilities.LEGACY_DESKTOP_ROLE, peer.capabilities)
        assertTrue(peer.capabilities.none(mount::contains), "no mount capability without the upgrade")
    }

    @Test
    fun aLegacyRecordGainsTheMountCapabilitiesOnlyThroughTheFlagGatedUpgrade() {
        Files.writeString(file, """[{"nodeId":"old-desktop","name":"old desktop","pairedAtMs":1}]""")

        assertEquals(CanvasMountDeviceUpgrade.Outcome.Applied(upgraded = 1), CanvasMountDeviceUpgrade.forPairingStore(file).runOnce())

        val peer = checkNotNull(FilePairedPeerStore(file).get("old-desktop"))
        assertEquals(IrohPeerCapabilities.LEGACY_DESKTOP_ROLE + mount, peer.capabilities)
        assertEquals(IrohPeerCapabilities.roles(mountsEnabled = true).getValue("desktop"), peer.capabilities)
    }

    @Test
    fun aNewPairingWithTheFlagOffHoldsNoMountCapability() {
        val pairing = IrohPairingService(InMemoryPairedPeerStore(), mountsEnabled = false)
        IrohPeerCapabilities.MOUNT_ROLE_NAMES.forEach { role ->
            assertTrue(pairing.roles().getValue(role).none(mount::contains), role)
        }

        val chat = redeem(pairing, pairing.createInvite("phone").secret, "phone")
        val desktop = redeem(pairing, pairing.createInvite(request(pairing.roles().getValue("desktop"))).secret, "desktop")

        assertEquals(IrohPeerCapabilities.CHAT_ROLE, chat.capabilities)
        assertEquals(IrohPeerCapabilities.DEFAULT_DESKTOP_ROLE, desktop.capabilities)
    }

    @Test
    fun aNewPairingWithTheFlagOnHoldsTheMountCapabilities() {
        val pairing = IrohPairingService(InMemoryPairedPeerStore(), mountsEnabled = true)

        val chat = redeem(pairing, pairing.createInvite("phone").secret, "phone")
        val vibesync = redeem(pairing, pairing.createInvite(request(pairing.roles().getValue("vibesync"))).secret, "vibesync")

        assertEquals(IrohPeerCapabilities.CHAT_ROLE + mount, chat.capabilities)
        assertEquals(IrohPeerCapabilities.VIBESYNC_ROLE + mount, vibesync.capabilities)
    }

    @Test
    fun aPendingInviteGrantsTheRoleAsOfRedemption() {
        val invites = InMemoryPairingInviteStore()
        // Minted by a process without mounts (the host `pair` command), redeemed by a host with them, and back.
        val oldRole = service(invites, mountsEnabled = false).createInvite(request(IrohPeerCapabilities.DEFAULT_DESKTOP_ROLE))
        val newRole = service(invites, mountsEnabled = true).createInvite(request(IrohPeerCapabilities.CHAT_ROLE + mount))

        val upgraded = redeem(service(invites, mountsEnabled = true), oldRole.secret, "upgraded")
        val withdrawn = redeem(service(invites, mountsEnabled = false), newRole.secret, "withdrawn")

        assertEquals(IrohPeerCapabilities.DEFAULT_DESKTOP_ROLE + mount, upgraded.capabilities)
        assertEquals(IrohPeerCapabilities.CHAT_ROLE, withdrawn.capabilities)
    }

    /**
     * The rule for hand-scoped sets: a set equal to a mount role (with or without the mount capabilities) is that role,
     * whether it was stored explicitly or by default, because role sets carry no provenance. Any other set is kept
     * exactly, by the upgrade and at redemption.
     */
    @Test
    fun anExplicitSetEqualToAPreMountRoleIsThatRoleAndAnyOtherSetIsKept() {
        val store = FilePairedPeerStore(file)
        store.save(PairedPeer("explicit", "explicit", 1, capabilities = IrohPeerCapabilities.LEGACY_DESKTOP_ROLE.toSet()))
        val narrow = setOf(IrohPeerCapabilities.CHAT_READ, IrohPeerCapabilities.MEMORY_READ)
        store.save(PairedPeer("narrow", "narrow", 2, capabilities = narrow))

        CanvasMountDeviceUpgrade.forPairingStore(file).runOnce()

        assertEquals(IrohPeerCapabilities.LEGACY_DESKTOP_ROLE + mount, store.get("explicit")?.capabilities)
        assertEquals(narrow, store.get("narrow")?.capabilities)
        assertEquals(narrow, IrohPeerCapabilities.grantAsOfNow(narrow, mountsEnabled = true))
        assertEquals(narrow + mount, IrohPeerCapabilities.grantAsOfNow(narrow + mount, mountsEnabled = false))
    }

    private fun service(invites: PairingInviteStore, mountsEnabled: Boolean) =
        IrohPairingService(InMemoryPairedPeerStore(), invites, mountsEnabled = mountsEnabled)

    private fun request(capabilities: Set<String>) =
        IrohPairingService.InviteRequest(suggestedName = "device", capabilities = capabilities)

    private fun redeem(pairing: IrohPairingService, secret: String, nodeId: String): PairedPeer =
        assertIs<IrohPairingService.RedeemResult.Paired>(pairing.redeem(secret, nodeId)).peer
}
