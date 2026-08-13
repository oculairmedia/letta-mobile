package com.letta.mobile.ui.screens.pairing

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.controller.node.iroh.FileIrohSecretKeyStore
import com.letta.mobile.data.controller.node.iroh.FilePairedPeerStore
import com.letta.mobile.data.controller.node.iroh.IrohNodeIdentity
import com.letta.mobile.data.controller.node.iroh.IrohSecretKeyStore
import com.letta.mobile.data.controller.node.iroh.PairedPeerStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Hilt wrapper around [PairInviteController] (letta-mobile-g2d2i, server
 * mode). All the actual state-machine logic lives in the controller so it
 * stays unit-testable without Hilt/Android context; this class only wires
 * up the on-device identity + peer store and republishes [uiState].
 *
 * UNVERIFIED: reuses the SAME secret-key file
 * (`iroh-client-identity.key`) that `SessionGraphFactory` already binds for
 * this device's outbound Iroh connections (d6e8g.9), on the theory that a
 * device's Iroh identity is a single stable NodeId regardless of whether
 * it's dialing out or being dialed into. Whether the phone's actual
 * listening Iroh endpoint (if one is running) shares this same
 * `iroh-paired-peers.json` [PairedPeerStore] instance — i.e. whether a
 * redemption against an invite minted here is actually visible to whatever
 * accepts the incoming connection — is NOT verified by this bead; see the
 * PR report for the "server mode" integration-gap note.
 */
@HiltViewModel
class PairInviteViewModel @Inject constructor(
    @ApplicationContext context: Context,
) : ViewModel() {

    private val secretKeyStore: IrohSecretKeyStore =
        FileIrohSecretKeyStore(File(context.filesDir, "iroh-client-identity.key").path)
    private val pairingStore: PairedPeerStore =
        FilePairedPeerStore(File(context.filesDir, "iroh-paired-peers.json").toPath())

    private val _uiState = MutableStateFlow(PairInviteUiState(loading = true))
    val uiState: StateFlow<PairInviteUiState> = _uiState.asStateFlow()

    private var controller: PairInviteController? = null

    init {
        viewModelScope.launch {
            val nodeIdHex = withContext(Dispatchers.IO) {
                IrohNodeIdentity.nodeIdHexFromSecretBytes(secretKeyStore.loadOrCreate())
            }
            val built = PairInviteController.fromIdentity(
                scope = viewModelScope,
                secretKeyStore = secretKeyStore,
                pairingStore = pairingStore,
                nodeIdHex = nodeIdHex,
            )
            controller = built
            launch { built.uiState.collect { _uiState.value = it } }
            built.mint()
        }
    }

    fun regenerate() {
        controller?.mint()
    }
}
