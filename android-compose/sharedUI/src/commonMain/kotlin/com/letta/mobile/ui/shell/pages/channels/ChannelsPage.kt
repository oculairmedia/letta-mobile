package com.letta.mobile.ui.shell.pages.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.letta.mobile.data.channel.ChannelDisplayItem
import com.letta.mobile.data.channel.ChannelsPageActions
import com.letta.mobile.data.channel.ChannelsPageState
import com.letta.mobile.ui.components.LettaCatalogGridPadding
import com.letta.mobile.ui.components.LettaInfoBox
import com.letta.mobile.ui.components.lettaCardGrid
import com.letta.mobile.ui.theme.LettaDimens

/**
 * The Channels page shared by desktop and Android (letta-mobile-c3np7.3.6): every live channel the
 * active backend exposes, grouped by status, with a search and status filter, and - when
 * [ChannelsPageOptions.showDetails] - a channel's full transport detail on tap.
 *
 * All state lives in [ChannelsPageState] (sharedLogic's ChannelsPageController); hosts own the
 * navigation chrome and tune the rest through [options]. At or above
 * [LettaDimens.Pane.wideBreakpoint] the cards lay out as a two-column grid with the detail docked
 * beside it; below it they stack in one column and the detail rises as a bottom sheet.
 */
@Composable
fun ChannelsPage(
    state: ChannelsPageState,
    actions: ChannelsPageActions,
    modifier: Modifier = Modifier,
    options: ChannelsPageOptions = ChannelsPageOptions(),
) {
    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag(ChannelsPageTags.PAGE)) {
        val wide = maxWidth >= LettaDimens.Pane.wideBreakpoint
        val page = ChannelsPageScope(state, actions, options, wide)
        if (wide) WideChannelsLayout(page) else CompactChannelsLayout(page)
    }
}

/**
 * Host presentation choices: [showTitle] false when the host already titles the screen; [touch]
 * grows the filter chips and actions to touch-target size; [showDetails] makes a card open the
 * channel's detail; [slots] are the host's own header controls.
 */
@Immutable
data class ChannelsPageOptions(
    val showTitle: Boolean = true,
    val touch: Boolean = false,
    val showDetails: Boolean = false,
    val slots: ChannelsPageSlots = ChannelsPageSlots(),
)

/** Test tags for the shared Channels page. */
object ChannelsPageTags {
    const val PAGE = "channels_page"
    const val SEARCH = "channels_search"
    const val REFRESH = "channels_refresh"
    const val EMPTY = "channels_empty"
    const val LIST = "channels_list"
    const val DETAIL = "channels_detail"
    const val DETAIL_CLOSE = "channels_detail_close"
    const val DETAIL_REFRESH = "channels_detail_refresh"

    fun card(channelId: String): String = "channels_card_$channelId"

    fun filter(label: String): String = "channels_filter_$label"
}

internal class ChannelsPageScope(
    val state: ChannelsPageState,
    val actions: ChannelsPageActions,
    val options: ChannelsPageOptions,
    val wide: Boolean,
) {
    val slots: ChannelsPageSlots
        get() = options.slots

    val openChannel: ChannelDisplayItem?
        get() = state.selectedChannel.takeIf { options.showDetails }

    fun onCardClick(channel: ChannelDisplayItem) {
        if (options.showDetails) actions.selectChannel(channel.id)
    }
}

@Composable
private fun WideChannelsLayout(page: ChannelsPageScope) {
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            ChannelsHeader(page)
            ChannelsBody(page, columns = 2, contentPadding = LettaCatalogGridPadding)
        }
        page.openChannel?.let { channel ->
            VerticalDivider()
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.width(LettaDimens.Pane.sidePanelWidth).fillMaxHeight(),
            ) {
                ChannelDetail(channel, page)
            }
        }
    }
}

@Composable
private fun CompactChannelsLayout(page: ChannelsPageScope) {
    Column(Modifier.fillMaxSize()) {
        ChannelsHeader(page)
        ChannelsBody(page, columns = 1, contentPadding = CompactListPadding)
    }
    page.openChannel?.let { channel -> ChannelDetailSheet(channel, page) }
}

@Composable
private fun ChannelsBody(page: ChannelsPageScope, columns: Int, contentPadding: PaddingValues) {
    val empty = page.state.emptyReason
    if (empty != null) {
        LettaInfoBox(
            message = empty.message,
            modifier = Modifier.testTag(ChannelsPageTags.EMPTY),
            contentPadding = if (page.wide) WideInfoPadding else CompactInfoPadding,
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(ChannelsPageTags.LIST),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.lg),
        contentPadding = contentPadding,
    ) {
        lettaCardGrid(
            sections = page.state.projection.sections.map { it.status.label to it.channels },
            keyOf = { it.id },
            columns = columns,
        ) { channel, cardModifier ->
            ChannelCard(channel, page, cardModifier.fillMaxWidth())
        }
    }
}

private val CompactListPadding = PaddingValues(
    start = LettaDimens.Space.lg,
    end = LettaDimens.Space.lg,
    top = LettaDimens.Space.sm,
    bottom = LettaDimens.Space.xxl,
)
private val WideInfoPadding = PaddingValues(horizontal = LettaDimens.Space.xxl, vertical = LettaDimens.Space.md)
private val CompactInfoPadding = PaddingValues(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.md)
