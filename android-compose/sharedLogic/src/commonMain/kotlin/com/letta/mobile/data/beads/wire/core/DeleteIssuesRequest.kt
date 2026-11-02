package com.letta.mobile.data.beads.wire.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire representation of the bd serve DeleteIssuesRequest schema. */
@Serializable
data class DeleteIssuesRequest(
    val ids: List<String>,
    val actor: String? = null,
    val cascade: Boolean? = null,
    val force: Boolean? = null,
    @SerialName("dry_run")
    val dryRun: Boolean? = null,
    @SerialName("expected_version")
    val expectedVersion: String? = null,
)
