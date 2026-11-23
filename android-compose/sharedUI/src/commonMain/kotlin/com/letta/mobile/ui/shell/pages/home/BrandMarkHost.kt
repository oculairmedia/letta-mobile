package com.letta.mobile.ui.shell.pages.home

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.letta.mobile.data.home.greeting.GreetingMarkIntent
import com.letta.mobile.data.home.greeting.GreetingMarkState
import com.letta.mobile.ui.components.foundation.color
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.tokens.LettaStatusTone

/**
 * What draws the Baken mark beside Home's greeting. The mark shows app and connection states only
 * ([GreetingMarkIntent]); a host with the animated mark (the BakenMark artboard and its BrandMark
 * state machine) provides its own through [LocalBrandMarkHost], every other host draws the static
 * [PlaceholderBrandMark], which draws nothing: a stand-in that reads as a glitch is worse than none.
 */
interface BrandMarkHost {
    /** Whether this host draws the real mark; while false the greeting leaves the mark out entirely. */
    val available: Boolean get() = true

    /** Draws the mark filling [modifier]'s bounds, playing [intent]'s state and one-shots. */
    @Composable
    fun Mark(intent: GreetingMarkIntent, modifier: Modifier)
}

/**
 * The stand-in until the animated mark ships. It reports itself unavailable, so the greeting leaves
 * the mark out; should a surface draw it anyway, it is an anchor with a pebble nested at its seam, in
 * the accent colour, dimmed while connecting or loading and amber while degraded.
 */
object PlaceholderBrandMark : BrandMarkHost {
    override val available: Boolean = false

    @Composable
    override fun Mark(intent: GreetingMarkIntent, modifier: Modifier) {
        val ink = markInk(intent.state)
        val pebble = MaterialTheme.colorScheme.tertiary
        Canvas(modifier) {
            val unit = size.minDimension
            val anchor = Size(unit * ANCHOR_WIDTH, unit * ANCHOR_HEIGHT)
            drawRoundRect(
                color = ink,
                topLeft = Offset((size.width - anchor.width) / 2f, size.height - anchor.height),
                size = anchor,
                cornerRadius = CornerRadius(anchor.width / 2f),
            )
            drawCircle(color = pebble, radius = unit * PEBBLE_RADIUS, center = Offset(size.width / 2f, unit * PEBBLE_CENTER_Y))
        }
    }

    @Composable
    private fun markInk(state: GreetingMarkState): Color = when (state) {
        GreetingMarkState.IDLE -> MaterialTheme.colorScheme.primary
        GreetingMarkState.LOADING, GreetingMarkState.CONNECTING -> MaterialTheme.colorScheme.primary.copy(alpha = LettaDimens.Alpha.muted)
        GreetingMarkState.DEGRADED -> LettaStatusTone.Warning.color()
    }

    // The placeholder's proportions, as shares of the mark's box.
    private const val ANCHOR_WIDTH = 0.56f
    private const val ANCHOR_HEIGHT = 0.62f
    private const val PEBBLE_RADIUS = 0.2f
    private const val PEBBLE_CENTER_Y = 0.24f
}

/** The host's mark; the placeholder unless the host provides the animated one. */
val LocalBrandMarkHost = staticCompositionLocalOf<BrandMarkHost> { PlaceholderBrandMark }
