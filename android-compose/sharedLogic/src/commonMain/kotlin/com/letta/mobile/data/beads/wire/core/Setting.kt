package com.letta.mobile.data.beads.wire.core

import kotlinx.serialization.Serializable

/** Wire representation of the bd serve Setting schema. */
@Serializable
data class Setting(
    val key: String,
    val value: String? = null,
    val redacted: Boolean,
)
