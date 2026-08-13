package com.letta.mobile.ui.screens.pairing

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import com.letta.mobile.data.controller.node.iroh.PairQrEnvelope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

enum class ScanStatus { SCANNING, VALID, INVALID }

@Immutable
data class ScanUiState(
    val status: ScanStatus = ScanStatus.SCANNING,
    val decoded: PairQrEnvelope.Decoded? = null,
    val errorMessage: String? = null,
)

/**
 * State machine for the client-mode QR scan screen (letta-mobile-g2d2i).
 *
 * The camera analyzer calls [onQrDecoded] for every frame that yields
 * *some* readable QR payload (not necessarily a valid pairing envelope —
 * that judgment happens here via [PairingQrDecoder]). [uiState] drives the
 * overlay: a scanning spinner, a success card with the peer's suggested
 * identity, or a specific rejection reason.
 */
@HiltViewModel
class QrScanViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(ScanUiState())
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    /**
     * De-dupes repeated frames carrying the same payload once we've already
     * settled on a verdict, so a camera holding steady on one code doesn't
     * thrash state every ~30ms. Reset by [resetToScanning].
     */
    private var lastHandledRaw: String? = null

    fun onQrDecoded(raw: String, nowMs: Long = System.currentTimeMillis()) {
        if (_uiState.value.status != ScanStatus.SCANNING && raw == lastHandledRaw) return
        lastHandledRaw = raw
        _uiState.value = when (val result = PairingQrDecoder.decode(raw, nowMs)) {
            is PairingQrDecoder.Result.Valid -> ScanUiState(
                status = ScanStatus.VALID,
                decoded = result.decoded,
                errorMessage = null,
            )
            is PairingQrDecoder.Result.Invalid -> ScanUiState(
                status = ScanStatus.INVALID,
                decoded = null,
                errorMessage = messageFor(result.reason),
            )
        }
    }

    /** Return to the scanning state, e.g. after the user dismisses an error or a "scan again" tap. */
    fun resetToScanning() {
        lastHandledRaw = null
        _uiState.value = ScanUiState()
    }

    private fun messageFor(reason: PairingQrDecoder.Reason): String = when (reason) {
        PairingQrDecoder.Reason.WRONG_PREFIX ->
            "That QR code isn't a Letta pairing code."
        PairingQrDecoder.Reason.WRONG_VERSION ->
            "This pairing code uses a format this version of the app doesn't understand. Update the app and try again."
        PairingQrDecoder.Reason.MALFORMED ->
            "This pairing code is damaged or incomplete. Ask the other device to generate a new one."
        PairingQrDecoder.Reason.EXPIRED ->
            "This pairing code has expired. Ask the other device to generate a new one."
    }
}
