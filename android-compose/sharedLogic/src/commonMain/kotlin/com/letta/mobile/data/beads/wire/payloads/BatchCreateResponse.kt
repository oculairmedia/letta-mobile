package com.letta.mobile.data.beads.wire.payloads

import com.letta.mobile.data.beads.wire.core.Issue
import kotlinx.serialization.Serializable

/** Wire representation of the bd serve BatchCreateResponse schema. */
@Serializable
data class BatchCreateResponse(
    val items: List<Issue>,
)
