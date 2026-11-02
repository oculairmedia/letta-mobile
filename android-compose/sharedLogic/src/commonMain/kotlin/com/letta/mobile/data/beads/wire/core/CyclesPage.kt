package com.letta.mobile.data.beads.wire.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire representation of the bd serve CyclesPage schema. */
@Serializable
data class CyclesPage(
    val items: List<Cycle>,
    @SerialName("has_more")
    val hasMore: Boolean,
)
