package com.letta.mobile.ui.screens.pairing

import com.letta.mobile.data.controller.node.iroh.PairQrEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PairingQrDecoderTest {

    private val nowMs = 1_000_000L

    private fun validEncoded(expiresAtMs: Long = nowMs + 60_000L): String = PairQrEnvelope.encode(
        nodeIdHex = "ab".repeat(32),
        signedSecret = "invite:" + "cd".repeat(32),
        expiresAtMs = expiresAtMs,
        signature = "sig-value",
    )

    @Test
    fun `valid envelope decodes successfully`() {
        val result = PairingQrDecoder.decode(validEncoded(), nowMs)
        assertTrue(result is PairingQrDecoder.Result.Valid)
        val decoded = (result as PairingQrDecoder.Result.Valid).decoded
        assertEquals(1, decoded.version)
        assertEquals("ab".repeat(32), decoded.nodeIdHex)
    }

    @Test
    fun `wrong prefix is rejected distinctly`() {
        val result = PairingQrDecoder.decode("https://example.com/not-a-pairing-code", nowMs)
        assertEquals(PairingQrDecoder.Reason.WRONG_PREFIX, (result as PairingQrDecoder.Result.Invalid).reason)
    }

    @Test
    fun `empty string is rejected as wrong prefix`() {
        val result = PairingQrDecoder.decode("", nowMs)
        assertEquals(PairingQrDecoder.Reason.WRONG_PREFIX, (result as PairingQrDecoder.Result.Invalid).reason)
    }

    @Test
    fun `wrong version is rejected distinctly from generic malformed`() {
        val body = """{"version":2,"node_id":"ab","signed_secret":"cd","expires_at_ms":${nowMs + 60_000},"signature":"sig"}"""
        val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(body.toByteArray())
        val raw = "${PairQrEnvelope.SCHEME}.$b64"
        val result = PairingQrDecoder.decode(raw, nowMs)
        assertEquals(PairingQrDecoder.Reason.WRONG_VERSION, (result as PairingQrDecoder.Result.Invalid).reason)
    }

    @Test
    fun `malformed base64 is rejected as malformed`() {
        val raw = "${PairQrEnvelope.SCHEME}.not-valid-base64!!!"
        val result = PairingQrDecoder.decode(raw, nowMs)
        assertEquals(PairingQrDecoder.Reason.MALFORMED, (result as PairingQrDecoder.Result.Invalid).reason)
    }

    @Test
    fun `valid base64 but non-json body is rejected as malformed`() {
        val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString("not json at all".toByteArray())
        val raw = "${PairQrEnvelope.SCHEME}.$b64"
        val result = PairingQrDecoder.decode(raw, nowMs)
        assertEquals(PairingQrDecoder.Reason.MALFORMED, (result as PairingQrDecoder.Result.Invalid).reason)
    }

    @Test
    fun `missing required field is rejected as malformed`() {
        val body = """{"version":1,"node_id":"ab","expires_at_ms":${nowMs + 60_000},"signature":"sig"}"""
        val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(body.toByteArray())
        val raw = "${PairQrEnvelope.SCHEME}.$b64"
        val result = PairingQrDecoder.decode(raw, nowMs)
        assertEquals(PairingQrDecoder.Reason.MALFORMED, (result as PairingQrDecoder.Result.Invalid).reason)
    }

    @Test
    fun `expired envelope is rejected distinctly`() {
        val raw = validEncoded(expiresAtMs = nowMs - 1L)
        val result = PairingQrDecoder.decode(raw, nowMs)
        assertEquals(PairingQrDecoder.Reason.EXPIRED, (result as PairingQrDecoder.Result.Invalid).reason)
    }

    @Test
    fun `envelope expiring exactly now is rejected as expired`() {
        val raw = validEncoded(expiresAtMs = nowMs)
        val result = PairingQrDecoder.decode(raw, nowMs)
        assertEquals(PairingQrDecoder.Reason.EXPIRED, (result as PairingQrDecoder.Result.Invalid).reason)
    }
}
