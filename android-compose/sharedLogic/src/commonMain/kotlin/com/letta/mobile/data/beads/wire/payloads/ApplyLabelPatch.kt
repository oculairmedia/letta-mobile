package com.letta.mobile.data.beads.wire.payloads

import kotlinx.serialization.Serializable

/** Wire representation of the bd serve ApplyLabelPatch schema. */
@Serializable
data class ApplyLabelPatch(
    val replace: List<String>? = null,
    val add: List<String>? = null,
    val remove: List<String>? = null,
)
