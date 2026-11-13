package com.letta.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.customColors

/**
 * The one header every shared page draws (letta-mobile-16b2u), so a title, its actions and its
 * controls sit in the same place on every page. Top to bottom, always in this order:
 *
 * 1. the title row: [title], the [context] slot (an agent picker, a search field), then at the far
 *    end the icon [actions], the [refresh] button and the one labelled [primaryAction];
 * 2. on a page narrower than [LettaDimens.Pane.wideBreakpoint], [context] on a row of its own
 *    (never above the title);
 * 3. [controls]: the page's view switch or filters (a [LettaChipTabRow]), with any paging beside it;
 * 4. [caption]: one muted line (the zone times read in, where a secret is stored);
 * 5. [content]: what only this page has (a search field, an error banner, a stats strip).
 *
 * A host that titles the screen itself passes a null [title]; the rest keeps its place. The refresh
 * button shows on pointer hosts only: touch pulls the page's [LettaRefreshable] instead.
 */
@Composable
fun LettaPageHeader(
    title: String?,
    modifier: Modifier = Modifier,
    primaryAction: LettaPageAction? = null,
    refresh: LettaPageRefresh? = null,
    caption: LettaPageCaption? = null,
    context: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    controls: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val wide = maxWidth >= LettaDimens.Pane.wideBreakpoint
        val titleRow = PageTitleRow(title, primaryAction, refresh, context.takeIf { wide }, actions)
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = LettaPageHeaderDefaults.gutter(wide), vertical = LettaDimens.Space.sm),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        ) {
            if (titleRow.shows(isPointerHost())) PageTitleRowContent(titleRow)
            if (!wide) context?.invoke()
            controls?.invoke()
            caption?.let { PageCaption(it) }
            content()
        }
    }
}

/** The page's one labelled action ("New schedule"): a filled button at the end of the title row. */
@Immutable
class LettaPageAction(
    val label: String,
    val onClick: () -> Unit,
    val icon: ImageVector? = LettaIcons.Add,
    val testTag: String? = null,
)

/** Reloading the page: the title row's refresh icon on pointer hosts ([button] is a host's own control). */
@Immutable
class LettaPageRefresh(
    val refreshing: Boolean,
    val onRefresh: () -> Unit,
    val testTag: String? = null,
    val button: (@Composable (onRefresh: () -> Unit, modifier: Modifier) -> Unit)? = null,
)

/** The header's one muted line. */
@Immutable
class LettaPageCaption(val text: String, val testTag: String? = null)

/** The page tokens every page shares with its header. */
object LettaPageHeaderDefaults {
    /** The page's side inset: rows under the header (filters, legends, lists) line up on it too. */
    fun gutter(wide: Boolean): Dp = if (wide) LettaDimens.Space.xl else LettaDimens.Space.lg
}

/** Test tags of [LettaPageHeader]. */
object LettaPageHeaderTags {
    const val TITLE = "letta_page_header_title"
}

private class PageTitleRow(
    val title: String?,
    val primaryAction: LettaPageAction?,
    val refresh: LettaPageRefresh?,
    val context: (@Composable () -> Unit)?,
    val actions: (@Composable RowScope.() -> Unit)?,
) {
    /** The row leaves the layout when it would be empty (a titled host on touch with nothing to press). */
    fun shows(pointer: Boolean): Boolean =
        title != null || primaryAction != null || context != null || actions != null || (pointer && refresh != null)
}

@Composable
private fun PageTitleRowContent(row: PageTitleRow) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = LettaDimens.Control.minTouchTarget),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
    ) {
        row.title?.let { title ->
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() }.testTag(LettaPageHeaderTags.TITLE),
            )
        }
        Box(Modifier.weight(1f)) { row.context?.invoke() }
        row.actions?.invoke(this)
        row.refresh?.let { refresh ->
            LettaRefreshAction(
                refreshing = refresh.refreshing,
                onRefresh = refresh.onRefresh,
                modifier = refresh.testTag?.let { Modifier.testTag(it) } ?: Modifier,
                button = refresh.button,
            )
        }
        row.primaryAction?.let { PagePrimaryButton(it) }
    }
}

@Composable
private fun PagePrimaryButton(action: LettaPageAction) {
    Button(onClick = action.onClick, modifier = action.testTag?.let { Modifier.testTag(it) } ?: Modifier) {
        action.icon?.let { icon ->
            Icon(icon, contentDescription = null, modifier = Modifier.size(LettaDimens.Control.icon))
            Spacer(Modifier.size(LettaDimens.Space.xs))
        }
        Text(action.label, maxLines = 1)
    }
}

@Composable
private fun PageCaption(caption: LettaPageCaption) {
    Text(
        caption.text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.customColors.onSurfaceMutedColor,
        modifier = caption.testTag?.let { Modifier.testTag(it) } ?: Modifier,
    )
}
