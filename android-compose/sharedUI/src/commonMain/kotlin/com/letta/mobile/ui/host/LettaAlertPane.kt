package com.letta.mobile.ui.host

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import com.letta.mobile.ui.theme.LettaDimens

/** One button of a pane's action row; a [destructive] one reads in the error colour. */
@Immutable
class LettaPaneButton(
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
    val testTag: String? = null,
)

/** A pane's action row: [extra] alone at the start (Download transcript), then [dismiss] and [confirm] at the end. */
@Immutable
class LettaPaneButtons(
    val confirm: LettaPaneButton? = null,
    val dismiss: LettaPaneButton? = null,
    val extra: LettaPaneButton? = null,
)

/**
 * What an alert-shaped pane shows around its body: the [title] (and the provider's [icon]), the
 * action row, and which container it asks for ([LettaPaneKind.Alert] for a short question,
 * [LettaPaneKind.Dialog] for a longer form or list).
 */
@Immutable
class LettaAlertSpec(
    val title: String,
    val buttons: LettaPaneButtons,
    val kind: LettaPaneKind = LettaPaneKind.Alert,
    val icon: ImageVector? = null,
    val testTag: String? = null,
)

/**
 *  (S3c): the one shape of a titled question or short form (rename, fork,
 * new folder, sign in, chat options) in the host's container. The body sits on the shared side
 * padding and takes the height left over the action row, which always stays in view.
 */
@Composable
fun LettaAlertPane(spec: LettaAlertSpec, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    LettaPane(spec.kind, onDismiss = onDismiss, title = spec.title, testTag = spec.testTag) {
        Column(
            modifier = Modifier.weight(1f, fill = false).fillMaxWidth().padding(horizontal = LettaDimens.Space.xl),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        ) {
            spec.icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(LettaDimens.Control.icon)) }
            content()
        }
        LettaPaneButtonRow(spec.buttons)
    }
}

/** The action row every alert-shaped pane ends with. */
@Composable
fun LettaPaneButtonRow(buttons: LettaPaneButtons) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(LettaDimens.Space.lg),
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        buttons.extra?.let { extra ->
            PaneButton(extra)
            Spacer(Modifier.weight(1f))
        }
        buttons.dismiss?.let { PaneButton(it) }
        buttons.confirm?.let { PaneButton(it) }
    }
}

@Composable
private fun PaneButton(button: LettaPaneButton) {
    TextButton(
        onClick = button.onClick,
        enabled = button.enabled,
        modifier = if (button.testTag != null) Modifier.testTag(button.testTag) else Modifier,
    ) {
        Text(button.label, color = if (button.destructive && button.enabled) MaterialTheme.colorScheme.error else Color.Unspecified)
    }
}

/** A destructive action's confirmation: the request's message, then Cancel and the confirm action. */
@Composable
fun LettaConfirmPane(request: LettaConfirmRequest, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val buttons = LettaPaneButtons(
        confirm = LettaPaneButton(request.confirmLabel, onConfirm, destructive = true, testTag = request.tags.confirm),
        dismiss = LettaPaneButton(request.dismissLabel, onDismiss, testTag = request.tags.dismiss),
    )
    LettaAlertPane(LettaAlertSpec(request.title, buttons, testTag = request.tags.pane), onDismiss) {
        Text(request.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
