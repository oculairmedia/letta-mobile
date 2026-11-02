package com.letta.mobile.data.beads.wire.payloads

import com.letta.mobile.data.beads.wire.core.Statistics
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire representation of the bd serve StatsResponse schema. */
@Serializable
data class StatsResponse(
    val summary: Statistics,
    @SerialName("blocked_count_skipped")
    val blockedCountSkipped: Boolean,
)
