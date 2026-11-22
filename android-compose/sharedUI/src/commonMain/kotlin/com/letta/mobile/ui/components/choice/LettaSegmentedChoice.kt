package com.letta.mobile.ui.components.choice

import androidx.compose.runtime.Immutable

/**
 * What a [LettaSegmentedControl] shows: its [segments] and which one is [selected] (by key).
 * [enabled] false disables every segment. [iconOnly] draws each segment's icon without its label
 * (the label is still what a screen reader says), so every segment then needs an icon. [showCheck]
 * false leaves a segment without an icon bare, for a row of short labels that must fit a phone; the
 * selected segment's fill says which is on. [tagPrefix] tags each segment `tagPrefix + key`.
 */
@Immutable
data class LettaSegmentedChoice(
    val segments: List<LettaSegment>,
    val selected: String,
    val enabled: Boolean = true,
    val iconOnly: Boolean = false,
    val showCheck: Boolean = true,
    val tagPrefix: String? = null,
)
