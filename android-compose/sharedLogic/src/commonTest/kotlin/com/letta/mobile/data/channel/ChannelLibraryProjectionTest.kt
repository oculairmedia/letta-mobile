package com.letta.mobile.data.channel

import kotlin.test.Test
import kotlin.test.assertEquals

class ChannelLibraryProjectionTest {
    @Test
    fun filtersAndGroupsInStatusOrder() {
        val channels = listOf(
            item("down", "Mail", ChannelDisplayStatus.Disconnected),
            item("up", "PM - letta-mobile", ChannelDisplayStatus.Connected),
        )

        val projection = projectChannelLibrary(channels, null, "pm letta mobile")

        assertEquals(listOf(ChannelDisplayStatus.Connected, ChannelDisplayStatus.Disconnected), projection.statuses)
        assertEquals(listOf("up"), projection.filteredChannels.map { it.id })
        assertEquals(listOf(ChannelDisplayStatus.Connected), projection.sections.map { it.status })
    }

    private fun item(id: String, title: String, status: ChannelDisplayStatus) = ChannelDisplayItem(
        id = id,
        title = title,
        subtitle = status.label,
        detailText = status.label,
        metadataLabels = emptyList(),
        status = status,
    )
}
