package com.letta.mobile.data.beads.wire.payloads

import kotlinx.serialization.Serializable

/** Wire representation of the bd serve RemoveDependencyResponse schema. */
@Serializable
data class RemoveDependencyResponse(
    val removed: Boolean,
)
