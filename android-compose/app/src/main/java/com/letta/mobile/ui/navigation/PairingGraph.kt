package com.letta.mobile.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.letta.mobile.ui.screens.pairing.PairInviteScreen
import com.letta.mobile.ui.screens.pairing.QrScanScreen

/**
 * letta-mobile-g2d2i / dpv7m.2: scan-to-pair (joining device) and "Pair a
 * device" (inviter). Both talk to the HOST: the scanner redeems the
 * host-minted invite and lands in chat; the inviter asks the host to mint.
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
        PairInviteScreen(onNavigateBack = { navController.popBackStack() })
    }
}
