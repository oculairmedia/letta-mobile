package com.letta.mobile.ui.shell.pages.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.home.FleetRecentConversation
import com.letta.mobile.data.home.HomeStatTile
import com.letta.mobile.data.home.relativeAgeLabel
import com.letta.mobile.data.home.statTiles
import com.letta.mobile.data.home.subtitle
import com.letta.mobile.ui.chat.AgentOrb
import com.letta.mobile.ui.home.FleetBarSpark
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens

@Composable
internal fun HomeHeader(page: HomePageScope) {
    Column(verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md)) {
        Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
                if (page.options.showTitle) {
                    Text(PAGE_TITLE, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        text = page.state.fleet.summary.subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (page.wide && page.options.showSearch) HomeSearchField(page, Modifier.width(WideSearchWidth))
            page.options.headerActions?.invoke(this)
        }
        if (!page.wide && page.options.showSearch) HomeSearchField(page, Modifier.fillMaxWidth())
    }
}

@Composable
private fun HomeSearchField(page: HomePageScope, modifier: Modifier) {
    val query = page.state.search.query
    OutlinedTextField(
        value = query,
        onValueChange = page.actions::updateSearchQuery,
        placeholder = { Text(SEARCH_PLACEHOLDER) },
        leadingIcon = { Icon(LettaIcons.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = page.actions::clearSearch) { Icon(LettaIcons.Close, contentDescription = CLEAR_SEARCH) }
            }
        },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.testTag(HomePageTags.SEARCH),
    )
}

/**
 * Home's chatbox. Deliberately not a second ComposerBar: it owns one draft and hands it to the host,
 * which routes it into the real chat pipeline. Attachments, mentions and slash commands stay the chat
 * page's job - this is the "just say the thing" entry point. Enter sends, Shift+Enter breaks a line.
 */
@Composable
internal fun HomeComposer(page: HomePageScope, modifier: Modifier = Modifier) {
    var draft by remember { mutableStateOf("") }
    val canSend = draft.isNotBlank()
    val submit = {
        if (draft.isNotBlank()) {
            page.navigation.onSubmitPrompt(draft.trim())
            draft = ""
        }
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(LettaDimens.Radius.lg),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.md),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
        ) {
            val field = ComposerField(draft, page.options.composerPlaceholder, { draft = it }, submit)
            HomeComposerField(field, Modifier.weight(1f))
            HomeSendButton(canSend = canSend, onSend = submit)
        }
    }
}

/** The composer field's draft, its placeholder and what typing and sending do. */
private class ComposerField(
    val text: String,
    val placeholder: String,
    val onTextChanged: (String) -> Unit,
    val onSubmit: () -> Unit,
)

@Composable
private fun HomeComposerField(field: ComposerField, modifier: Modifier = Modifier) {
    val text = field.text
    val onSubmit = field.onSubmit
    val textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
    BasicTextField(
        value = text,
        onValueChange = field.onTextChanged,
        modifier = modifier
            .heightIn(min = LettaDimens.Control.fieldHeight, max = ComposerMaxHeight)
            .padding(vertical = LettaDimens.Space.xs)
            .testTag(HomePageTags.COMPOSER)
            .onPreviewKeyEvent { event ->
                val isSend = event.type == KeyEventType.KeyDown &&
                    (event.key == Key.Enter || event.key == Key.NumPadEnter) &&
                    !event.isShiftPressed
                if (isSend) onSubmit()
                isSend
            },
        textStyle = textStyle,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        maxLines = COMPOSER_MAX_LINES,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { onSubmit() }),
        decorationBox = { innerTextField ->
            Box(Modifier.fillMaxWidth()) {
                if (text.isEmpty()) Text(field.placeholder, style = textStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                innerTextField()
            }
        },
    )
}

@Composable
private fun HomeSendButton(canSend: Boolean, onSend: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onSend,
        enabled = canSend,
        modifier = Modifier.size(LettaDimens.Control.iconButtonLg).testTag(HomePageTags.SEND),
        shape = CircleShape,
        color = if (canSend) colors.primary else colors.surfaceContainerHigh,
        contentColor = if (canSend) colors.onPrimary else colors.onSurfaceVariant.copy(alpha = LettaDimens.Alpha.disabled),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.ArrowUpward, contentDescription = SEND_LABEL, modifier = Modifier.size(LettaDimens.Control.iconButtonLg))
        }
    }
}

