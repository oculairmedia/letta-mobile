package com.letta.mobile.data.beads.wire.mutations

import kotlinx.serialization.Serializable

/** Wire representation of the bd serve ClaimNextRequest schema. */
@Serializable
data class ClaimNextRequest(
    val actor: String,
)
