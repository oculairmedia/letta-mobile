package com.letta.mobile.data.beads.wire.payloads

import kotlinx.serialization.Serializable

/** Wire representation of the bd serve SetSettingRequest schema. */
@Serializable
data class SetSettingRequest(
    val value: String,
)
