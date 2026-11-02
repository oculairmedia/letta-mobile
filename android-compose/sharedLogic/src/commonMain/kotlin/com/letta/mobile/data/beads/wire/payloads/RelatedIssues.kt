package com.letta.mobile.data.beads.wire.payloads

import com.letta.mobile.data.beads.wire.core.IssueWithDependencyMetadata
import kotlinx.serialization.Serializable

/** Wire representation of the bd serve RelatedIssues schema. */
@Serializable
data class RelatedIssues(
    val items: List<IssueWithDependencyMetadata>,
)
