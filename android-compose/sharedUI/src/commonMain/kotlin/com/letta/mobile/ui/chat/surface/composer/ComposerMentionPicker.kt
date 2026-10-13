package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.letta.mobile.data.composer.MentionCatalog
import com.letta.mobile.data.composer.MentionKind
import com.letta.mobile.data.composer.Mentionable
import com.letta.mobile.ui.chat.ChatColumnMaxWidth
import com.letta.mobile.ui.components.picker.LettaPickerContent
import com.letta.mobile.ui.components.picker.LettaPickerSpec
import com.letta.mobile.ui.components.picker.PickMode
import com.letta.mobile.ui.components.picker.PickerCallbacks
import com.letta.mobile.ui.components.picker.PickerItem
import com.letta.mobile.ui.components.picker.PickerSection
import com.letta.mobile.ui.theme.ChatComposerDimens
import com.letta.mobile.ui.theme.LettaDimens

/** How many mentionables each section lists before the user narrows the `@` token. */
internal const val MENTIONS_PER_SECTION = 6

/**
 * The `@mention` suggestions (Penpot "Composer (@ mentions)") above the prompt: FILES / AGENTS /
 * MEMORY sections of mentionables, already filtered by the shared MentionCatalog, as the shared
 * [LettaPickerContent] (letta-mobile-ckmub.3.8) on a raised card. It is not a pane: the caret stays
 * in the composer while the user types the token, so the card has no search field of its own.
 */
@Composable
internal fun ComposerMentionPicker(
    groups: List<Pair<MentionKind, List<Mentionable>>>,
    onSelect: (Mentionable) -> Unit,
) {
    val sections = remember(groups) { mentionSections(groups) }
    Surface(
        modifier = Modifier.widthIn(max = ChatColumnMaxWidth).fillMaxWidth().heightIn(max = ChatComposerDimens.mentionPickerMaxHeight),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(LettaDimens.Stroke.hairline, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            LettaPickerContent(
                spec = LettaPickerSpec(sections = sections, mode = PickMode.Command, filterLocally = false, search = null),
                callbacks = PickerCallbacks(onQuery = {}, onPick = { onSelect(it.value) }),
            )
        }
    }
}

/** One picker section per mention kind, each capped at [MENTIONS_PER_SECTION] rows. */
internal fun mentionSections(groups: List<Pair<MentionKind, List<Mentionable>>>): List<PickerSection<Mentionable>> =
    groups.map { (kind, items) ->
        PickerSection(
            key = kind.name,
            title = MentionCatalog.sectionTitle(kind),
            items = items.take(MENTIONS_PER_SECTION).map { mention ->
                PickerItem(
                    key = "${kind.name}-${mention.id}",
                    value = mention,
                    title = mention.label,
                    trailing = mention.sublabel,
                    icon = kind.icon,
                )
            },
        )
    }

private val MentionKind.icon: ImageVector
    get() = when (this) {
        MentionKind.File -> Icons.Outlined.Description
        MentionKind.Agent -> Icons.Outlined.SmartToy
        MentionKind.Memory -> Icons.Outlined.Memory
    }
