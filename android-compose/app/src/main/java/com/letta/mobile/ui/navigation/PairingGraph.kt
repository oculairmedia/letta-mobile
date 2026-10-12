package com.letta.mobile.ui.navigation

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.letta.mobile.ui.pairing.PairDevicePage
import com.letta.mobile.ui.pairing.PairingStrings
import com.letta.mobile.ui.screens.pairing.PairInviteViewModel
import com.letta.mobile.ui.screens.pairing.QrScanScreen

/**
 * letta-mobile-g2d2i / dpv7m.2 / dpv7m.5: scan-to-pair (joining device) and
 * "Pair a device" (inviter). Both talk to the HOST: the scanner confirms the
 * host, redeems the host-minted invite and lands in chat; the inviter asks the
 * host to mint. "Pair a device" is the shared [PairDevicePage]; Android adds
 * only the share sheet.
 */
internal fun NavGraphBuilder.pairingRoutes(navController: NavHostController) {
    composable<PairingScanRoute>(
        enterTransition = drillInEnter,
        exitTransition = drillInExit,
        popEnterTransition = drillInPopEnter,
        popExitTransition = drillInPopExit,
    ) {
        QrScanScreen(
            onPaired = {
                navController.navigate(ConversationsRoute) {
                    popUpTo(0) { inclusive = true }
                    launchSingleTop = true
                }
            },
            onNavigateBack = { navController.popBackStack() },
        )
    }

    composable<PairingInviteRoute>(
        enterTransition = drillInEnter,
        exitTransition = drillInExit,
        popEnterTransition = drillInPopEnter,
        popExitTransition = drillInPopExit,
    ) {
        val viewModel: PairInviteViewModel = hiltViewModel()
        val agents by viewModel.agents.collectAsStateWithLifecycle()
        val context = LocalContext.current
        PairDevicePage(
            session = viewModel.session,
            agents = agents,
            clock = System::currentTimeMillis,
            onShareLink = { link -> sharePairLink(context, link) },
            onNavigateBack = { navController.popBackStack() },
        )
    }
}

/** The Android share sheet for a pairing link (the user picks the recipient; nothing is sent automatically). */
private fun sharePairLink(context: Context, link: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
    context.startActivity(Intent.createChooser(send, PairingStrings.INVITE_TITLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
