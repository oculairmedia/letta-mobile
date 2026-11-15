package com.letta.mobile.ui.shell.pages.channels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import com.letta.mobile.ui.shell.tokens.ShellType
import com.letta.mobile.data.channel.ChannelDisplayItem
import com.letta.mobile.data.channel.ChannelDisplayStatus
import com.letta.mobile.ui.components.LettaCatalogCard
import com.letta.mobile.ui.components.foundation.LettaChipTab
import com.letta.mobile.ui.components.foundation.LettaChipTabRow
import com.letta.mobile.ui.components.LettaPageHeader
import com.letta.mobile.ui.components.foundation.LettaButtonIcon
import com.letta.mobile.ui.components.foundation.LettaIconButton
import com.letta.mobile.ui.components.foundation.LettaSearchField
import com.letta.mobile.ui.components.foundation.LettaSearchOptions
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.shell.tokens.ShellDimens
import com.letta.mobile.ui.components.foundation.LettaPill
import com.letta.mobile.ui.components.LettaRefreshAction
import com.letta.mobile.ui.components.LettaRefreshIconButton
import com.letta.mobile.ui.components.list.RosterRowState
import com.letta.mobile.ui.host.LettaPane
import com.letta.mobile.ui.host.LettaPaneKind
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.customColors

/** The search field a host draws in the header; [modifier] carries the page's size and test tag. */
fun interface ChannelsSearchFieldSlot {
    @Composable
    fun Content(query: String, onQueryChange: (String) -> Unit, placeholder: String, modifier: Modifier)
}

/** The header's refresh action on a pointer host (touch pulls instead); [modifier] carries the page's test tag. */
fun interface ChannelsRefreshActionSlot {
    @Composable
    fun Content(onRefresh: () -> Unit, modifier: Modifier)
}

/**
 * Platform pieces of the Channels header. The defaults are plain Material 3 controls; desktop
 * passes its compact Jewel field and icon button so the page matches its other catalogs.
 */
@Immutable
data class ChannelsPageSlots(
    val searchField: ChannelsSearchFieldSlot = DefaultChannelsSearchField,
    val refreshAction: ChannelsRefreshActionSlot = DefaultChannelsRefreshAction,
) {
    companion object {
        /** The plain Material 3 field and refresh: the touch host's own controls (Android). */
        val Material = ChannelsPageSlots()
    }
}

private val DefaultChannelsSearchField = ChannelsSearchFieldSlot { query, onQueryChange, placeholder, modifier ->
    LettaSearchField(query, onQueryChange, modifier, LettaSearchOptions(placeholder = placeholder))
}

private val DefaultChannelsRefreshAction = ChannelsRefreshActionSlot { onRefresh, modifier ->
    LettaRefreshIconButton(onRefresh, modifier)
}

@Composable
internal fun ChannelsHeader(page: ChannelsPageScope) {
    LettaPageHeader(
        title = PAGE_TITLE.takeIf { page.options.showTitle },
        caption = page.state.library.summaryLabel.takeIf { page.options.showTitle && !page.wide },
        // The header puts the search beside the title on a wide page and on a row of its own on a phone.
        context = { ChannelsSearch(page, if (page.wide) Modifier.width(ShellDimens.pageSearchWidth) else Modifier.fillMaxWidth()) },
        refresh = {
            // Pointer only: touch pulls the list (ChannelsPage's LettaRefreshable).
            LettaRefreshAction(refreshing = false, onRefresh = page.actions::refresh, modifier = Modifier.testTag(ChannelsPageTags.REFRESH)) { onRefresh, modifier ->
                page.slots.refreshAction.Content(onRefresh, modifier)
            }
        },
        controls = { ChannelsFilterChips(page) },
    )
}

@Composable
private fun ChannelsSearch(page: ChannelsPageScope, modifier: Modifier) {
    page.slots.searchField.Content(
        query = page.state.query,
        onQueryChange = page.actions::updateQuery,
        placeholder = SEARCH_PLACEHOLDER,
        modifier = modifier.testTag(ChannelsPageTags.SEARCH),
    )
}

