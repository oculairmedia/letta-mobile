package com.letta.mobile.data.beads.wire.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire representation of the bd serve BondRef schema. */
@Serializable
data class BondRef(
    @SerialName("source_id")
    val sourceId: String,
    @SerialName("bond_type")
    val bondType: String,
    @SerialName("bond_point")
    val bondPoint: String? = null,
)
