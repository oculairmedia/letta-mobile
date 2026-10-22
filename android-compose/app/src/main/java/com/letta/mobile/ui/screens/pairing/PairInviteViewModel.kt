package com.letta.mobile.ui.screens.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.pairing.composition.PairingComposition
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * "Pair a device" on the phone (letta-mobile-dpv7m.2/.5): the HOST mints the invite over
 * admin_rpc (`pair.invite.create`). The shared [PairingComposition] builds the session (all UI is
 * the shared PairDevicePage) and tells it this phone's own NodeId so the list can show "This device".
 */
@HiltViewModel
class PairInviteViewModel @Inject constructor(ports: AndroidPairingPorts) : ViewModel() {
    private val pairing = PairingComposition(ports)

    val session = pairing.inviteSession(ports.adminRpc, viewModelScope)

    init {
        viewModelScope.launch { session.identifySelf(pairing.selfNodeId()) }
    }
}
