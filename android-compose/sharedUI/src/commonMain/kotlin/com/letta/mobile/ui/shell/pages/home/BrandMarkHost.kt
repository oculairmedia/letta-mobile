package com.letta.mobile.ui.shell.pages.home

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.letta.mobile.data.home.greeting.GreetingMarkIntent
import com.letta.mobile.data.home.greeting.GreetingMarkState
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.shellBrandMark

/**
 * What draws the Baken mark in Home's greeting. The mark shows app and connection states only
 * ([GreetingMarkIntent]); a host with the animated mark (the BakenMark artboard and its BrandMark
 * state machine) provides its own through [LocalBrandMarkHost], every other host has the still
 * [PlaceholderBrandMark], which the greeting leaves out.
 */
interface BrandMarkHost {
    /** Whether this host draws the animated mark; while false the greeting leaves the mark out entirely. */
    val available: Boolean get() = true

    /** Draws the mark filling [modifier]'s bounds, playing [intent]'s state and one-shots. */
    @Composable
    fun Mark(intent: GreetingMarkIntent, modifier: Modifier)
}

/**
 * The stand-in until the animated mark ships. It reports itself unavailable, so the greeting leaves
 * the mark out; should a surface draw it anyway, it is the brand kit's flat mark in the theme's
 * colours ([shellBrandMark]), dimmed while connecting or loading. Never shapes of its own.
 */
object PlaceholderBrandMark : BrandMarkHost {
    override val available: Boolean = false

    @Composable
    override fun Mark(intent: GreetingMarkIntent, modifier: Modifier) {
        val settled = intent.state == GreetingMarkState.IDLE
        Image(shellBrandMark(), contentDescription = null, modifier = modifier, alpha = if (settled) 1f else LettaDimens.Alpha.muted)
    }
}

/** The host's mark; the placeholder unless the host provides the animated one. */
val LocalBrandMarkHost = staticCompositionLocalOf<BrandMarkHost> { PlaceholderBrandMark }
