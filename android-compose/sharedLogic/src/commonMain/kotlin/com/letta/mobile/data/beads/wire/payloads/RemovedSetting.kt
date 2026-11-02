package com.letta.mobile.data.beads.wire.payloads

import kotlinx.serialization.Serializable

/** Wire representation of the bd serve RemovedSetting schema. */
@Serializable
data class RemovedSetting(
    val key: String,
)
