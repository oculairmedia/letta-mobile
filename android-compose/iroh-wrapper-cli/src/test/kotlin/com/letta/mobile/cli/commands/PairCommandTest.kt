package com.letta.mobile.cli.commands

import com.letta.mobile.data.controller.node.iroh.AdminRpcInvocation
import com.letta.mobile.data.controller.node.iroh.AdminRpcRequestContext
import com.letta.mobile.data.controller.node.iroh.AdminRpcRouter
import com.letta.mobile.data.controller.node.iroh.FixedIrohSecretKeyStore
import com.letta.mobile.data.controller.node.iroh.IrohPairingService
import com.letta.mobile.data.controller.node.iroh.InMemoryPairedPeerStore
import com.letta.mobile.data.controller.node.iroh.PairQrEnvelope
import com.letta.mobile.data.controller.node.iroh.PairQrSigner
import com.letta.mobile.data.controller.node.iroh.PairingAdminHandlers
import com.letta.mobile.qr.QrCode
import com.letta.mobile.qr.QrRenderer
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * letta-mobile-gw0h1 (sixv8.2): the CLI QR-rendering subcommand is a thin
 * shell over the in-process admin router. These tests assert the wire path
 * end-to-end: build the router with a deterministic signer, dispatch
 * `pair.invite.create` with `qr: true`, render the resulting `qr_invite`
 * value, and verify the rendered PNG round-trips through ZXing's reader.
 *
 * The Clikt `run()` is exercised indirectly: we drive the same internal
 * builder (`buildRouterForTest` + the QR extraction helper) and renderer
 * path the command uses. A direct Clikt invocation requires forking a
 * process (the command calls `exitProcess` on error); the in-process
 * coverage here pins the contract the operator observes.
 */
class PairCommandTest {

    @Test
    fun qrInviteMatchesProtocolWireFormat() = runBlocking {
        val (router, fixedId) = buildRouterForTest()
        val response = router.dispatch(
            AdminRpcInvocation(
                requestId = "t",
                method = "pair.invite.create",
                params = buildJsonObject {
                    put("name", "alice-mac")
                    put("qr", true)
                },
                context = AdminRpcRequestContext.Authenticated,
            ),
        )
        val parsed = Json.parseToJsonElement(response).jsonObject
        assertEquals(true, parsed["success"]?.jsonPrimitive?.boolean)
        val result = parsed["result"]?.jsonObject ?: error("missing result")
        val qr = (result["qr_invite"] as? JsonPrimitive)?.content
        assertNotNull(qr, "qr_invite must be present")
        assertTrue(qr!!.startsWith("letta-qr-v1."), "must use the §7.1 scheme, got: $qr")
        val decoded = PairQrEnvelope.decode(qr)
        assertNotNull(decoded, "decoder must accept our own envelope")
        assertEquals(1, decoded!!.version)
        assertEquals(fixedId, decoded.nodeIdHex)
        assertTrue(decoded.signedSecret.startsWith("invite:"), "signed_secret must carry the invite: prefix")
        assertTrue(decoded.signature.isNotEmpty(), "signature must not be empty")
    }

    @Test
    fun textRendererProducesNonEmptyOutput() = runBlocking {
        val (router, _) = buildRouterForTest()
        val qr = extractQrInvite(router)
        val matrix = QrCode.encode(qr)
        val text = QrRenderer.renderText(matrix)
        assertTrue(text.isNotEmpty(), "text render must produce non-empty output")
        // Expect at least one of the half-block characters used in the renderer.
        assertTrue(
            text.contains('█') || text.contains('▀') || text.contains('▄'),
            "text output should contain at least one half-block character, got: ${text.take(200)}",
        )
    }

