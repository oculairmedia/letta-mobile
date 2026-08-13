@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop.qr

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.controller.node.iroh.AdminRpcRouter
import com.letta.mobile.data.controller.node.iroh.FixedIrohSecretKeyStore
import com.letta.mobile.data.controller.node.iroh.IrohPairingService
import com.letta.mobile.data.controller.node.iroh.InMemoryPairedPeerStore
import com.letta.mobile.data.controller.node.iroh.PairQrSigner
import com.letta.mobile.data.controller.node.iroh.PairingAdminHandlers
import com.letta.mobile.data.controller.node.iroh.PairedPeer
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * letta-mobile-nonza (sixv8.3): execution-location picker tests.
 *
 * The picker is a chat-composer header chip. Acceptance criterion: when
 * collapsed, it shows the current execution location (default `local` or
 * the first paired peer once one shows up). When expanded, it shows the
 * full peer list. The picker does not interfere with the chat composer
 * because it is a fixed-height overlay.
 *
 * We drive the controller via [com.letta.mobile.data.controller.node.iroh.PairedPeerStore]
 * writes (the production code path the install screen polls) so the
 * tests stay honest about how the picker gets its data.
 */
class DesktopExecutionLocationPickerTest {

    @Test
    fun defaultExecutionLocationIsLocal(@TempDir tmp: File) = runComposeUiTest {
        val (controller, _) = newController(tmp)
        setContent {
            DesktopExecutionLocationPicker(controller = controller)
        }
        // Collapsed label is the local fallback (no paired peers yet).
        onNodeWithContentDescription("Execution location: local").assertIsDisplayed()
    }

    @Test
    fun defaultExecutionLocationIsFirstPeer(@TempDir tmp: File) = runComposeUiTest {
        val (controller, store) = newController(tmp)
        store.save(PairedPeer(nodeId = "node-1", name = "alice-iphone", pairedAtMs = 1L))
        store.save(PairedPeer(nodeId = "node-2", name = "bob-ipad", pairedAtMs = 2L))
        controller.refreshPeers()
        setContent {
            DesktopExecutionLocationPicker(controller = controller)
        }
        // First peer in the list is the default execution location per the
        // acceptance criterion (refreshPeers auto-selects the first peer).
        onNodeWithContentDescription("Execution location: alice-iphone").assertIsDisplayed()
    }

    // NOTE: this test intentionally does NOT drive the picker through
    // runComposeUiTest/setContent like the other Compose-backed tests in
    // this file. Expanding the picker composes ExpandedList -> PickerRow ->
    // DesktopSelectableChip, which is backed by Jewel's `Chip` component.
    // Jewel 0.37.0-262.4852.51 ships `org.jetbrains.jewel.ui.component.ChipKt`
    // compiled to class file version 69 (JDK 25); the `shared-multiplatform`
    // CI job's toolchain is pinned to JDK 21 (`gradle/gradle-daemon-jvm.properties`,
    // required by the Kotlin/Native `hostNative` targets in :sharedLogic:allTests
    // that run in the same job), so loading that class throws
    // UnsupportedClassVersionError as soon as any test recomposes into the
    // expanded state. This asserts the same "expanding shows the full peer
    // list" behavior at the data layer — [expandedPickerRows] is the pure
    // function [ExpandedList] uses to build its rows — instead of composing
    // through the Jewel-backed row UI. See letta-mobile-sixv8.1.
    @Test
    fun expandingShowsFullPeerList(@TempDir tmp: File) {
        val (controller, store) = newController(tmp)
        store.save(PairedPeer(nodeId = "node-1", name = "alice-iphone", pairedAtMs = 1L))
        store.save(PairedPeer(nodeId = "node-2", name = "bob-ipad", pairedAtMs = 2L))
        store.save(PairedPeer(nodeId = "node-3", name = "carol-mac", pairedAtMs = 3L))
        controller.refreshPeers()

        // refreshPeers auto-selects the first peer as the default execution
        // location, matching the picker's collapsed-label acceptance
        // criterion exercised by defaultExecutionLocationIsFirstPeer above.
        assertEquals("node-1", controller.selectedExecutionLocation)

        val rows = expandedPickerRows(
            peers = controller.peers,
            selectedNodeId = controller.selectedExecutionLocation,
        )

        assertEquals(listOf("local", "alice-iphone", "bob-ipad", "carol-mac"), rows.map { it.label })
        // Exactly one row is selected, and it is the auto-selected peer.
        assertEquals(listOf(false, true, false, false), rows.map { it.selected })
        // Peer rows carry a truncated node-id subtitle; the local row does not.
        assertEquals(null, rows.first().subtitle)
        assertEquals(true, rows.drop(1).all { it.subtitle != null })
    }

    @Test
    fun selectingPeerUpdatesCollapsedLabel(@TempDir tmp: File) = runComposeUiTest {
        val (controller, store) = newController(tmp)
        store.save(PairedPeer(nodeId = "node-1", name = "alice-iphone", pairedAtMs = 1L))
        store.save(PairedPeer(nodeId = "node-2", name = "bob-ipad", pairedAtMs = 2L))
        controller.refreshPeers()
        setContent {
            DesktopExecutionLocationPicker(controller = controller)
        }
        // Default = alice-iphone (first peer).
        onNodeWithContentDescription("Execution location: alice-iphone").assertIsDisplayed()
        controller.selectExecutionLocation("node-2")
        onNodeWithContentDescription("Execution location: bob-ipad").assertIsDisplayed()
    }

    @Test
    fun pickerPureDefaultLabelDerivation() {
        val peers = listOf(
            PairedPeer(nodeId = "node-1", name = "alice-iphone", pairedAtMs = 1L),
        )
        // First peer wins as the default label.
        assertEquals("alice-iphone", defaultExecutionLocationLabel(peers))
        // Empty store falls back to local.
        assertEquals("local", defaultExecutionLocationLabel(emptyList()))
        // Custom local label respected.
        assertEquals(
            "this-desktop",
            defaultExecutionLocationLabel(emptyList(), localLabel = "this-desktop"),
        )
    }

    private fun newController(tmp: File): Pair<DesktopPairInviteController, InMemoryPairedPeerStore> {
        val store = InMemoryPairedPeerStore()
        val keyBytes = ByteArray(32) { (it + 1).toByte() }
        val keyStore = FixedIrohSecretKeyStore(keyBytes)
        val nodeId = runBlockingSha256Hex(keyBytes)
        val router = AdminRpcRouter()
        val pairing = IrohPairingService(store = store)
        PairingAdminHandlers.register(router, pairing, TestSigner(), nodeId)
        return DesktopPairInviteController(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            router = router,
            pairing = pairing,
            pairingStore = store,
            pngOutputFile = File(tmp, "pair.png"),
        ) to store
    }

    private fun runBlockingSha256Hex(bytes: ByteArray): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(bytes)
        return md.digest().joinToString("") { b -> "%02x".format(b) }
    }

    private class TestSigner : PairQrSigner {
        override fun sign(nodeIdHex: String, signedSecret: String, expiresAtMs: Long): String = "test-sig"
    }
}
