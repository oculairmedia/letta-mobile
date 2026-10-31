package com.letta.mobile.ui.mascot

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.letta.mobile.avatar.core.model.MascotIdentity
import com.letta.mobile.ui.theme.LocalReducedMotion

/**
 * [agentId]'s mascot drawn where it is, by the one live-or-still rule: the live scene while [live]
 * (see [mascotAtWork]), otherwise the identity's still - the same picture every list, chip and
 * header shows. [MascotAvatar] and a [MascotSeat] with no transport layer both draw through here,
 * so a seat cannot picture an idle agent differently from its avatar.
 */
@Composable
internal fun MascotInPlace(
    agentId: String,
    identity: MascotIdentity,
    size: Dp,
    live: Boolean,
    onClick: (() -> Unit)? = null,
    /** Live, the character answers gestures (see [MascotLive]); a still only clicks. */
    touch: MascotTouch = MascotTouch.answering(onClick),
) {
    MascotLiveSwap(
        live = live,
        liveContent = { MascotLive(agentId, identity, size = size, onClick = onClick, touch = touch) },
        stillContent = {
            Box(Modifier.requiredSize(size), contentAlignment = Alignment.Center) {
                MascotStill(agentId, identity, size = size)
                if (onClick != null) MascotHitArea(size, onClick)
            }
        },
    )
}

/**
 * Still and live cross-fade rather than cut: the two are different poses of one character (and the
 * live scene's first frame lands a frame or two after it is composed), so a cut reads as a glitch.
 */
@Composable
internal fun MascotLiveSwap(live: Boolean, liveContent: @Composable () -> Unit, stillContent: @Composable () -> Unit) {
    val spec = if (LocalReducedMotion.current) snap<Float>() else tween(LIVE_SWAP_MS)
    Crossfade(targetState = live, animationSpec = spec, label = "mascotLiveSwap") { isLive ->
        if (isLive) liveContent() else stillContent()
    }
}

/** How long a still and the live scene cross-fade; a state change never lands in one frame. */
private const val LIVE_SWAP_MS = 220

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
