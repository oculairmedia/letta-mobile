package com.letta.mobile.ui.navigation

import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.letta.mobile.ui.pairing.PairDeviceBinding
import com.letta.mobile.ui.pairing.PairDevicePage
import com.letta.mobile.ui.screens.pairing.AndroidPairingShell
import com.letta.mobile.ui.screens.pairing.PairInviteViewModel
import com.letta.mobile.ui.screens.pairing.PairingViewModel
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
                navController.popBackStack(ShellWorkspaceRoute, inclusive = false)
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
        val agents by hiltViewModel<PairingViewModel>().agents.collectAsStateWithLifecycle()
        val context = LocalContext.current
        PairDevicePage(
            binding = PairDeviceBinding(viewModel.session, agents, System::currentTimeMillis) { AndroidPairingShell.share(context, it) },
            onNavigateBack = { navController.popBackStack() },
        )
    }
}

