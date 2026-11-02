package com.letta.mobile.data.beads.wire.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire representation of the bd serve SweepSkips schema. */
@Serializable
data class SweepSkips(
    val pinned: Int,
    val referenced: Int,
    @SerialName("not_closed")
    val notClosed: Int,
    @SerialName("unknown_closed_at")
    val unknownClosedAt: Int,
    @SerialName("closed_at_or_after_cutoff")
    val closedAtOrAfterCutoff: Int,
    val unreadable: Int,
)
