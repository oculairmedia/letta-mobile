package com.letta.mobile.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * letta-mobile-bglj6.1: the few sizes the shared chat rows need that the [LettaDimens] scale
 * does not name. Each one bounds content the transcript must not let grow without limit.
 */
object ChatRowDimens {
    /** Cap for an expanded user prompt: tall enough to read, never a full viewport. */
    val promptExpandedMaxHeight: Dp = 340.dp

    /** Chat-anchored A2UI stays bounded; past this the surface scrolls internally. */
    val a2uiMaxHeight: Dp = 360.dp

    /** One attached image gets the room to be looked at. */
    val imageSingleHeight: Dp = 220.dp

    /** Each cell when several images share a row. */
    val imageGridHeight: Dp = 128.dp

    /** Pinned prompt thumbnails: small enough that the sticky card never blows the fold. */
    val thumbnailWidth: Dp = 56.dp
    val thumbnailHeight: Dp = 40.dp

    /** Expanded subagent prompt line cap. */
    const val dispatchPromptMaxLines: Int = 12

    /** Clamped user prompt line count. */
    const val promptCollapsedMaxLines: Int = 3

    /** Lines a plain tool output shows before it says how much is hidden. */
    const val toolOutputVisibleLines: Int = 40

    /** Diff rows rendered before the block stops (the copy action still carries everything). */
    const val diffVisibleLines: Int = 200

    /** Horizontal scroll step, in px, for the arrow keys inside a tool output block. */
    const val toolOutputScrollStepPx: Int = 64

    /** Images shown in a grid / thumbnail strip before the rest collapse to a count. */
    const val gridMaxImages: Int = 4
    const val stripMaxImages: Int = 6

    /** A phone prompt's images (letta-mobile-bglj6.1.9): three up at most, each cell this tall. */
    const val promptGridMaxImages: Int = 3
    val promptGridCellHeight: Dp = 120.dp

    /** A phone's tool row header: the legacy CollapsibleStatusRow's 48dp touch target (bglj6.1.23). */
    val toolHeaderMinHeight: Dp = 48.dp

    /** How long the copy affordance shows its "copied" check, in ms. */
    const val copiedFeedbackMillis: Long = 1200L
}

/**
 * Background tints for the chat rows. These colour a SURFACE (a diff row, an error inset),
 * never content, which is why they sit under the content-alpha floor.
 */
object ChatRowAlpha {
    /** Added / removed diff row wash. */
    const val diffRowTint: Float = 0.12f

    /** Failed tool output inset over errorContainer. */
    const val errorInset: Float = 0.32f

    /** Hover wash behind the assistant copy action. */
    const val hoverSurface: Float = 0.94f

    /** Side/bottom outline of the user prompt card. */
    const val promptEdge: Float = 0.8f

    /** Copy action at rest when the row is hovered but the control is not. */
    const val copyIdle: Float = 0.6f

    /** Full-screen image viewer scrim. */
    const val viewerScrim: Float = 0.88f

    // The legacy Android timeline's muted text (RunActivityDisclosure, MessageReasoning,
    // DeliveryTimeText), lifted so the shared rows match it.

    /** A run header's title while the run is working. */
    const val workingTitle: Float = 0.88f

    /** The reasoning preview beside "Thought". */
    const val reasoningPreview: Float = 0.8f

    /** "Thinking…" while reasoning streams. */
    const val reasoningActiveTitle: Float = 0.92f

    /** The "You" label on a prompt bubble. */
    const val userRoleLabel: Float = 0.7f

    /** The "Inter-agent" label on another agent's bubble. */
    const val interAgentLabel: Float = 0.85f

    /** The newest reply's delivery time, drawn at this layer alpha. */
    const val deliveryTime: Float = 0.5f

    /** The working orb's pulse: dim and bright ends, and its still value under reduced motion. */
    const val workingOrbDim: Float = 0.44f
    const val workingOrbBright: Float = 0.92f
    const val workingOrbResting: Float = 0.72f

    /** The faded tails of the thinking row's sweeping gradient. */
    const val thinkingTail: Float = 0.45f
}
