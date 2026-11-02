package com.letta.mobile.data.beads.wire.mutations

import kotlinx.serialization.Serializable

/** Wire representation of the bd serve BatchCloseItem schema. */
@Serializable
data class BatchCloseItem(
    val id: String,
    val reason: String? = null,
)
