package com.letta.mobile.data.beads.wire.payloads

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire representation of the bd serve ApplyCloseItem schema. */
@Serializable
data class ApplyCloseItem(
    val target: Ref,
    val reason: String? = null,
    val session: String? = null,
    val force: Boolean? = null,
    @SerialName("expected_version")
    val expectedVersion: String? = null,
)
