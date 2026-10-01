package com.letta.mobile.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * letta-mobile-bglj6.1: the few dimensions the shared chat timeline needs that [LettaDimens]
 * does not name. Everything else in `ui/chat/surface/timeline` reads [LettaDimens].
 *
 * Values are lifted from the two timelines this one replaces (desktop's message list and
 * Android's feature-chat list) so the shared page looks like what each platform shipped.
 */
object ChatTimelineDimens {
    /** Top fade ramp: taller than the bottom so it reads under the header (desktop list). */
    val topFadeLength: Dp = 72.dp

    /** Bottom fade ramp into the composer. */
    val bottomFadeLength: Dp = 44.dp

    /** Height a not-yet-loaded paged row reserves, so the list does not collapse while it loads. */
    val placeholderRowHeight: Dp = 48.dp

    /** Status / error glyph on the full-pane panels. */
    val statusIcon: Dp = 48.dp

    /** Loading skeleton bubble widths (Android MessageSkeleton). */
    val skeletonUserBubbleMaxWidth: Dp = 200.dp
    val skeletonAgentBubbleMaxWidth: Dp = 260.dp

    /** Widest the welcome/status panels' prose runs before wrapping. */
    val proseMaxWidth: Dp = 560.dp

    /** Alphas the timeline uses. Low ones are fills/decoration, never content colour. */
    object Alpha {
        /** Skeleton pulse range and the skeleton's placeholder text lines. */
        const val skeletonPulseLow: Float = 0.3f
        const val skeletonPulseHigh: Float = 0.6f
        const val skeletonLine: Float = 0.3f

        /** Text selection highlight behind selected message text. */
        const val selection: Float = 0.32f

        /** Muted labels (day divider word, clock). */
        const val mutedLabel: Float = 0.82f

        /** The goal card's near-opaque surface. */
        const val goalCard: Float = 0.94f
    }

    /** The scroll-to-latest glide's springback peaks at most this far past the newest edge. */
    val scrollToLatestMaxOvershoot: Dp = LettaDimens.Space.md

    /**
     * The Touch scroll-to-latest button (designsystem ScrollToBottomFab): its inset from the
     * list's bottom-end corner (feature-chat LettaSpacing.INNER_PADDING) and its lift.
     */
    val scrollToLatestTouchInset: Dp = LettaDimens.Space.lg
    val scrollToLatestTouchElevation: Dp = LettaDimens.Space.xs
    val scrollToLatestTouchPressedElevation: Dp = LettaDimens.Space.sm

    /** Fade-edge cross-fade, matching desktop's list. */
    const val fadeAnimationMillis: Int = 250

    /** Skeleton pulse period and per-row stagger (Android MessageSkeleton). */
    const val skeletonPulseMillis: Int = 1000
    const val skeletonStaggerMillis: Int = 150
}
