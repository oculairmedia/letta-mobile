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
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Android binding of the shared scan -> pair -> persist -> connect flow
 * (letta-mobile-dpv7m.2). The camera / deep link supplies text; the shared
 * [PairingFlow] does everything else. The redeemer dials with the SAME
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

    /** One scanned / opened / pasted value. Repeated frames of the same code are ignored while busy. */
    fun submit(raw: String) {
        if (state.value !is PairingState.Idle || !inFlight.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                IrohAndroidInit.install(context)
                flow.submit(raw)
            } finally {
                inFlight.set(false)
            }
        }
    }

    private val inFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    fun reset() = flow.reset()

    private fun deviceName(): String = listOfNotNull(Build.MANUFACTURER, Build.MODEL)
        .joinToString(" ")
        .ifBlank { "Android device" }
}
