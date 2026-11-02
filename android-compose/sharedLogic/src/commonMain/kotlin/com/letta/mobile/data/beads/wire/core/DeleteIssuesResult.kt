package com.letta.mobile.data.beads.wire.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire representation of the bd serve DeleteIssuesResult schema. */
@Serializable
data class DeleteIssuesResult(
    @SerialName("dry_run")
    val dryRun: Boolean,
    val deleted: Int,
    val dependencies: Int,
    val labels: Int,
    val events: Int,
    @SerialName("references_updated")
    val referencesUpdated: Int,
    val orphaned: List<String>? = null,
)
