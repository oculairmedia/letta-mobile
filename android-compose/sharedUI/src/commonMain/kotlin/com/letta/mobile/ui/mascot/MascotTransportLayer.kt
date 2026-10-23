package com.letta.mobile.ui.mascot

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.letta.mobile.ui.theme.LocalReducedMotion

/** True below a [MascotTransportLayer] that draws: a layer nested in it draws no mascots. */
internal val LocalMascotLayerAbove = staticCompositionLocalOf { false }

/**
 * Draws, over [content], one live mascot per seated agent at its active seat, and springs it
 * between seats when the active seat changes - the whole transport animation, in one place. Mount
 * once at the window root, inside the mascot host and registry providers. [reducedMotion] (the
 * shell's [LocalReducedMotion] by default) snaps every hop and keeps only a short entrance fade.
 *
 * Inside another layer this one only lays out [content]: Android mounts a layer at the window root
 * around the shared workspace, which mounts its own for desktop and web, and two drawing layers
 * put two animated copies of every character on the same seat.
 */
@Composable
fun MascotTransportLayer(
    reducedMotion: Boolean = LocalReducedMotion.current,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (LocalMascotLayerAbove.current) {
        Box(modifier) { content() }
    } else {
        DrawingMascotLayer(reducedMotion, modifier, content)
    }
}

