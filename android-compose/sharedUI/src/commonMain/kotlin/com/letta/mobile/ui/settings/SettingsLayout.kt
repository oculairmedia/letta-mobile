package com.letta.mobile.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import com.letta.mobile.data.settings.SettingsGroup
import com.letta.mobile.ui.components.LettaChipTab
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens

/**
 * The Settings page's three form factors. [Phone]: one scrolling list with
 * the search under the title. [Medium]: the same list under a sticky row of section chips. [Wide]: a
 * section rail (search on top, scroll-spy selection) beside a content column capped at a readable width.
 */
enum class SettingsLayout(
    /** The content column's side gutter. */
    val gutter: Dp,
    /** The content column's widest; a phone uses its whole width. */
    val columnMaxWidth: Dp,
) {
    Phone(LettaDimens.Space.lg, Dp.Unspecified),
    Medium(LettaDimens.Space.xl, LettaDimens.Pane.settingsColumnMaxWidth),
    Wide(LettaDimens.Space.xl, LettaDimens.Pane.settingsColumnMaxWidth),
    ;

    companion object {
        fun of(width: Dp): SettingsLayout = when {
            width >= LettaDimens.Pane.settingsWideBreakpoint -> Wide
            width >= LettaDimens.Pane.settingsMediumBreakpoint -> Medium
            else -> Phone
        }
    }
}

/**
 * The group the reader is in: the last one whose top has scrolled within [slop] of the top, or the
 * last group once the page is scrolled to its end (a short last group never reaches the top).
 * [anchors] are each group's top in the scrolling column, in page order.
 */
fun settingsScrollSpy(anchors: List<Pair<SettingsGroup, Int>>, scroll: Int, atEnd: Boolean, slop: Int): SettingsGroup? {
    if (anchors.isEmpty()) return null
    if (atEnd && scroll > 0) return anchors.last().first
    return anchors.lastOrNull { (_, top) -> top <= scroll + slop }?.first ?: anchors.first().first
}

/** A group's glyph in the section rail. */
internal val SettingsGroup.icon: ImageVector
    get() = when (this) {
        SettingsGroup.Backend -> LettaIcons.Server
        SettingsGroup.Chat -> LettaIcons.Chat
        SettingsGroup.Appearance -> LettaIcons.AutoAwesome
        SettingsGroup.Notifications -> LettaIcons.Lightbulb
        SettingsGroup.Providers -> LettaIcons.Key
        SettingsGroup.About -> LettaIcons.Info
        SettingsGroup.Developer -> LettaIcons.Code
        SettingsGroup.Danger -> LettaIcons.Warning
    }

/** The search field: under the title on a phone, on top of the rail on a wide window. */
@Composable
internal fun SettingsSearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = { Text(SettingsCopy.SEARCH) },
        leadingIcon = { Icon(LettaIcons.Search, contentDescription = null, modifier = Modifier.size(LettaDimens.Control.icon)) },
        shape = MaterialTheme.shapes.extraLarge,
        modifier = modifier.fillMaxWidth().testTag(SettingsPageTags.SEARCH),
    )
}

/** The section rail of a wide window: one tab per group, the one in view selected. */
@Composable
internal fun SettingsRail(groups: List<SettingsGroup>, active: SettingsGroup?, onOpen: (SettingsGroup) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.selectableGroup().testTag(SettingsPageTags.RAIL), verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.hair)) {
        groups.forEach { group ->
            val selected = group == active
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = LettaDimens.Control.minTouchTarget)
                    .clip(MaterialTheme.shapes.medium)
                    .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.background)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onOpen(group) })
                    .testTag(SettingsPageTags.RAIL_PREFIX + group.name)
                    .padding(horizontal = LettaDimens.Space.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
            ) {
                val color = when {
                    group == SettingsGroup.Danger -> MaterialTheme.colorScheme.error
                    selected -> MaterialTheme.colorScheme.onSecondaryContainer
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Icon(group.icon, contentDescription = null, tint = color, modifier = Modifier.size(LettaDimens.Control.icon))
                Text(
                    group.title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = color,
                )
            }
        }
    }
}

/** The sticky chip row of a medium window: one chip per group, the one in view raised. */
@Composable
internal fun SettingsChips(groups: List<SettingsGroup>, active: SettingsGroup?, onOpen: (SettingsGroup) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup().testTag(SettingsPageTags.CHIPS),
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        groups.forEach { group ->
            LettaChipTab(
                text = group.title,
                active = group == active,
                onClick = { onOpen(group) },
                modifier = Modifier.testTag(SettingsPageTags.CHIP_PREFIX + group.name),
            )
        }
    }
}

/** A group's small header over its cards; the danger zone's reads red. */
@Composable
internal fun SettingsGroupHeader(group: SettingsGroup) {
    Text(
        group.title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = if (group == SettingsGroup.Danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = LettaDimens.Space.xs, top = LettaDimens.Space.sm),
    )
}

/** A group not drawn yet (the first frame draws only Backend): its header and two placeholder rows. */
@Composable
internal fun SettingsSkeletonGroup(group: SettingsGroup) {
    Column(Modifier.fillMaxWidth().testTag(SettingsPageTags.SKELETON_PREFIX + group.name), verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
        SettingsGroupHeader(group)
        SettingsSection(SettingsHeader("skeleton-" + group.name, group.title, showTitle = false)) {
            SettingsSkeletonRow()
            SettingsSkeletonRow()
        }
    }
}
