package com.letta.mobile

import android.content.Intent
import com.letta.mobile.ui.navigation.PairingScanRoute
import com.letta.mobile.ui.screens.pairing.PendingPairLink

/**
 * letta-mobile-dpv7m.3: a `meridian://pair?…` / `letta://pair?…` link opened
 * from the phone camera or another app. The link (it carries a one-time
 * secret) is handed to the scan screen through [PendingPairLink] in memory —
 * never as a navigation argument — and the caller clears it from the intent.
 */
data object PairLinkLaunchTarget : AppLaunchTarget {
    override fun toRoute(): PairingScanRoute = PairingScanRoute

    fun fromIntent(intent: Intent?): PairLinkLaunchTarget? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        return if (PendingPairLink.offer(intent.dataString)) PairLinkLaunchTarget else null
    }
}