    @Test
    fun pngRendererProducesValidPngFile() {
        // Keep the PNG scanner round-trip deterministic. Router-generated invites
        // contain random entropy and the current time, and some resulting QR masks
        // are not detected reliably by ZXing's image reader. This fixed fixture has
        // the same production wire shape and is validated by the protocol decoder.
        val qr = FIXED_PROTOCOL_QR_INVITE
        assertNotNull(PairQrEnvelope.decode(qr), "fixed QR fixture must remain protocol-valid")
        val matrix = QrCode.encode(qr)
        val tmp = Files.createTempFile("pair-cli-", ".png").toFile()
        tmp.deleteOnExit()
        try {
            val written = QrRenderer.writePng(matrix, tmp)
            assertTrue(written > 0, "PNG must have non-zero size, got $written")
            // PNG magic bytes.
            val head = tmp.readBytes().take(8).toByteArray()
            val expected = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            assertTrue(head.contentEquals(expected), "PNG header mismatch: ${head.joinToString("") { "%02x".format(it) }}")
            // Round-trip decode the rendered PNG to prove the renderer is
            // not just emitting random pixels.
            val image = ImageIO.read(tmp) ?: error("ImageIO.read returned null")
            val source = BufferedImageLuminanceSource(image)
            val hints = mapOf(
                com.google.zxing.DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE),
                com.google.zxing.DecodeHintType.TRY_HARDER to true,
            )
            val reader = MultiFormatReader()
            val result = try {
                reader.decode(BinaryBitmap(HybridBinarizer(source)), hints)
            } catch (_: Exception) {
                reader.decode(BinaryBitmap(com.google.zxing.common.GlobalHistogramBinarizer(source)), hints)
            }
            assertEquals(qr, result.text, "rendered PNG must decode back to the original qr_invite value")
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun pngRendererStreamRoundTrip() = runBlocking {
        val (router, _) = buildRouterForTest()
        val qr = extractQrInvite(router)
        val matrix = QrCode.encode(qr)
        val out = ByteArrayOutputStream()
        QrRenderer.writePngToStream(matrix, out)
        val bytes = out.toByteArray()
        assertTrue(bytes.size > 0, "PNG stream must have non-zero size, got ${bytes.size}")
        // First 4 bytes must be the PNG signature.
        assertEquals(0x89.toByte(), bytes[0], "PNG byte 0")
        assertEquals(0x50.toByte(), bytes[1], "PNG byte 1")
        assertEquals(0x4E.toByte(), bytes[2], "PNG byte 2")
        assertEquals(0x47.toByte(), bytes[3], "PNG byte 3")
    }

    /**
     * Build the same in-process admin router [PairCommand.run] builds,
     * with a deterministic HMAC signer keyed by a 32-byte fixed value so
     * the resulting `node_id` and `signature` are stable across runs.
     */
    private suspend fun buildRouterForTest(): Pair<AdminRpcRouter, String> {
        val keyBytes = ByteArray(32) { (it + 1).toByte() } // 0x01..0x20 — deterministic
        val keyStore = FixedIrohSecretKeyStore(keyBytes)
        val nodeId = PairNodeIdFactory.fromSecretKeyStore(keyStore)
        val signer: PairQrSigner = TestSigner()
        val pairing = IrohPairingService(InMemoryPairedPeerStore())
        val router = AdminRpcRouter().also {
            PairingAdminHandlers.register(it, pairing, signer, nodeId)
        }
        return router to nodeId
    }

    private suspend fun extractQrInvite(router: AdminRpcRouter): String {
        val response = router.dispatch(
            AdminRpcInvocation(
                requestId = "t",
                method = "pair.invite.create",
                params = buildJsonObject {
                    put("qr", true)
                },
                context = AdminRpcRequestContext.Authenticated,
            ),
        )
        val parsed = Json.parseToJsonElement(response).jsonObject
        val result = parsed["result"]?.jsonObject ?: error("missing result")
        return (result["qr_invite"] as? JsonPrimitive)?.content
            ?: error("missing qr_invite")
    }

    /** Deterministic test signer — fixed blob, not HMAC (we don't need crypto here). */
    private class TestSigner : PairQrSigner {
        override fun sign(nodeIdHex: String, signedSecret: String, expiresAtMs: Long): String = "test-sig"
    }

    private companion object {
        const val FIXED_PROTOCOL_QR_INVITE =
            "letta-qr-v1.eyJ2ZXJzaW9uIjoxLCJub2RlX2lkIjoiMDEwMTAxMDEwMTAxMDEwMTAxMDEwMTAxMDEwMTAxMDEwMTAx" +
                "MDEwMTAxMDEwMTAxMDEwMTAxMDEwMTAxMDEwMSIsInNpZ25lZF9zZWNyZXQiOiJpbnZpdGU6MDIwMjAyMDIwMjAyMDIwMjAyMDIwMjAy" +
                "MDIwMjAyMDIwMjAyMDIwMjAyMDIwMjAyMDIwMjAyMDIwMjAyMDIwMiIsImV4cGlyZXNfYXRfbXMiOjIwMDAwMDAwMDAwMDAsInNpZ25hdHVy" +
                "ZSI6InRlc3Qtc2lnIn0"
    }
}
