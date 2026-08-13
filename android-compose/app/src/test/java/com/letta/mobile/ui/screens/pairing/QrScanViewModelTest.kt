package com.letta.mobile.ui.screens.pairing

import com.letta.mobile.data.controller.node.iroh.PairQrEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class QrScanViewModelTest {

    private val nowMs = 1_000_000L

    private fun validEncoded(expiresAtMs: Long = nowMs + 60_000L): String = PairQrEnvelope.encode(
        nodeIdHex = "ab".repeat(32),
        signedSecret = "invite:" + "cd".repeat(32),
        expiresAtMs = expiresAtMs,
        signature = "sig-value",
    )

    @Test
    fun `initial state is scanning`() {
        val viewModel = QrScanViewModel()
        assertEquals(ScanStatus.SCANNING, viewModel.uiState.value.status)
        assertNull(viewModel.uiState.value.decoded)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `valid qr transitions to VALID with decoded envelope`() {
        val viewModel = QrScanViewModel()
        viewModel.onQrDecoded(validEncoded(), nowMs)
        val state = viewModel.uiState.value
        assertEquals(ScanStatus.VALID, state.status)
        assertNotNull(state.decoded)
        assertEquals("ab".repeat(32), state.decoded!!.nodeIdHex)
        assertNull(state.errorMessage)
    }

    @Test
    fun `garbage qr transitions to INVALID with a wrong-prefix message`() {
        val viewModel = QrScanViewModel()
        viewModel.onQrDecoded("https://example.com/totally-unrelated", nowMs)
        val state = viewModel.uiState.value
        assertEquals(ScanStatus.INVALID, state.status)
        assertNull(state.decoded)
        assertNotNull(state.errorMessage)
        assertEquals(
            true,
            state.errorMessage!!.contains("isn't a Letta pairing code"),
        )
    }

    @Test
    fun `expired qr transitions to INVALID with an expiry message`() {
        val viewModel = QrScanViewModel()
        viewModel.onQrDecoded(validEncoded(expiresAtMs = nowMs - 1L), nowMs)
        val state = viewModel.uiState.value
        assertEquals(ScanStatus.INVALID, state.status)
        assertEquals(true, state.errorMessage!!.contains("expired"))
    }

    @Test
    fun `repeated identical frame after settling does not re-emit`() {
        val viewModel = QrScanViewModel()
        val raw = "not-a-pairing-code"
        viewModel.onQrDecoded(raw, nowMs)
        val firstState = viewModel.uiState.value
        // Same raw payload again, later timestamp — should be a no-op because
        // we've already settled on a verdict for this exact frame content.
        viewModel.onQrDecoded(raw, nowMs + 5_000L)
        assertEquals(firstState, viewModel.uiState.value)
    }

    @Test
    fun `resetToScanning clears decoded state and dedup guard`() {
        val viewModel = QrScanViewModel()
        viewModel.onQrDecoded(validEncoded(), nowMs)
        assertEquals(ScanStatus.VALID, viewModel.uiState.value.status)

        viewModel.resetToScanning()
        assertEquals(ScanStatus.SCANNING, viewModel.uiState.value.status)
        assertNull(viewModel.uiState.value.decoded)

        // After reset, the same raw payload is handled again (not deduped).
        viewModel.onQrDecoded(validEncoded(), nowMs)
        assertEquals(ScanStatus.VALID, viewModel.uiState.value.status)
    }

    @Test
    fun `different invalid reasons produce different messages`() {
        val viewModel = QrScanViewModel()
        viewModel.onQrDecoded("garbage", nowMs)
        val prefixMessage = viewModel.uiState.value.errorMessage

        viewModel.resetToScanning()
        viewModel.onQrDecoded(validEncoded(expiresAtMs = nowMs - 1L), nowMs)
        val expiredMessage = viewModel.uiState.value.errorMessage

        assertNotNull(prefixMessage)
        assertNotNull(expiredMessage)
        assertEquals(false, prefixMessage == expiredMessage)
    }
}
