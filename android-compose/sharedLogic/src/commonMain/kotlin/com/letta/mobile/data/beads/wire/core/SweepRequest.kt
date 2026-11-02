package com.letta.mobile.data.beads.wire.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire representation of the bd serve SweepRequest schema. */
@Serializable
data class SweepRequest(
    val tier: String,
    val actor: String? = null,
    @SerialName("closed_before")
    val closedBefore: String? = null,
    val pattern: String? = null,
    @SerialName("protect_referenced")
    val protectReferenced: Boolean? = null,
    @SerialName("dry_run")
    val dryRun: Boolean? = null,
)
