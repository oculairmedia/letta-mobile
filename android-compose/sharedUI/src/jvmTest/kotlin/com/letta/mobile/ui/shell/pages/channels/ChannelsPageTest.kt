@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.pages.channels

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.channel.ChannelDisplayItem
import com.letta.mobile.data.channel.ChannelDisplayStatus
import com.letta.mobile.data.channel.ChannelLibraryState
import com.letta.mobile.data.channel.ChannelsPageActions
import com.letta.mobile.data.channel.ChannelsPageReducer
import com.letta.mobile.data.channel.ChannelsPageState
import kotlin.test.Test
import kotlin.test.assertEquals

/** The shared Channels page on a wide desktop window and a phone (letta-mobile-c3np7.3.6). */
class ChannelsPageTest {
    private val live = ChannelDisplayItem(
        id = "iroh",
        title = "Iroh backend",
        subtitle = "Connected via iroh",
        detailText = "Connected to server s1 session x9 using iroh. A2UI is enabled",
        metadataLabels = listOf("Connected", "iroh", "A2UI"),
        status = ChannelDisplayStatus.Connected,
    )
    private val redialing = ChannelDisplayItem(
        id = "ws",
        title = "WebSocket backend",
        subtitle = "Reconnecting",
        detailText = "Live channel transport lost its connection and is reconnecting. Anything shown may be stale.",
        metadataLabels = listOf("Reconnecting", "Code 1006", "Stale"),
        status = ChannelDisplayStatus.Reconnecting,
    )

    private fun stateOf(vararg channels: ChannelDisplayItem): ChannelsPageState =
        ChannelsPageReducer.withLibrary(ChannelsPageState(), ChannelLibraryState(channels.toList()))

    private fun ComposeUiTest.show(
        state: ChannelsPageState,
        actions: ChannelsPageActions,
        width: Dp,
        options: ChannelsPageOptions = ChannelsPageOptions(),
    ) {
        setContent {
            MaterialTheme {
                Box(Modifier.width(width).height(PAGE_HEIGHT)) {
                    ChannelsPage(state = state, actions = actions, options = options)
                }
            }
        }
    }

    @Test
    fun liveAndStaleChannelsRenderWithTheirStatus() = runComposeUiTest {
        show(stateOf(live, redialing), RecordingActions(), WIDE)
        onNodeWithTag(ChannelsPageTags.card("iroh")).assertExists()
        onNodeWithTag(ChannelsPageTags.card("ws")).assertExists()
        onNodeWithText("Code 1006").assertExists()
        onNodeWithText("Stale").assertExists()
        onNodeWithTag(ChannelsPageTags.filter("Reconnecting")).assertExists()
    }

    @Test
    fun anEmptyLibrarySaysSo() = runComposeUiTest {
        show(stateOf(), RecordingActions(), COMPACT)
        onNodeWithTag(ChannelsPageTags.EMPTY).assertExists()
        onNodeWithText("No live channels are available for the active backend.").assertExists()
    }

    @Test
    fun aFilterThatMatchesNothingSaysSo() = runComposeUiTest {
        val state = ChannelsPageReducer.withQuery(stateOf(live), "nothing like this")
        show(state, RecordingActions(), WIDE)
        onNodeWithText("No channels match your filter.").assertExists()
    }

    @Test
    fun headerControlsReachTheActions() = runComposeUiTest {
        val actions = RecordingActions()
        show(stateOf(live, redialing), actions, COMPACT)
        onNodeWithTag(ChannelsPageTags.REFRESH).performClick()
        onNodeWithTag(ChannelsPageTags.filter("Reconnecting")).performClick()
        onNodeWithTag(ChannelsPageTags.SEARCH).performTextInput("iroh")
        assertEquals(listOf("refresh", "filter:Reconnecting", "query:iroh"), actions.calls)
    }

    @Test
    fun theActiveFilterChipIsSelected() = runComposeUiTest {
        val state = ChannelsPageReducer.withStatusFilter(stateOf(live, redialing), ChannelDisplayStatus.Connected)
        show(state, RecordingActions(), WIDE)
        onNodeWithTag(ChannelsPageTags.filter("Connected")).assertIsSelected()
        onNodeWithTag(ChannelsPageTags.card("ws")).assertDoesNotExist()
    }

    @Test
    fun cardsOpenTheDetailOnlyWhenTheHostAsks() = runComposeUiTest {
        val actions = RecordingActions()
        show(stateOf(live), actions, WIDE)
        onNodeWithTag(ChannelsPageTags.card("iroh")).performClick()
        assertEquals(emptyList(), actions.calls)
    }

    @Test
    fun aTappedCardOpensItsDetail() = runComposeUiTest {
        val actions = RecordingActions()
        show(stateOf(live), actions, COMPACT, PhoneOptions)
        onNodeWithTag(ChannelsPageTags.card("iroh")).performClick()
        assertEquals(listOf("select:iroh"), actions.calls)
    }

    @Test
    fun theWideDetailShowsTransportAndRefreshes() = runComposeUiTest {
        val actions = RecordingActions()
        val state = ChannelsPageReducer.withSelection(stateOf(live, redialing), "ws")
        show(state, actions, WIDE, PhoneOptions)
        onNodeWithTag(ChannelsPageTags.DETAIL).assertExists()
        onNodeWithText("This channel is not live: anything it shows may be stale.").assertExists()
        onNodeWithTag(ChannelsPageTags.DETAIL_REFRESH).performClick()
        onNodeWithTag(ChannelsPageTags.DETAIL_CLOSE).performClick()
        assertEquals(listOf("refresh", "clear"), actions.calls)
    }

    private class RecordingActions : ChannelsPageActions {
        val calls = mutableListOf<String>()

        override fun refresh() {
            calls += "refresh"
        }

        override fun updateQuery(query: String) {
            calls += "query:$query"
        }

        override fun selectStatusFilter(status: ChannelDisplayStatus?) {
            calls += "filter:${status?.label}"
        }

        override fun selectChannel(channelId: String) {
            calls += "select:$channelId"
        }

        override fun clearSelection() {
            calls += "clear"
        }
    }

    private companion object {
        val WIDE = 1000.dp
        val COMPACT = 400.dp
        val PAGE_HEIGHT = 800.dp
        val PhoneOptions = ChannelsPageOptions(showTitle = false, touch = true, showDetails = true)
    }
}
