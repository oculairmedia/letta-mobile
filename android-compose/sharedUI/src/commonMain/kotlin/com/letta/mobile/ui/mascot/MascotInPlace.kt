package com.letta.mobile.ui.mascot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.letta.mobile.avatar.core.MascotIdentity

/**
 * [agentId]'s mascot drawn where it is, by the one live-or-still rule: the live scene while [live]
 * (see [mascotAtWork]), otherwise the identity's still - the same picture every list, chip and
 * header shows. [MascotAvatar] and a [MascotSeat] with no transport layer both draw through here,
 * so a seat cannot picture an idle agent differently from its avatar (letta-mobile-c3np7.5.8).
 */
@Composable
internal fun MascotInPlace(
    agentId: String,
    identity: MascotIdentity,
    size: Dp,
    live: Boolean,
    onClick: (() -> Unit)? = null,
) {
    if (live) {
        MascotLive(agentId, identity, size = size, onClick = onClick)
        return
    }
    Box(Modifier.requiredSize(size), contentAlignment = Alignment.Center) {
        MascotStill(agentId, identity, size = size)
        if (onClick != null) MascotHitArea(size, onClick)
    }
}

/**
 * What a drawn mascot shows, in semantics: the identity it is drawn in and whether it moves. Every
 * mascot drawing carries it, so a test can check that two pictures of one agent agree without
 * reading pixels.
 */
object MascotSemantics {
    val Identity: SemanticsPropertyKey<MascotIdentity> = SemanticsPropertyKey("MascotIdentity")
    val Live: SemanticsPropertyKey<Boolean> = SemanticsPropertyKey("MascotLive")
}

private var SemanticsPropertyReceiver.mascotIdentity by MascotSemantics.Identity
private var SemanticsPropertyReceiver.mascotLive by MascotSemantics.Live

internal fun Modifier.mascotSemantics(identity: MascotIdentity, live: Boolean): Modifier = semantics {
    mascotIdentity = identity
    mascotLive = live
}
