package com.letta.mobile.data.channel

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelsPageControllerTest {
    private val connected = item("up", "PM - letta-mobile", ChannelDisplayStatus.Connected)
    private val reconnecting = item("redial", "Mail", ChannelDisplayStatus.Reconnecting)

    @Test
    fun startFollowsTheLibrary() = runTest(UnconfinedTestDispatcher()) {
        val source = FakeChannelLibrarySource(ChannelLibraryState(listOf(connected)))
        val controller = ChannelsPageController(source, backgroundScope)
        controller.start()

        source.library.value = ChannelLibraryState(listOf(connected, reconnecting))

        assertTrue(source.started)
        assertEquals(listOf("up", "redial"), controller.state.value.projection.filteredChannels.map { it.id })
        assertTrue(controller.state.value.hasStaleChannels)
    }

    @Test
    fun queryAndStatusFilterNarrowTheProjection() = runTest(UnconfinedTestDispatcher()) {
        val controller = ChannelsPageController(FakeChannelLibrarySource(ChannelLibraryState(listOf(connected, reconnecting))), backgroundScope)

        controller.selectStatusFilter(ChannelDisplayStatus.Reconnecting)
        assertEquals(listOf("redial"), controller.state.value.projection.filteredChannels.map { it.id })

        controller.updateQuery("letta")
        assertEquals(ChannelsEmptyReason.NoMatches, controller.state.value.emptyReason)

        controller.selectStatusFilter(null)
        assertEquals(listOf("up"), controller.state.value.projection.filteredChannels.map { it.id })
        assertEquals(listOf(ChannelDisplayStatus.Connected, ChannelDisplayStatus.Reconnecting), controller.state.value.projection.statuses)
    }

    @Test
    fun anEmptyLibraryReportsNoChannels() {
        val state = ChannelsPageReducer.withLibrary(ChannelsPageState(), ChannelLibraryState())

        assertEquals(ChannelsEmptyReason.NoChannels, state.emptyReason)
        assertFalse(state.hasStaleChannels)
    }

    @Test
    fun selectionOpensKnownChannelsOnly() = runTest(UnconfinedTestDispatcher()) {
        val controller = ChannelsPageController(FakeChannelLibrarySource(ChannelLibraryState(listOf(connected))), backgroundScope)

        controller.selectChannel("missing")
        assertNull(controller.state.value.selectedChannel)

        controller.selectChannel("up")
        assertEquals(connected, controller.state.value.selectedChannel)

        controller.clearSelection()
        assertNull(controller.state.value.selectedChannelId)
    }

    @Test
    fun theOpenChannelFollowsLibraryUpdates() = runTest(UnconfinedTestDispatcher()) {
        val source = FakeChannelLibrarySource(ChannelLibraryState(listOf(connected)))
        val controller = ChannelsPageController(source, backgroundScope)
        controller.start()
        controller.selectChannel("up")

        val dropped = connected.copy(status = ChannelDisplayStatus.Disconnected)
        source.library.value = ChannelLibraryState(listOf(dropped))
        assertEquals(ChannelDisplayStatus.Disconnected, controller.state.value.selectedChannel?.status)

        source.library.value = ChannelLibraryState()
        assertNull(controller.state.value.selectedChannel)
    }

    @Test
    fun refreshAndCloseReachTheSource() = runTest(UnconfinedTestDispatcher()) {
        val source = FakeChannelLibrarySource(ChannelLibraryState())
        val controller = ChannelsPageController(source, backgroundScope)

        controller.refresh()
        controller.close()

        assertEquals(1, source.refreshes)
        assertTrue(source.closed)
    }

    private class FakeChannelLibrarySource(initial: ChannelLibraryState) : ChannelLibrarySource {
        val library = MutableStateFlow(initial)
        override val state: StateFlow<ChannelLibraryState> = library
        var started = false
        var refreshes = 0
        var closed = false

        override fun start() {
            started = true
        }

        override fun refresh() {
            refreshes++
        }

        override fun close() {
            closed = true
        }
    }

    private fun item(id: String, title: String, status: ChannelDisplayStatus) = ChannelDisplayItem(
        id = id,
        title = title,
        subtitle = status.label,
        detailText = status.label,
        metadataLabels = listOf(status.label),
        status = status,
    )
}
