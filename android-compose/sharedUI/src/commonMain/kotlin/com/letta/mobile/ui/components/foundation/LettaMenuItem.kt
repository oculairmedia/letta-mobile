package com.letta.mobile.ui.components.foundation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import com.letta.mobile.ui.theme.tokens.LettaControlTokens
import com.letta.mobile.ui.theme.tokens.LettaSpaceTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * One row of a menu (LettaPopupMenu, or a menu with its own dividers and status lines built on
 * LettaMenu). [keepsMenuOpen] is for a row that adjusts something the person is watching (a zoom
 * step) and leaves the menu up; every other row closes it before its action runs. [tag] is the
 * row's test tag.
 */
@Immutable
data class LettaMenuItem(
    val label: String,
    val icon: ImageVector? = null,
    val enabled: Boolean = true,
    val keepsMenuOpen: Boolean = false,
    val tag: String = "",
    val onClick: () -> Unit,
)

/**
 * The one menu row: [item]'s words in bodyMedium on the menu's surface, its icon at the standard
 * glyph size in the muted ink, on the menu's one inset. A chosen row closes the menu through
 * [onDismiss] (unless it [LettaMenuItem.keepsMenuOpen]) and runs its action just after, on
 * [LocalMenuActionScope] when a host provides one.
 *
 * Both halves of that are necessary. The wait keeps screen-replacing work (opening the canvas,
 * navigating away) off the frame that tears the popup's own scene layer down. And the scope has to
 * outlive the popup, because callers dismiss by removing it - `if (menuOpen) { LettaPopupMenu(...) }` -
 * so an effect owned by the menu is cancelled before it can run, and every menu item in the app
 * silently does nothing.
 */
@Composable
internal fun LettaMenuEntry(item: LettaMenuItem, onDismiss: () -> Unit) {
    val actionScope = LocalMenuActionScope.current
    DropdownMenuItem(
        text = {
            Text(text = item.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        },
        leadingIcon = item.icon?.let { icon ->
            {
                LettaIcon(
                    icon,
                    contentDescription = null,
                    style = LettaIconStyle(LettaControlTokens.icon, MaterialTheme.colorScheme.onSurfaceVariant),
                )
            }
        },
        enabled = item.enabled,
        modifier = if (item.tag.isEmpty()) Modifier else Modifier.testTag(item.tag),
        contentPadding = PaddingValues(horizontal = LettaSpaceTokens.lg, vertical = LettaSpaceTokens.hair),
        onClick = {
            if (item.keepsMenuOpen) {
                item.onClick()
            } else {
                onDismiss()
                runAfterDismiss(actionScope, item.onClick)
            }
        },
    )
}

private fun runAfterDismiss(actionScope: CoroutineScope?, action: () -> Unit) {
    if (actionScope == null) {
        action()
        return
    }
    actionScope.launch {
        // A frame boundary, not a guess at one. A fixed delay says nothing about whether Compose has
        // applied the dismissal and torn the popup's scene layer down - and on a slow frame the
        // action would replace the screen before it had, which is the failure this deferral exists
        // to avoid.
        withFrameNanos { }
        action()
    }
}

/**
 * A scope that outlives a popup, for running what a menu item chose.
 *
 * The popup is usually removed from the composition by its own dismiss handler, so anything owned
 * by it - a `LaunchedEffect`, a `rememberCoroutineScope` - dies before the action can run. A host
 * provides a scope that lives as long as its window; without one the action runs immediately,
 * which is correct, just without the frame of daylight between dismissal and a screen-replacing
 * action.
 */
val LocalMenuActionScope = compositionLocalOf<CoroutineScope?> { null }
