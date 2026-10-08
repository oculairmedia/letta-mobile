package com.letta.mobile.ui.shell.pages.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.data.home.HomeSearchState
import com.letta.mobile.ui.components.DisclosureChevron
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens

/**
 * Home's global search results, one collapsible section per kind: agents, tools and blocks match
 * locally at once; messages arrive from the backend search a moment later.
 */
internal fun LazyListScope.homeSearchResults(page: HomePageScope) {
    val search = page.state.search
    val sections = searchSections(search, page)
    item(key = "search-anchor") { Spacer(Modifier.testTag(HomePageTags.SEARCH_RESULTS)) }
    sections.forEach { section -> searchSection(section) }
    if (search.searchingMessages && search.messages.isEmpty()) item(key = "search-pending") { PendingMessages() }
    if (!search.searchingMessages && search.isEmpty) {
        item(key = "search-empty") { HomeEmptyLine(NO_RESULTS, Modifier.testTag(HomePageTags.SEARCH_EMPTY)) }
    }
}

@Immutable
private class SearchHit(val id: String, val primary: String, val secondary: String?, val onClick: () -> Unit)

@Immutable
private class SearchSection(val key: String, val title: String, val icon: ImageVector, val hits: List<SearchHit>, val maxSecondaryLines: Int = 1)

private fun searchSections(search: HomeSearchState, page: HomePageScope): List<SearchSection> {
    val nav = page.navigation
    return listOf(
        SearchSection("agents", "Agents", LettaIcons.Agent, search.agents.map { SearchHit(it.id.value, it.name, it.description) { nav.onOpenAgent(it.id.value) } }),
        SearchSection("tools", "Tools", LettaIcons.Tool, search.tools.map { SearchHit(it.id.value, it.name, it.description) { nav.onOpenTool(it.id.value) } }),
        SearchSection("blocks", "Blocks", LettaIcons.ViewModule, search.blocks.map { SearchHit(it.id.value, it.label ?: UNNAMED_BLOCK, it.description) { nav.onOpenBlock(it.id.value) } }),
        SearchSection(
            key = "messages",
            title = MESSAGES_LABEL,
            icon = LettaIcons.Chat,
            hits = search.messages.mapIndexed { index, message ->
                val role = message.role?.replaceFirstChar { it.uppercase() } ?: MESSAGE_FALLBACK
                SearchHit(message.messageId ?: "message-$index", role, message.content.orEmpty()) { nav.onOpenMessage(message) }
            },
            maxSecondaryLines = MESSAGE_LINES,
        ),
    ).filter { it.hits.isNotEmpty() }
}

private fun LazyListScope.searchSection(section: SearchSection) {
    item(key = "${section.key}-header") {
        // Collapsed state survives the query changing, like the Android dashboard's sections.
        var expanded by rememberSaveable(section.key) { mutableStateOf(true) }
        SectionHeader(section, expanded) { expanded = !expanded }
        if (expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
                section.hits.forEach { hit -> SearchHitRow(hit, section) }
            }
        }
    }
}

@Composable
private fun SectionHeader(section: SearchSection, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(onClick = onToggle).padding(vertical = LettaDimens.Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(section.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(LettaDimens.Space.sm))
        Text("(${section.hits.size})", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        DisclosureChevron(expanded = expanded, contentDescription = if (expanded) "Collapse" else "Expand")
    }
}

@Composable
private fun SearchHitRow(hit: SearchHit, section: SearchSection) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = hit.onClick)
            .testTag(HomePageTags.searchHit(hit.id))
            .padding(LettaDimens.Space.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
    ) {
        Icon(section.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(LettaDimens.Control.iconButtonSm))
        Column(Modifier.weight(1f)) {
            Text(hit.primary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            hit.secondary?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = section.maxSecondaryLines, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun PendingMessages() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = LettaDimens.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        Text(MESSAGES_LABEL, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        CircularProgressIndicator(modifier = Modifier.size(LettaDimens.Control.icon), strokeWidth = LettaDimens.Space.hair)
    }
}

private const val MESSAGES_LABEL = "Messages"
private const val MESSAGE_FALLBACK = "Message"
private const val UNNAMED_BLOCK = "Unnamed"
private const val NO_RESULTS = "No results found"
private const val MESSAGE_LINES = 3
