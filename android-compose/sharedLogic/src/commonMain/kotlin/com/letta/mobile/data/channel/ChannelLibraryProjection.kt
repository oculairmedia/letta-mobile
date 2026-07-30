package com.letta.mobile.data.channel

import com.letta.mobile.data.search.TextMatch

data class ChannelDisplaySection(
    val status: ChannelDisplayStatus,
    val channels: List<ChannelDisplayItem>,
)

data class ChannelLibraryProjection(
    val statuses: List<ChannelDisplayStatus>,
    val filteredChannels: List<ChannelDisplayItem>,
    val sections: List<ChannelDisplaySection>,
)

fun projectChannelLibrary(
    channels: List<ChannelDisplayItem>,
    statusFilter: ChannelDisplayStatus?,
    query: String,
): ChannelLibraryProjection {
    val statuses = ChannelDisplayStatus.entries.filter { status -> channels.any { it.status == status } }
    val filtered = channels.filter { channel ->
        (statusFilter == null || channel.status == statusFilter) &&
            TextMatch.matches(query, channel.title, channel.subtitle, channel.detailText)
    }
    val sections = statuses.mapNotNull { status ->
        filtered.filter { it.status == status }
            .takeIf(List<ChannelDisplayItem>::isNotEmpty)
            ?.let { ChannelDisplaySection(status, it) }
    }
    return ChannelLibraryProjection(statuses, filtered, sections)
}
