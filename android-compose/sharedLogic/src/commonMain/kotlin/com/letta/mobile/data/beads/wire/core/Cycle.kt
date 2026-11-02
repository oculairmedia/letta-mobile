package com.letta.mobile.data.beads.wire.core

import kotlinx.serialization.Serializable

/** Wire representation of the bd serve Cycle schema. */
@Serializable
data class Cycle(
    val members: List<CycleMember>,
    val partial: Boolean,
)
