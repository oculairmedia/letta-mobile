package com.letta.mobile.data.beads.wire.payloads

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire representation of the bd serve ApplyBatchRequest schema. */
@Serializable
data class ApplyBatchRequest(
    val actor: String,
    val items: List<ApplyItem>,
    val provenance: String? = null,
    @SerialName("force_id_prefix")
    val forceIdPrefix: Boolean? = null,
    @SerialName("skip_per_edge_cycle_check")
    val skipPerEdgeCycleCheck: Boolean? = null,
)
