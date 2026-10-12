package com.letta.mobile.ui.host

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.letta.mobile.ui.theme.LettaDimens

/**
 * Shows [content] in the container the current [LettaHost] uses for [kind]. The pane is open while
 * it is composed: a caller dismisses it by removing it (`if (open) LettaPane(...)`), and [onDismiss]
 * is how the container asks for that (scrim tap, Escape, back, swipe down).
 *
 * Every container moves focus into the pane, closes on Escape, and enters without motion when
 * [com.letta.mobile.ui.theme.LocalReducedMotion] is set. An anchored pane (popover, menu) also asks
 * for [onDismiss] when the page that opened it stops ([DismissWhenOwnerStops]: on Android, the page
 * is navigated away from or the app goes to the background), so its window never outlives the page.
 */
@Composable
fun LettaPane(
    kind: LettaPaneKind,
    onDismiss: () -> Unit,
    title: String? = null,
    size: PaneSize = PaneSize.Default,
    testTag: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val host = LocalLettaHost.current
    val resolved = host.resolve(kind)
    val spec = PaneSpec(onDismiss, title, resolved.anchorOrDefault(), size, testTag, modifier)
    when (resolved) {
        LettaPaneKind.Sheet -> host.sheet(spec, content)
        is LettaPaneKind.Popover -> {
            DismissWhenOwnerStops(onDismiss)
            host.popover(spec, content)
        }
        LettaPaneKind.Dialog -> host.dialog(spec, content)
        LettaPaneKind.Alert -> host.alert(spec, content)
        is LettaPaneKind.Menu -> {
            DismissWhenOwnerStops(onDismiss)
            host.menu(true, spec, content)
        }
        // resolve() never returns an intent.
        is LettaPaneKind.Adaptive, LettaPaneKind.Modal -> host.dialog(spec, content)
    }
}

/**
 * A menu anchored to the composable it is declared inside, through the host's menu container.
 * Unlike [LettaPane] it takes [expanded], so the container can animate the menu out. While the
 * page that owns it is stopped there is no menu at all: it closes when the page stops, and a
 * stopped page's paused frame clock would hold the exit animation, and the menu's window, open.
 */
@Composable
fun LettaMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    anchor: PaneAnchor = PaneAnchor.BelowStart,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (expanded) DismissWhenOwnerStops(onDismiss)
    if (!ownerIsStarted()) return
    LocalLettaHost.current.menu(expanded, PaneSpec(onDismiss = onDismiss, anchor = anchor, modifier = modifier), content)
}

/** A destructive action's confirmation: the request's message, then Cancel and the confirm action. */
@Composable
fun LettaConfirmPane(request: LettaConfirmRequest, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    LettaPane(LettaPaneKind.Alert, onDismiss = onDismiss, title = request.title, testTag = LettaPaneTags.CONFIRM) {
        Text(
            request.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = LettaDimens.Space.xl),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(LettaDimens.Space.lg),
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm, Alignment.End),
        ) {
            TextButton(onClick = onDismiss) { Text(request.dismissLabel) }
            TextButton(onClick = onConfirm) { Text(request.confirmLabel) }
        }
    }
}

private fun LettaPaneKind.anchorOrDefault(): PaneAnchor = when (this) {
    is LettaPaneKind.Popover -> anchor
    is LettaPaneKind.Menu -> anchor
    is LettaPaneKind.Adaptive -> anchor
    else -> PaneAnchor.BelowStart
}

/** Tags the default containers put on their frames, and the confirmation's content. */
object LettaPaneTags {
    const val SHEET = "letta-pane-sheet"
    const val POPOVER = "letta-pane-popover"
    const val DIALOG = "letta-pane-dialog"
    const val ALERT = "letta-pane-alert"
    const val CONFIRM = "letta-pane-confirm"
}