@Composable
internal fun HomeSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = MUTED_ALPHA),
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(top = LettaDimens.Space.md, bottom = LettaDimens.Space.hair),
    )
}

@Composable
internal fun HomeEmptyLine(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(vertical = LettaDimens.Space.lg),
    )
}

/**
 * Counters, four to a row on a wide window and two on a phone. Every tile in a row matches the
 * tallest so footers pin to a common bottom edge.
 */
@Composable
internal fun HomeStatTiles(page: HomePageScope) {
    val columns = if (page.wide) WIDE_STAT_COLUMNS else COMPACT_STAT_COLUMNS
    Column(Modifier.testTag(HomePageTags.STATS), verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md)) {
        page.state.statTiles().chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md)) {
                row.forEach { tile -> HomeStatTileCard(tile, Modifier.weight(1f).fillMaxHeight()) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun HomeStatTileCard(tile: HomeStatTile, modifier: Modifier) {
    // background + border on the same shape and no clip: clipping then bordering cuts the stroke to
    // sub-pixel at fractional Windows DPI.
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.large)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large)
            .padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.md),
    ) {
        Text(tile.label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(LettaDimens.Space.sm))
        Text(tile.value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.weight(1f))
        tile.series?.let { FleetBarSpark(it, Modifier.fillMaxWidth().height(LettaDimens.Space.xl)) }
        tile.caption?.let { caption ->
            Spacer(Modifier.height(LettaDimens.Space.xs))
            Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = MUTED_ALPHA))
        }
    }
}

internal fun LazyListScope.homeRecentConversations(page: HomePageScope) {
    item { HomeSectionLabel(RECENT_LABEL) }
    val recent = page.state.fleet.recent
    if (recent.isEmpty()) item { HomeEmptyLine(NO_CONVERSATIONS) }
    items(items = recent, key = { "recent-${it.conversationId}" }) { conversation ->
        RecentConversationRow(conversation, page)
    }
}

@Composable
private fun RecentConversationRow(conversation: FleetRecentConversation, page: HomePageScope) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(LettaDimens.Radius.md))
            .clickable { page.navigation.onOpenConversation(conversation) }
            .testTag(HomePageTags.recent(conversation))
            .padding(LettaDimens.Space.sm),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
    ) {
        AgentOrb(agentId = conversation.agentId, index = page.orbIndex(conversation.agentId), size = LettaDimens.Orb.sm, cornerRadius = LettaDimens.Radius.sm)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.hair)) {
            Text(conversation.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val secondary = if (page.wide) conversation.preview else listOf(conversation.agentName, conversation.preview).filter { it.isNotBlank() }.joinToString(" · ")
            if (secondary.isNotBlank()) {
                Text(secondary, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (page.wide) MutedCell(conversation.agentName, Modifier.width(RecentAgentWidth))
        MutedCell(relativeAgeLabel(conversation.updatedAtLabel), Modifier.width(LettaDimens.Orb.railSlotWidth), TextAlign.End)
    }
}

@Composable
internal fun MutedCell(text: String, modifier: Modifier, align: TextAlign = TextAlign.Start) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = align, modifier = modifier)
}

internal const val MUTED_ALPHA = 0.72f
private const val PAGE_TITLE = "Home"
private const val SEARCH_PLACEHOLDER = "Search agents, tools, blocks and messages"
private const val CLEAR_SEARCH = "Clear search"
private const val SEND_LABEL = "Send message"
private const val RECENT_LABEL = "Recent conversations"
private const val NO_CONVERSATIONS = "No conversations yet. Start one from the chatbox."
private const val COMPOSER_MAX_LINES = 5
private const val WIDE_STAT_COLUMNS = 4
private const val COMPACT_STAT_COLUMNS = 2
private val WideSearchWidth = 320.dp
private val ComposerMaxHeight = 120.dp
private val RecentAgentWidth = 120.dp
