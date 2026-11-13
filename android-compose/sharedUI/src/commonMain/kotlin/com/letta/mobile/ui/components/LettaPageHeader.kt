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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * The one header every shared page draws, so a title, its actions and its controls sit in the same
 * place on every page. Top to bottom, always in this order:
 *
 * 1. the title row: [title], the [context] slot (an agent picker, a search field), then at the far
 *    end the icon [actions], the [refresh] slot and the one labelled [primaryAction];
 * 2. on a page narrower than [LettaDimens.Pane.wideBreakpoint], [context] on a row of its own
 *    (never above the title);
 * 3. [controls]: the page's view switch or filters (a [LettaChipTabRow]), with any paging beside it;
 * 4. [caption]: one muted line (the zone times read in, where a secret is stored);
 * 5. [content]: what only this page has (a search field, an error banner, a stats strip).
 *
 * A host that titles the screen itself passes a null [title]; the rest keeps its place.
 *
 * [primaryAction] is the page's one labelled action ("New schedule"), drawn as a filled button
 * after a plus unless the item names another icon. [refresh] is the page's [LettaRefreshAction],
 * which draws on pointer hosts only: touch pulls the page's [LettaRefreshable] instead.
 */
@Composable
fun LettaPageHeader(
    title: String?,
    modifier: Modifier = Modifier,
    primaryAction: LettaMenuItem? = null,
    caption: String? = null,
    context: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    refresh: (@Composable () -> Unit)? = null,
    controls: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val wide = maxWidth >= LettaDimens.Pane.wideBreakpoint
        val rowContext = context.takeIf { wide }
        // The row leaves the layout when it would be empty (a titled host on touch with nothing to press).
        val showsTitleRow = title != null || primaryAction != null || rowContext != null || actions != null ||
            (refresh != null && isPointerHost())
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = lettaPageGutter(wide), vertical = LettaDimens.Space.sm),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        ) {
            if (showsTitleRow) {
                PageTitleRow(title, primaryAction, rowContext) {
                    actions?.invoke(this)
                    refresh?.invoke()
                }
            }
            if (!wide) context?.invoke()
            controls?.invoke()
            caption?.let { PageCaption(it) }
            content()
        }
    }
}

/** The page's side inset: rows under the header (filters, legends, lists) line up on it too. */
fun lettaPageGutter(wide: Boolean): Dp = if (wide) LettaDimens.Space.xl else LettaDimens.Space.lg

/** Test tag of [LettaPageHeader]'s title. */
const val LETTA_PAGE_TITLE_TAG = "letta_page_header_title"

/** Test tag of [LettaPageHeader]'s labelled primary action. */
const val LETTA_PAGE_PRIMARY_ACTION_TAG = "letta_page_header_primary_action"

/** Test tag of [LettaPageHeader]'s caption. */
const val LETTA_PAGE_CAPTION_TAG = "letta_page_header_caption"

@Composable
private fun PageTitleRow(
    title: String?,
    primaryAction: LettaMenuItem?,
    context: (@Composable () -> Unit)?,
    iconActions: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = LettaDimens.Control.minTouchTarget),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
    ) {
        title?.let {
            Text(
                it,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() }.testTag(LETTA_PAGE_TITLE_TAG),
            )
        }
        Box(Modifier.weight(1f)) { context?.invoke() }
        iconActions()
        primaryAction?.let { PagePrimaryButton(it) }
    }
}

@Composable
private fun PagePrimaryButton(action: LettaMenuItem) {
    Button(onClick = action.onClick, enabled = action.enabled, modifier = Modifier.testTag(LETTA_PAGE_PRIMARY_ACTION_TAG)) {
        Icon(action.icon ?: LettaIcons.Add, contentDescription = null, modifier = Modifier.size(LettaDimens.Control.icon))
        Spacer(Modifier.size(LettaDimens.Space.xs))
        Text(action.label, maxLines = 1)
    }
}

@Composable
private fun PageCaption(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.customColors.onSurfaceMutedColor,
        modifier = Modifier.testTag(LETTA_PAGE_CAPTION_TAG),
    )
}
