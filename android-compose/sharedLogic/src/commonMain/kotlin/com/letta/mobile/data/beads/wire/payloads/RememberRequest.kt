package com.letta.mobile.data.beads.wire.payloads

import kotlinx.serialization.Serializable

/** Wire representation of the bd serve RememberRequest schema. */
@Serializable
data class RememberRequest(
    val content: String,
    val key: String? = null,
)
