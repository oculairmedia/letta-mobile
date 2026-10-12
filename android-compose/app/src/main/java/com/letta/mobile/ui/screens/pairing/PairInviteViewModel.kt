package com.letta.mobile.ui.screens.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.pairing.PairInviteRequest
import com.letta.mobile.data.pairing.PairInviteSession
import com.letta.mobile.data.pairing.PairInviteUiState
import com.letta.mobile.data.pairing.PairingAdminRepository
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.modelcontrol.AdminRpcInvoker
import com.letta.mobile.qr.ZxingQrEncoder
import com.letta.mobile.ui.pairing.AgentChoice
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * "Pair a device" on the phone (letta-mobile-dpv7m.2): the HOST mints the
 * invite over admin_rpc (`pair.invite.create`), so the QR this screen shows is
 * redeemable by the host — unlike the old in-process mint, which no listener
 * could ever redeem. Requires this device to hold admin on the host; otherwise
 * the shared session explains why.
 */
@HiltViewModel
class PairInviteViewModel @Inject constructor(
    rpc: AdminRpcInvoker,
    agentRepository: IAgentRepository,
) : ViewModel() {
    private val session = PairInviteSession(PairingAdminRepository(rpc), ZxingQrEncoder, viewModelScope)

    val state: StateFlow<PairInviteUiState> = session.state

    val agents: StateFlow<List<AgentChoice>> = agentRepository.agents
        .map { list -> list.map { AgentChoice(it.id.value, it.name) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    init {
        session.refresh()
    }

    fun mint(request: PairInviteRequest) = session.mint(request)

    fun refresh() = session.refresh()

    fun revokeDevice(nodeId: String) = session.revokeDevice(nodeId)

    fun revokeInvite(inviteId: String) = session.revokeInvite(inviteId)

    fun dismissInvite() = session.dismissInvite()

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
