package com.letta.mobile.data.beads.wire.mutations

import com.letta.mobile.data.beads.wire.core.Issue
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire representation of the bd serve ClaimResponse schema. */
@Serializable
data class ClaimResponse(
    val issue: Issue,
    @SerialName("already_claimed")
    val alreadyClaimed: Boolean,
)
