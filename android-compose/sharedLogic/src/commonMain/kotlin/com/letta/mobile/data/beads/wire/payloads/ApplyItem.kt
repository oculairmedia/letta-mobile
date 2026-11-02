package com.letta.mobile.data.beads.wire.payloads

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire representation of the bd serve ApplyItem schema. */
@Serializable
data class ApplyItem(
    val kind: String,
    val create: ApplyCreateItem? = null,
    val update: ApplyUpdateItem? = null,
    val close: ApplyCloseItem? = null,
    @SerialName("dep_add")
    val depAdd: ApplyDepAddItem? = null,
)
