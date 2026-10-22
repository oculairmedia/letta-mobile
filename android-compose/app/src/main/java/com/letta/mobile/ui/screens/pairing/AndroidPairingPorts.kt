package com.letta.mobile.ui.screens.pairing

import android.content.Context
import android.content.Intent
import android.os.Build
import com.letta.mobile.data.pairing.PairedHostStore
import com.letta.mobile.data.pairing.QrEncoder
import com.letta.mobile.data.pairing.SettingsPairedHostStore
import com.letta.mobile.data.pairing.composition.IrohPairingNetwork
import com.letta.mobile.data.pairing.composition.PairingNetwork
import com.letta.mobile.data.pairing.composition.PairingPorts
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.repository.modelcontrol.AdminRpcInvoker
import com.letta.mobile.data.security.KeystoreIrohIdentityStore
import com.letta.mobile.qr.ZxingQrEncoder
import com.letta.mobile.runtime.iroh.IrohAndroidInit
import com.letta.mobile.ui.pairing.AgentChoice
import com.letta.mobile.ui.pairing.composition.PairingShellPorts
import com.letta.mobile.ui.pairing.PairingStrings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * What Android owns for pairing (letta-mobile-wisqe.1): the Keystore-sealed identity the chat
 * session dials with, the settings repository, the host's admin_rpc and the ZXing encoder. The
 * flow, the invite session and the NodeId are the shared [com.letta.mobile.data.pairing.composition.PairingComposition].
 */
class AndroidPairingPorts @Inject constructor(
    @ApplicationContext private val context: Context,
    settingsRepository: ISettingsRepository,
    override val adminRpc: AdminRpcInvoker,
) : PairingPorts {
    private val identity by lazy { KeystoreIrohIdentityStore.forApp(context) }

    /** Iroh's JNI needs the app context before any key is turned into an endpoint or NodeId. */
    override suspend fun loadIdentityKey(): ByteArray {
        IrohAndroidInit.install(context)
        return identity.loadOrCreate()
    }

    override val network: PairingNetwork = IrohPairingNetwork()

    override val hostStore: PairedHostStore = SettingsPairedHostStore(settingsRepository)

    override val deviceName: String = listOfNotNull(Build.MANUFACTURER, Build.MODEL)
        .joinToString(" ")
        .ifBlank { "Android device" }

    override val qrEncoder: QrEncoder = ZxingQrEncoder

    override fun nowMs(): Long = System.currentTimeMillis()
}

/** Android's shell for the install-and-pair card: deep links held in [PendingPairLink] and the share sheet. */
class AndroidPairingShell(
    private val context: Context,
    override val agents: List<AgentChoice>,
    private val onOpenChat: () -> Unit,
) : PairingShellPorts {
    override val pendingLink: String? get() = PendingPairLink.peek()

    override fun consumePendingLink() {
        PendingPairLink.take()
    }

    override fun openChat() = onOpenChat()

    override fun shareLink(link: String) = share(context, link)

    companion object {
        /** The Android share sheet for a pairing link (the user picks the recipient; nothing is sent automatically). */
        fun share(context: Context, link: String) {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
            context.startActivity(Intent.createChooser(send, PairingStrings.INVITE_TITLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
