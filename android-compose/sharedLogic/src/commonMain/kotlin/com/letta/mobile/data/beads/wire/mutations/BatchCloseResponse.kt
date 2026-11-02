package com.letta.mobile.data.beads.wire.mutations

import kotlinx.serialization.Serializable

/** Wire representation of the bd serve BatchCloseResponse schema. */
@Serializable
data class BatchCloseResponse(
    val outcomes: List<CloseOutcome>,
)
