package com.letta.mobile.ui.screens.pairing

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.controller.node.iroh.IrohNodeIdentity
import com.letta.mobile.data.pairing.PairInviteSession
import com.letta.mobile.data.pairing.PairingAdminRepository
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.modelcontrol.AdminRpcInvoker
import com.letta.mobile.data.security.KeystoreIrohIdentityStore
import com.letta.mobile.qr.ZxingQrEncoder
import com.letta.mobile.runtime.iroh.IrohAndroidInit
import com.letta.mobile.ui.pairing.AgentChoice
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * "Pair a device" on the phone (letta-mobile-dpv7m.2/.5): the HOST mints the
 * invite over admin_rpc (`pair.invite.create`). This ViewModel only builds the
 * shared [PairInviteSession] (all UI is the shared PairDevicePage) and tells it
 * this phone's own NodeId so the list can show "This device".
 */
@HiltViewModel
class PairInviteViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    rpc: AdminRpcInvoker,
    agentRepository: IAgentRepository,
) : ViewModel() {
    val session = PairInviteSession(PairingAdminRepository(rpc), ZxingQrEncoder, viewModelScope, System::currentTimeMillis)

    val agents: StateFlow<List<AgentChoice>> = agentRepository.agents
        .map { list -> list.map { AgentChoice(it.id.value, it.name) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    init {
        viewModelScope.launch { session.identifySelf(selfNodeId()) }
    }

    /** The NodeId the chat session dials with (same Keystore identity); null if it cannot be derived. */
    private suspend fun selfNodeId(): String? = try {
        withContext(Dispatchers.IO) {
            IrohAndroidInit.install(context)
            IrohNodeIdentity.nodeIdHexFromSecretBytes(KeystoreIrohIdentityStore.forApp(context).loadOrCreate())
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
