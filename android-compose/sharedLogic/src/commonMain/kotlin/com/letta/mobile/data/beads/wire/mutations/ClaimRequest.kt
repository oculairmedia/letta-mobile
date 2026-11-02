package com.letta.mobile.data.beads.wire.mutations

import kotlinx.serialization.Serializable

/** Wire representation of the bd serve ClaimRequest schema. */
@Serializable
data class ClaimRequest(
    val actor: String,
)
