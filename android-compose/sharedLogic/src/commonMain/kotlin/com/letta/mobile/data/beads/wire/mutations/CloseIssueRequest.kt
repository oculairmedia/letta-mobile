package com.letta.mobile.data.beads.wire.mutations

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire representation of the bd serve CloseIssueRequest schema. */
@Serializable
data class CloseIssueRequest(
    val actor: String,
    val reason: String? = null,
    val session: String? = null,
    val force: Boolean? = null,
    @SerialName("expected_version")
    val expectedVersion: String? = null,
)
