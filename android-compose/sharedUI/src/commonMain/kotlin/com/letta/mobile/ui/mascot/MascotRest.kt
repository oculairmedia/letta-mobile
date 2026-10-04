package com.letta.mobile.ui.mascot

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.data.presence.AgentPresence
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

/*
 * letta-mobile-bglj6.1.12: a seated mascot rests. The shared chat seats the agent's mascot at the
 * composer for the life of the page, and a live surface advanced its scene on every frame for as
 * long as it was composed: on Android that is a Rive TextureView producing a new buffer every
 * vsync, and every one of those redraws the whole window. A Perfetto trace of an idle phone chat
 * (nothing streaming, the ambient glow gone) showed exactly that - one Rive advance + draw and one
 * full window draw per vsync, ~60 a second, for as long as the chat was open. The legacy chat had
 * no such cost because its companion leaves composition the moment the run ends.
 *
 * This is the product rule [mascotAtWork] already states for tiles (moving while there is
 * something to show, a still otherwise), applied to the live surfaces too.
 */

/**
 * Whether a live mascot has something to show right now: any presence but plain idle (working,
 * the user typing to it, an approval, an error), or a pointer in the window its eyes can follow.
 */
internal fun mascotEngaged(presence: AgentPresence, pointerPresent: Boolean): Boolean =
    presence != AgentPresence.IDLE || pointerPresent

/**
 * How long a mascot keeps moving after it stops being engaged (or first appears, or changes
 * identity), so the director's last beat plays out - the success bloom is 2 s
 * (AvatarDirector.Config.successTotalSeconds), an error 1.6 s, a morph 0.24 s - before the scene
 * holds its pose.
 */
internal val MASCOT_REST_DELAY: Duration = 3.seconds

/**
 * True while [MascotLive]'s scene should advance: while [engaged], and for [MASCOT_REST_DELAY]
 * after it stops being engaged or [identity] changes. False afterwards: the surface keeps its
 * last frame and nothing asks for another.
 */
@Composable
internal fun rememberMascotMoving(engaged: Boolean, identity: MascotIdentity): Boolean {
    var moving by remember { mutableStateOf(true) }
    LaunchedEffect(engaged, identity) {
        moving = true
        if (!engaged) {
            delay(MASCOT_REST_DELAY)
            moving = false
        }
    }
    return moving
}
