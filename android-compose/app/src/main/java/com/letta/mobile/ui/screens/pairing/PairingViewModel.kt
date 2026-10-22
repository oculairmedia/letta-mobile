package com.letta.mobile.ui.screens.pairing

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.pairing.PairingState
import com.letta.mobile.data.pairing.composition.PairingComposition
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.ui.pairing.AgentChoice
import com.letta.mobile.ui.pairing.InstallAndPairBindings
import com.letta.mobile.ui.pairing.composition.installAndPairBindings
import com.letta.mobile.ui.pairing.pairingScanActions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Android's pairing ports over the shared composition (letta-mobile-dpv7m.2/.5, wisqe.1): the
 * scan screen's join flow and the Settings install-and-pair card are both built from
 * [AndroidPairingPorts] by shared code, which always stops at the trust prompt before dialling.
 */
@HiltViewModel
class PairingViewModel @Inject constructor(
    private val ports: AndroidPairingPorts,
    agentRepository: IAgentRepository,
) : ViewModel() {
    private val flow = PairingComposition(ports).joinFlow()

    val state: StateFlow<PairingState> = flow.state

    /** The roster an invite may be limited to. */
    val agents: StateFlow<List<AgentChoice>> = agentRepository.agents
        .map { list -> list.map { AgentChoice(it.id.value, it.name) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    fun actions(onOpenChat: () -> Unit) = pairingScanActions(flow, viewModelScope, onOpenChat)

    /** A link opened from outside the app: parsed, then held at the trust prompt. */
    fun submit(raw: String) {
        flow.submit(raw)
    }

    /** The Settings install-and-pair card; [onOpenChat] after a successful pairing. */
    fun installAndPair(context: Context, agents: List<AgentChoice>, onOpenChat: () -> Unit): InstallAndPairBindings =
        installAndPairBindings(ports, AndroidPairingShell(context, agents, onOpenChat))

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
