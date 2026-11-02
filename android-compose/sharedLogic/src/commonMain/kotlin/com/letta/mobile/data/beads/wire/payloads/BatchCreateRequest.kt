package com.letta.mobile.data.beads.wire.payloads

import kotlinx.serialization.Serializable

/** Wire representation of the bd serve BatchCreateRequest schema. */
@Serializable
data class BatchCreateRequest(
    val actor: String,
    val items: List<BatchCreateItem>,
)
