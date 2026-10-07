package com.letta.mobile.ui.shell.pages.channels

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import com.letta.mobile.data.channel.ChannelDisplayItem
import com.letta.mobile.data.channel.ChannelDisplayStatus
import com.letta.mobile.ui.components.LettaCatalogCard
import com.letta.mobile.ui.components.LettaChipTab
import com.letta.mobile.ui.components.LettaPill
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.customColors

/** The search field a host draws in the header; [modifier] carries the page's size and test tag. */
fun interface ChannelsSearchFieldSlot {
    @Composable
    fun Content(query: String, onQueryChange: (String) -> Unit, placeholder: String, modifier: Modifier)
}

/** The header's refresh action; [modifier] carries the page's test tag. */
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
)

private val DefaultChannelsSearchField = ChannelsSearchFieldSlot { query, onQueryChange, placeholder, modifier ->
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier,
    )
}

private val DefaultChannelsRefreshAction = ChannelsRefreshActionSlot { onRefresh, modifier ->
    IconButton(onClick = onRefresh, modifier = modifier) {
        Icon(Icons.Outlined.Refresh, contentDescription = REFRESH_LABEL)
    }
}

@Composable
internal fun ChannelsHeader(page: ChannelsPageScope) {
    val horizontal = if (page.wide) LettaDimens.Space.xxl else LettaDimens.Space.lg
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = horizontal, end = horizontal, top = LettaDimens.Space.lg, bottom = LettaDimens.Space.sm),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { if (page.options.showTitle) ChannelsTitle(page) }
            if (page.wide) {
                ChannelsSearch(page, Modifier.width(WideSearchWidth))
            }
            page.slots.refreshAction.Content(page.actions::refresh, Modifier.testTag(ChannelsPageTags.REFRESH))
        }
        if (!page.wide) ChannelsSearch(page, Modifier.fillMaxWidth())
        ChannelsFilterChips(page)
    }
}

@Composable
private fun ChannelsTitle(page: ChannelsPageScope) {
    Column(verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.hair)) {
        Text(PAGE_TITLE, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (!page.wide) {
            Text(page.state.library.summaryLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
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
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val filter = page.state.statusFilter
        LettaChipTab(ALL_CHANNELS, filter == null, { page.actions.selectStatusFilter(null) }, chipModifier.testTag(ChannelsPageTags.filter(ALL_CHANNELS)))
        page.state.projection.statuses.forEach { status ->
            LettaChipTab(status.label, filter == status, { page.actions.selectStatusFilter(status) }, chipModifier.testTag(ChannelsPageTags.filter(status.label)))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChannelCard(channel: ChannelDisplayItem, page: ChannelsPageScope, modifier: Modifier) {
    val accent = channel.status.accent()
    val pills: @Composable () -> Unit = { ChannelPills(channel, accent) }
    LettaCatalogCard(
        title = channel.title,
        description = channel.cardDescription(),
        accent = accent,
        onClick = { page.onCardClick(channel) },
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
            Text(channel.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButton(onClick = page.actions::clearSelection, modifier = Modifier.testTag(ChannelsPageTags.DETAIL_CLOSE)) {
                Icon(Icons.Outlined.Close, contentDescription = CLOSE_LABEL)
            }
        }
        PillFlow { ChannelPills(channel, accent) }
        DetailField(SUBTITLE_LABEL, channel.subtitle)
        DetailField(DETAIL_LABEL, channel.detailText)
        if (channel.status.isStale) {
            Text(STALE_NOTICE, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        OutlinedButton(onClick = page.actions::refresh, modifier = Modifier.testTag(ChannelsPageTags.DETAIL_REFRESH)) {
            Icon(Icons.Outlined.Refresh, contentDescription = null)
            Text(REFRESH_LABEL, modifier = Modifier.padding(start = LettaDimens.Space.sm))
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChannelDetailSheet(channel: ChannelDisplayItem, page: ChannelsPageScope) {
    ModalBottomSheet(onDismissRequest = page.actions::clearSelection) {
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
private val WideSearchWidth = LettaDimens.Pane.navWidth
