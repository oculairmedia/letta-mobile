@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop.qr

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

    @Test
    fun expandingShowsFullPeerList(@TempDir tmp: File) = runComposeUiTest {
        val (controller, store) = newController(tmp)
        store.save(PairedPeer(nodeId = "node-1", name = "alice-iphone", pairedAtMs = 1L))
        store.save(PairedPeer(nodeId = "node-2", name = "bob-ipad", pairedAtMs = 2L))
        store.save(PairedPeer(nodeId = "node-3", name = "carol-mac", pairedAtMs = 3L))
        controller.refreshPeers()
        setContent {
            DesktopExecutionLocationPicker(controller = controller)
        }
        // Click the collapsed chip to expand.
        onNodeWithContentDescription("Execution location: alice-iphone").performClick()
        // Expanded list shows each peer as a clickable row. Compose's text
        // semantics merge the chip + row, so we get multiple matches for
        // the default peer (chip + row). Use assertCountEquals(>=1) via a
        // different matcher below.
        onNodeWithText("bob-ipad").assertIsDisplayed()
        onNodeWithText("carol-mac").assertIsDisplayed()
        // The "local" contentDescription should ONLY appear on the collapsed
        // chip — when the chip is expanded it shows the selected peer, not
        // "local". The expanded list has its own local row but no
        // contentDescription (the row text is "local").
        onAllNodesWithContentDescription("Execution location: alice-iphone").assertCountEquals(1)
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
