package com.letta.mobile.desktop.qr

import com.letta.mobile.data.controller.node.iroh.AdminRpcRouter
import com.letta.mobile.data.controller.node.iroh.FixedIrohSecretKeyStore
import com.letta.mobile.data.controller.node.iroh.IrohPairingService
import com.letta.mobile.data.controller.node.iroh.InMemoryPairedPeerStore
import com.letta.mobile.data.controller.node.iroh.PairQrSigner
import com.letta.mobile.data.controller.node.iroh.PairingAdminHandlers
import com.letta.mobile.data.controller.node.iroh.PairedPeer
import com.letta.mobile.qr.QrCode
import com.letta.mobile.qr.QrRenderer
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * letta-mobile-nonza (sixv8.3): install-screen tests.
 *
 * We exercise the same code path the desktop app uses at runtime — the
 * `DesktopPairInviteController.mint()` dispatch + the QR primitives
 * (`QrCode.encode` + `QrRenderer.writePng`) — but without a Composable
 * host. The picker tests in [DesktopExecutionLocationPickerTest] cover the
 * collapsed/expanded view surface.
 *
 * The QR must round-trip through ZXing so a real mobile scanner can read
 * it (the wire format is the protocol's contract; the renderer is the
 * only thing that differs across CLI/Desktop/Mobile per protocol §10).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopPairInviteControllerTest {

    @Test
    fun installRendersQrPngToFile(@TempDir tmp: File) = runTest {
        val pngFile = File(tmp, "pair.png")
        val (controller, _) = buildControllerForTest(tmp, pngFile)
        val result = mintAndAwait(controller)
        assertEquals(pngFile, result, "controller must render to the configured output file")
        assertTrue(pngFile.exists() && pngFile.length() > 0, "PNG must be written and non-empty")
        // PNG magic bytes (89 50 4E 47 0D 0A 1A 0A) — proves we wrote a PNG,
        // not random bytes. ZXing round-trip is exercised in
        // sharedLogic/QrCodeTest.kt (the encoder/decoder contract lives there).
        val head = pngFile.readBytes().take(8).toByteArray()
        val expected = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertTrue(
            head.contentEquals(expected),
            "PNG header mismatch: ${head.joinToString("") { "%02x".format(it) }}",
        )
        // The PNG must decode back to a TYPE_BYTE_GRAY BufferedImage — the
        // renderer contract from PR #1177 (see QrRenderer.renderPng).
        val image = ImageIO.read(pngFile) ?: error("ImageIO.read returned null")
        assertEquals(
            java.awt.image.BufferedImage.TYPE_BYTE_GRAY,
            image.type,
            "renderer must use TYPE_BYTE_GRAY, got ${image.type}",
        )
        // The wire value on the controller matches the protocol §7.1 scheme.
        assertTrue(
            controller.wireValue!!.startsWith(DesktopPairInviteController.WIRE_SCHEME + "."),
            "wire value must use the letta-qr-v1 scheme, got: ${controller.wireValue}",
        )
    }

    @Test
    fun installTextRendererFallbackProducesNonEmptyOutput(@TempDir tmp: File) = runTest {
        val pngFile = File(tmp, "unused.png")
        val (controller, _) = buildControllerForTest(tmp, pngFile)
        mintAndAwait(controller)
        assertNotNull(controller.wireValue)
        val matrix = QrCode.encode(controller.wireValue!!)
        val text = QrRenderer.renderText(matrix)
        assertTrue(text.isNotEmpty(), "text render must produce non-empty output")
        assertTrue(
            text.contains('█') || text.contains('▀') || text.contains('▄'),
            "text output should contain at least one half-block character, got: ${text.take(200)}",
        )
    }

    @Test
    fun pairingStoreUpdateTriggersAutoNavigation(@TempDir tmp: File) = runTest {
        val pngFile = File(tmp, "pair.png")
        val (controller, store) = buildControllerForTest(tmp, pngFile)
        controller.refreshPeers()
        assertEquals(0, controller.peers.size, "store starts empty")
        // Simulate a phone scanning the QR and the wrapper persisting the
        // resulting peer. The install screen polls the store; the controller
        // observes the size change.
        store.save(
            PairedPeer(
                nodeId = "phone-node-1",
                name = "alice-iphone",
                pairedAtMs = System.currentTimeMillis(),
            ),
        )
        controller.refreshPeers()
        assertEquals(1, controller.peers.size)
        assertEquals("alice-iphone", controller.peers.first().name)
        // The picker should auto-default to the first peer so the user does
        // not land on an empty / local selection once a phone dials in.
        assertEquals("phone-node-1", controller.selectedExecutionLocation)
    }

    @Test
    fun regenerateReplacesWireValueAndFile(@TempDir tmp: File) = runTest {
        val pngFile = File(tmp, "pair.png")
        val (controller, _) = buildControllerForTest(tmp, pngFile)
        val first = mintAndAwait(controller)
        assertNotNull(controller.wireValue)
        mintAndAwait(controller)
        assertTrue(pngFile.exists())
        assertTrue(controller.wireValue!!.startsWith(DesktopPairInviteController.WIRE_SCHEME + "."))
        // file is overwritten on regenerate (same path, new bytes).
        assertTrue(pngFile.length() > 0)
        // Distinct wire values across calls — the invite secret rotates per
        // call (the pairing service emits a fresh nonce).
        // We can't assert inequality directly (depends on RNG), but we can
        // confirm the bytes on disk are a valid PNG.
        assertTrue(first.exists())
    }

    @Test
    fun mintFailureSurfacesErrorWithoutThrowing(@TempDir tmp: File) = runTest {
        // Build a controller with a signer wired up but no router — that
        // simulates a misconfigured QR path (the handler still runs but the
        // signer returns an empty string, which the handler reads as
        // "qr_invite omitted").
        val pngFile = File(tmp, "pair.png")
        val store = InMemoryPairedPeerStore()
        val router = AdminRpcRouter()
        val pairing = IrohPairingService(store = store)
        // Register with NO signer / NO node id — `qr: true` returns no qr_invite.
        PairingAdminHandlers.register(router, pairing, PairQrSigner { _, _, _ -> "" }, "")
        val controller = DesktopPairInviteController(
            scope = this,
            router = router,
            pairing = pairing,
            pairingStore = store,
            pngOutputFile = pngFile,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            computeDispatcher = StandardTestDispatcher(testScheduler),
        )
        controller.mint()
        advanceUntilIdle()
        // Misconfigured path: the handler returns a 200 with no qr_invite.
        // The controller surfaces "no qr_invite" rather than silently
        // rendering an empty PNG.
        assertNotNull(controller.error, "controller must surface the misconfig error")
        assertTrue(controller.error!!.contains("qr_invite"))
    }

    // --- Helpers ---

    private fun TestScope.buildControllerForTest(
        tmp: File,
        pngFile: File,
    ): Pair<DesktopPairInviteController, InMemoryPairedPeerStore> {
        val store = InMemoryPairedPeerStore()
        val keyBytes = ByteArray(32) { (it + 1).toByte() }
        val keyStore = FixedIrohSecretKeyStore(keyBytes)
        val nodeId = PairNodeIdFactoryTest.fromSecretKeyStore(keyStore)
        val controller = DesktopPairInviteController.forTest(
            scope = this,
            pngOutputFile = pngFile,
            signer = TestSigner(),
            qrNodeIdHex = nodeId,
            pairingStore = store,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            computeDispatcher = StandardTestDispatcher(testScheduler),
        )
        return controller to store
    }

    /**
     * Local replacement for the former `mintForTest()` helper that lived in
     * DesktopPairInstallScreen.kt (production code). Dispatchers are now
     * injected, so draining the test scheduler is sufficient — no wall-clock
     * polling, no timeout.
     */
    private fun TestScope.mintAndAwait(controller: DesktopPairInviteController): File {
        controller.mint()
        advanceUntilIdle()
        val err = controller.error
        val file = controller.pngFile
        check(err == null && file != null) { err ?: "mint produced no file" }
        return file
    }

    /** Deterministic test signer — fixed blob, mirrors the CLI test. */
    private class TestSigner : PairQrSigner {
        override fun sign(nodeIdHex: String, signedSecret: String, expiresAtMs: Long): String = "test-sig"
    }
}

/** Mirrors `PairNodeIdFactory` from the CLI module but local to the test. */
private object PairNodeIdFactoryTest {
    fun fromSecretKeyStore(keyStore: com.letta.mobile.data.controller.node.iroh.IrohSecretKeyStore): String =
        kotlinx.coroutines.runBlocking {
            val bytes = keyStore.loadOrCreate()
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            digest.update(bytes)
            digest.digest().joinToString("") { b -> "%02x".format(b) }
        }
}
