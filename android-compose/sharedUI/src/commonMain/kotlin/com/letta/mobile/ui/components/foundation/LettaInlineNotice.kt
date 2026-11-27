package com.letta.mobile.ui.components.foundation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.tokens.LettaPanelTone
import com.letta.mobile.ui.theme.tokens.LettaSpaceTokens
import androidx.compose.ui.draw.shadow
import com.letta.mobile.ui.theme.tokens.LettaComponentDimens
import com.letta.mobile.ui.theme.tokens.LettaShapes

/**
 * A button on a [LettaInlineNotice]: its words, what it does, its test tag, and whether it can be
 * pressed now. A dismiss with an [icon] is the shared icon button (a close mark) labelled [label]
 * instead of a text button.
 */
@Immutable
data class LettaNoticeAction(
    val label: String,
    val onClick: () -> Unit,
    val tag: String = "",
    val enabled: Boolean = true,
    val icon: ImageVector? = null,
)

/**
 * What a [LettaInlineNotice] says: [message] (under an optional [title]) in [tone]'s panel; [contentTag]
 * goes on the content row inside the panel, for tests. An [elevated] notice floats over the page (an
 * overlay banner) and casts the floating notice's shadow.
 */
@Immutable
data class LettaNotice(
    val message: String,
    val tone: LettaPanelTone = LettaPanelTone.Info,
    val title: String? = null,
    val contentTag: String = "",
    val elevated: Boolean = false,
)

/**
 * The one inline banner: a [LettaNotice] in a borderless [LettaPanel] of its tone, with an optional
 * [action] (an outlined button) and [dismiss] (a text button). With a title it leads with the tone's
 * glyph. Replaces the pages' private banner composables; the shared LettaInlineError is this at
 * [LettaPanelTone.Error] with a retry.
 *
 * Use it for a note that stays in the page's layout ("Read only while offline", "Sign in to sync"),
 * or, [LettaNotice.elevated], for the overlay host's banner over the page; a transient message is a
 * snackbar. [modifier] goes on the panel.
 */
@Composable
fun LettaInlineNotice(
    notice: LettaNotice,
    modifier: Modifier = Modifier,
    action: LettaNoticeAction? = null,
    dismiss: LettaNoticeAction? = null,
) {
    val tone = notice.tone
    val title = notice.title
    val shadow = if (notice.elevated) Modifier.shadow(LettaComponentDimens.floatingNoticeElevation, LettaShapes.panel) else Modifier
    LettaPanel(modifier = modifier.fillMaxWidth().then(shadow), tone = tone) {
        Row(
            modifier = Modifier
                .testTag(notice.contentTag)
                .padding(if (title == null) LettaSpaceTokens.md else LettaSpaceTokens.lg),
            horizontalArrangement = Arrangement.spacedBy(LettaSpaceTokens.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (title != null) LettaIcon(tone.glyph, contentDescription = null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LettaSpaceTokens.hair)) {
                title?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                Text(
                    text = notice.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = tone.contentColor().copy(alpha = if (title == null) 1f else MESSAGE_ALPHA),
                )
            }
            action?.let {
                OutlinedButton(onClick = it.onClick, enabled = it.enabled, modifier = Modifier.testTag(it.tag)) { Text(it.label) }
            }
            dismiss?.let { NoticeDismiss(it) }
        }
    }
}

@Composable
private fun NoticeDismiss(dismiss: LettaNoticeAction) {
    val icon = dismiss.icon
    if (icon != null) {
        LettaIconButton(
            LettaButtonIcon(icon, dismiss.label),
            onClick = dismiss.onClick,
            modifier = Modifier.testTag(dismiss.tag),
            enabled = dismiss.enabled,
        )
    } else {
        TextButton(onClick = dismiss.onClick, enabled = dismiss.enabled, modifier = Modifier.testTag(dismiss.tag)) { Text(dismiss.label) }
    }
}

private val LettaPanelTone.glyph: ImageVector
    get() = when (this) {
        LettaPanelTone.Error -> LettaIcons.Error
        LettaPanelTone.Warning -> LettaIcons.Warning
        LettaPanelTone.Neutral, LettaPanelTone.Info -> LettaIcons.Info
    }

/** A message under a title reads one step quieter than the title. */
private const val MESSAGE_ALPHA = 0.84f
