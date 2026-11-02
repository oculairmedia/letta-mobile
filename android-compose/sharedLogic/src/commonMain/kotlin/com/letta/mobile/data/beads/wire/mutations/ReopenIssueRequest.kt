package com.letta.mobile.data.beads.wire.mutations

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire representation of the bd serve ReopenIssueRequest schema. */
@Serializable
data class ReopenIssueRequest(
    val actor: String,
    val reason: String? = null,
    @SerialName("expected_version")
    val expectedVersion: String? = null,
)
