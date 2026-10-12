package com.letta.mobile.ui.screens.pairing

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.pairing.PairingFlow
import com.letta.mobile.data.pairing.PairingState
import com.letta.mobile.data.pairing.SettingsPairedHostStore
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.security.KeystoreIrohIdentityStore
import com.letta.mobile.data.transport.iroh.IrohPairingRedeemer
import com.letta.mobile.runtime.iroh.IrohAndroidInit
import com.letta.mobile.ui.pairing.PairingScanActions
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Android binding of the shared scan -> confirm -> pair -> persist -> connect
 * flow (letta-mobile-dpv7m.2/.5). The camera / deep link / paste supplies
 * text; the shared [PairingFlow] does everything else and always stops at the
 * trust prompt before dialling. The redeemer dials with the SAME
 * Keystore-sealed identity the chat session uses, so the NodeId the host
 * binds is the one that reconnects.
 */
@HiltViewModel
class PairingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    settingsRepository: ISettingsRepository,
) : ViewModel() {
    private val flow = PairingFlow(
        redeemer = IrohPairingRedeemer(KeystoreIrohIdentityStore.forApp(context)),
        store = SettingsPairedHostStore(settingsRepository),
        deviceName = deviceName(),
        nowMs = System::currentTimeMillis,
    )

    val state: StateFlow<PairingState> = flow.state

    fun actions(onOpenChat: () -> Unit) = PairingScanActions(
        onSubmit = { raw -> flow.submit(raw) },
        onConfirm = { dial { flow.confirm() } },
        onCancel = flow::cancel,
        onRetry = { dial { flow.retry() } },
        onScanAgain = flow::reset,
        onOpenChat = onOpenChat,
    )

    /** A link opened from outside the app: parsed, then held at the trust prompt. */
    fun submit(raw: String) {
        flow.submit(raw)
    }

    private fun dial(block: suspend () -> Unit) {
        viewModelScope.launch {
            IrohAndroidInit.install(context)
            block()
        }
    }

    private fun deviceName(): String = listOfNotNull(Build.MANUFACTURER, Build.MODEL)
        .joinToString(" ")
        .ifBlank { "Android device" }
}
