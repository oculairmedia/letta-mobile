package com.letta.mobile.ui.shell.header

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.host.LettaInput
import com.letta.mobile.ui.host.LocalLettaHost
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.search.LettaSearchAnchoredField
import com.letta.mobile.ui.theme.LettaDimens

/** What the header names: a title (and the agent or context under it), a host's own block, or nothing. */
sealed interface HeaderTitle {
    /** [onSubtitleClick] makes the subtitle a control (Android Home's backend label opens the switcher). */
    data class Text(val title: String, val subtitle: String? = null, val onSubtitleClick: (() -> Unit)? = null) : HeaderTitle

    /**
     * A host-drawn title (desktop's agent identity block or its conversation tab strip). With
     * [fill] it takes the header's free width, which a tab strip needs; otherwise it sizes to itself.
     */
    class Custom(val fill: Boolean = false, val content: @Composable () -> Unit) : HeaderTitle

    data object None : HeaderTitle
}

/** The leading affordance: back on a pushed page, the drawer on a root page, or a host's own control. */
sealed interface HeaderNav {
    data object None : HeaderNav

    data class Back(val onClick: () -> Unit, val contentDescription: String = "Back") : HeaderNav

    data class Menu(val onClick: () -> Unit, val contentDescription: String = "Menu") : HeaderNav

    /** Desktop's sidebar toggle and its collapsed-sidebar overflow. */
    class Custom(val content: @Composable RowScope.() -> Unit) : HeaderNav
}

/** A trailing header control. */
sealed interface HeaderAction {
    data class Icon(val icon: ImageVector, val contentDescription: String, val onClick: () -> Unit) : HeaderAction

    class Custom(val content: @Composable RowScope.() -> Unit) : HeaderAction
}

/** The header's controls around the title: the leading [nav] affordance and the trailing [actions]. */
data class HeaderControls(
    val nav: HeaderNav = HeaderNav.None,
    val actions: List<HeaderAction> = emptyList(),
)

/**
 * The one shell header (hard cut P3, letta-mobile-ckmub.3.5): the leading nav affordance, the
 * [title], the page's ONE [search] and the trailing actions ([controls]), on every platform. Desktop
 * draws it inside its window title bar (the free width between title and search stays the native drag
 * region); Android draws it in [com.letta.mobile.ui.shell.scaffold.LettaPageScaffold]'s header slot,
 * which puts it below the status bar while the page colour runs behind it (edge to edge).
 *
 * The header has no background of its own: the page's container (or the title bar) shows through.
 */
@Composable
fun ShellHeader(
    title: HeaderTitle,
    modifier: Modifier = Modifier,
    search: SearchSlot? = null,
    controls: HeaderControls = HeaderControls(),
) {
    val touch = LocalLettaHost.current.input == LettaInput.Touch
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (touch) ShellHeaderDefaults.TouchMinHeight else 0.dp)
            .testTag(ShellHeaderTags.HEADER),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderNavSlot(controls.nav)
        HeaderTitleSlot(title)
        if (title !is HeaderTitle.Custom || !title.fill) {
            // The free width: on desktop this is the window's drag lane.
            Box(Modifier.weight(1f).fillMaxHeight())
        }
        if (search != null) SearchSlotField(search, compact = touch)
        controls.actions.forEach { action -> HeaderActionSlot(action) }
    }
}

@Composable
private fun RowScope.HeaderNavSlot(nav: HeaderNav) {
    when (nav) {
        HeaderNav.None -> Box(Modifier.width(LettaDimens.Space.lg))
        is HeaderNav.Back -> HeaderIconButton(LettaIcons.ArrowBack, nav.contentDescription, nav.onClick, ShellHeaderTags.NAV)
        is HeaderNav.Menu -> HeaderIconButton(LettaIcons.Menu, nav.contentDescription, nav.onClick, ShellHeaderTags.NAV)
        is HeaderNav.Custom -> nav.content(this)
    }
}

@Composable
private fun RowScope.HeaderTitleSlot(title: HeaderTitle) {
    when (title) {
        HeaderTitle.None -> Unit
        is HeaderTitle.Text -> Column(Modifier.weight(1f, fill = false).padding(horizontal = LettaDimens.Space.xs)) {
            Text(
                text = title.title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag(ShellHeaderTags.TITLE),
            )
            title.subtitle?.let { subtitle ->
                val onClick = title.onSubtitleClick
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
                )
            }
        }
        is HeaderTitle.Custom -> if (title.fill) {
            Box(Modifier.weight(1f).fillMaxHeight()) { title.content() }
        } else {
            title.content()
        }
    }
}

@Composable
private fun RowScope.HeaderActionSlot(action: HeaderAction) {
    when (action) {
        is HeaderAction.Icon -> HeaderIconButton(action.icon, action.contentDescription, action.onClick, null)
        is HeaderAction.Custom -> action.content(this)
    }
}

@Composable
private fun HeaderIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, tag: String?) {
    IconButton(onClick = onClick, modifier = if (tag == null) Modifier else Modifier.testTag(tag)) {
        Icon(icon, contentDescription = contentDescription)
    }
}

/**
 * The header's search: a field-shaped trigger on a pointer window (an icon on a phone) that opens the
 * search in a [com.letta.mobile.ui.host.LettaPaneKind.Popover] below it. Focus is re-enabled for the
 * slot because desktop's title bar disables it for its whole subtree.
 */
@Composable
private fun SearchSlotField(slot: SearchSlot, compact: Boolean) {
    LettaSearchAnchoredField(
        query = slot.query,
        onQueryChange = slot.onQuery,
        sections = slot.sections,
        onRowSelected = slot.onRowSelected,
        onDismiss = slot.onDismiss,
        modifier = (if (compact) Modifier else Modifier.width(ShellHeaderDefaults.SearchWidth))
            .focusProperties { canFocus = true }
            .testTag(ShellHeaderTags.SEARCH),
        config = slot.config,
        compact = compact,
    )
}

/** The header's fixed sizes. */
object ShellHeaderDefaults {
    /** A phone app bar's height. A pointer window's title bar sets its own. */
    val TouchMinHeight = 64.dp

    /** The search trigger's width in a pointer header. */
    val SearchWidth = 260.dp
}

/** Test handles for the header's parts. */
object ShellHeaderTags {
    const val HEADER = "shell-header"
    const val NAV = "shell-header-nav"
    const val TITLE = "shell-header-title"
    const val SEARCH = "shell-header-search"
}
