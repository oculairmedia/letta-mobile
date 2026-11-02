package com.letta.mobile.data.beads.wire.mutations

import com.letta.mobile.data.beads.wire.core.IssueWithCounts
import kotlinx.serialization.Serializable

/** Wire representation of the bd serve ClaimNextResponse schema. */
@Serializable
data class ClaimNextResponse(
    val claimed: IssueWithCounts? = null,
)
