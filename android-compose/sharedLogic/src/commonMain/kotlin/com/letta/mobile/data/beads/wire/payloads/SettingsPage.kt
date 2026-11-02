package com.letta.mobile.data.beads.wire.payloads

import com.letta.mobile.data.beads.wire.core.Setting
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire representation of the bd serve SettingsPage schema. */
@Serializable
data class SettingsPage(
    val items: List<Setting>,
    @SerialName("has_more")
    val hasMore: Boolean,
    @SerialName("next_cursor")
    val nextCursor: String? = null,
)
