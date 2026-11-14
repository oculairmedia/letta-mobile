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

/** A button on a [LettaInlineNotice]: its words, what it does, its test tag, and whether it can be pressed now. */
@Immutable
data class LettaNoticeAction(
    val label: String,
    val onClick: () -> Unit,
    val tag: String = "",
    val enabled: Boolean = true,
)

/**
 * The one inline banner: [message] (under an optional [title]) in a borderless [LettaPanel] of
 * [tone], with an optional [action] (an outlined button) and [dismiss] (a text button). With a
 * [title] it leads with the tone's glyph. Replaces the pages' private banner composables; the shared
 * LettaInlineError is this at [LettaPanelTone.Error] with a retry.
 *
 * Use it for a note that stays in the page's layout ("Read only while offline", "Sign in to sync"),
 * not for a transient message (that is a snackbar or an overlay). [contentTag] goes on the content
 * row inside the panel; [modifier] on the panel.
 */
@Composable
fun LettaInlineNotice(
    message: String,
    modifier: Modifier = Modifier,
    tone: LettaPanelTone = LettaPanelTone.Info,
    title: String? = null,
    action: LettaNoticeAction? = null,
    dismiss: LettaNoticeAction? = null,
    contentTag: String = "",
) {
    LettaPanel(modifier = modifier.fillMaxWidth(), tone = tone, border = false) {
        Row(
            modifier = Modifier
                .testTag(contentTag)
                .padding(if (title == null) LettaSpaceTokens.md else LettaSpaceTokens.lg),
            horizontalArrangement = Arrangement.spacedBy(LettaSpaceTokens.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (title != null) LettaIcon(tone.glyph, contentDescription = null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LettaSpaceTokens.hair)) {
                title?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = tone.contentColor().copy(alpha = if (title == null) 1f else MESSAGE_ALPHA),
                )
            }
            action?.let {
                OutlinedButton(onClick = it.onClick, enabled = it.enabled, modifier = Modifier.testTag(it.tag)) { Text(it.label) }
            }
            dismiss?.let {
                TextButton(onClick = it.onClick, enabled = it.enabled, modifier = Modifier.testTag(it.tag)) { Text(it.label) }
            }
        }
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