@Composable
private fun ChannelsFilterChips(page: ChannelsPageScope) {
    val chipModifier = if (page.options.touch) Modifier.minimumInteractiveComponentSize() else Modifier
    LettaChipTabRow(Modifier.fillMaxWidth()) {
        val filter = page.state.statusFilter
        LettaChipTab(ALL_CHANNELS, filter == null, { page.actions.selectStatusFilter(null) }, chipModifier.testTag(ChannelsPageTags.filter(ALL_CHANNELS)))
        page.state.projection.statuses.forEach { status ->
            LettaChipTab(status.label, filter == status, { page.actions.selectStatusFilter(status) }, chipModifier.testTag(ChannelsPageTags.filter(status.label)))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChannelCard(channel: ChannelDisplayItem, page: ChannelsPageScope, row: RosterRowState, modifier: Modifier) {
    val accent = channel.status.accent()
    val pills: @Composable () -> Unit = { ChannelPills(channel, accent) }
    LettaCatalogCard(
        title = channel.title,
        description = channel.cardDescription(),
        accent = accent,
        onClick = row.onClick,
        modifier = modifier.testTag(ChannelsPageTags.card(channel.id)),
        // A phone card is too narrow for the pills beside the text, so they wrap under it.
        footer = { if (!page.wide) PillFlow { pills() } },
        trailing = { if (page.wide) Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm), verticalAlignment = Alignment.CenterVertically) { pills() } },
    )
}

/** The status pill plus the diagnostic metadata (Code 4401, Auth, transport, device...) it carries. */
@Composable
private fun ChannelPills(channel: ChannelDisplayItem, accent: Color) {
    LettaPill(channel.status.label, accent)
    channel.extraLabels().forEach { label -> LettaPill(label, MaterialTheme.colorScheme.onSurfaceVariant) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PillFlow(content: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier.padding(top = LettaDimens.Space.xs),
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
    ) { content() }
}

/** A channel's full transport detail: the docked panel on wide windows, the sheet's body on phones. */
@Composable
internal fun ChannelDetail(channel: ChannelDisplayItem, page: ChannelsPageScope) {
    val accent = channel.status.accent()
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(LettaDimens.Space.lg).testTag(ChannelsPageTags.DETAIL),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(channel.title, style = ShellType.detailTitle, modifier = Modifier.weight(1f))
            LettaIconButton(
                icon = LettaButtonIcon(LettaIcons.Close, CLOSE_LABEL),
                onClick = page.actions::clearSelection,
                modifier = Modifier.testTag(ChannelsPageTags.DETAIL_CLOSE),
            )
        }
        PillFlow { ChannelPills(channel, accent) }
        DetailField(SUBTITLE_LABEL, channel.subtitle)
        DetailField(DETAIL_LABEL, channel.detailText)
        if (channel.status.isStale) {
            Text(STALE_NOTICE, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        // Pointer only: on touch the user pulls the list behind the sheet.
        LettaRefreshAction(refreshing = false, onRefresh = page.actions::refresh, modifier = Modifier.testTag(ChannelsPageTags.DETAIL_REFRESH)) { onRefresh, modifier ->
            OutlinedButton(onClick = onRefresh, modifier = modifier) { Text(REFRESH_LABEL) }
        }
    }
}

@Composable
private fun DetailField(label: String, value: String) {
    if (value.isBlank()) return
    Column(verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(value, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
internal fun ChannelDetailSheet(channel: ChannelDisplayItem, page: ChannelsPageScope) {
    LettaPane(LettaPaneKind.Modal, onDismiss = page.actions::clearSelection) {
        ChannelDetail(channel, page)
    }
}

/**
 * The card's description: the status pill already names the status, so the detail (or subtitle)
 * shows only when it adds something.
 */
internal fun ChannelDisplayItem.cardDescription(): String? =
    detailText.takeIf { it.isNotBlank() && !it.equals(status.label, ignoreCase = true) }
        ?: subtitle.takeIf { it.isNotBlank() && !it.equals(status.label, ignoreCase = true) }

/** Metadata labels other than the status the pill already shows. */
internal fun ChannelDisplayItem.extraLabels(): List<String> =
    metadataLabels.filterNot { it.equals(status.label, ignoreCase = true) }

@Composable
internal fun ChannelDisplayStatus.accent(): Color = when (this) {
    ChannelDisplayStatus.Connected -> MaterialTheme.customColors.successColor
    ChannelDisplayStatus.Connecting, ChannelDisplayStatus.Reconnecting -> MaterialTheme.customColors.runningColor
    ChannelDisplayStatus.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
    ChannelDisplayStatus.Disconnected -> MaterialTheme.colorScheme.error
}

private const val PAGE_TITLE = "Channels"
private const val SEARCH_PLACEHOLDER = "Search channels"
private const val ALL_CHANNELS = "All channels"
private const val REFRESH_LABEL = "Refresh"
private const val CLOSE_LABEL = "Close"
private const val SUBTITLE_LABEL = "Status"
private const val DETAIL_LABEL = "Transport"
private const val STALE_NOTICE = "This channel is not live: anything it shows may be stale."
