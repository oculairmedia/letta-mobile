package com.letta.mobile.data.beads.wire.mutations

import kotlinx.serialization.Serializable

/** Wire representation of the bd serve BatchCloseRequest schema. */
@Serializable
data class BatchCloseRequest(
    val actor: String,
    val items: List<BatchCloseItem>,
    val session: String? = null,
    val force: Boolean? = null,
)
