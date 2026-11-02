package com.letta.mobile.data.beads.wire.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire representation of the bd serve SweepResult schema. */
@Serializable
data class SweepResult(
    @SerialName("dry_run")
    val dryRun: Boolean,
    val swept: Int,
    val dependencies: Int,
    val labels: Int,
    val events: Int,
    val skipped: SweepSkips,
    @SerialName("referenced_ids")
    val referencedIds: List<String>? = null,
)
