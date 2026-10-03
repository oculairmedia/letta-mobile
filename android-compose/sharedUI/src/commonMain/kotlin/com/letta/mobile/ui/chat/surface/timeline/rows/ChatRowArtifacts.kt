package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.composables.icons.lucide.LayoutGrid
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Maximize2
import com.composables.icons.lucide.Palette
import com.letta.mobile.data.a2ui.toA2uiSurfaceStateOrNull
import com.letta.mobile.data.canvas.compose.ComposeKind
import com.letta.mobile.data.chat.projection.CanvasArtifactError
import com.letta.mobile.data.chat.projection.CanvasArtifactReceipt
import com.letta.mobile.data.chat.projection.CanvasArtifactStatus
import com.letta.mobile.data.model.UiGeneratedComponent
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_canvas_dry_run
import com.letta.mobile.sharedui.resources.rows_canvas_failed
import com.letta.mobile.sharedui.resources.rows_canvas_items
import com.letta.mobile.sharedui.resources.rows_canvas_kind_card
import com.letta.mobile.sharedui.resources.rows_canvas_kind_checklist
import com.letta.mobile.sharedui.resources.rows_canvas_kind_group
import com.letta.mobile.sharedui.resources.rows_canvas_kind_note
import com.letta.mobile.sharedui.resources.rows_canvas_kind_text
import com.letta.mobile.sharedui.resources.rows_canvas_more_problems
import com.letta.mobile.sharedui.resources.rows_canvas_pending
import com.letta.mobile.sharedui.resources.rows_canvas_published
import com.letta.mobile.sharedui.resources.rows_canvas_show
import com.letta.mobile.sharedui.resources.rows_canvas_summary
import com.letta.mobile.sharedui.resources.rows_canvas_untitled
import com.letta.mobile.ui.a2ui.A2uiSurfaceRenderer
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.surface.touchStyle
import com.letta.mobile.ui.theme.ChatRowDimens
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LocalReducedMotion
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** The rounded container approvals, questions and generated UI share (desktop's ArtifactCard). */
@Composable
internal fun ArtifactCard(
    icon: ImageVector?,
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(LettaDimens.Radius.md),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.padding(LettaDimens.Space.lg),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(LettaDimens.Control.icon),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            content()
        }
    }
}

/** Tool cards are quiet on success; only failures carry a persistent badge. */
@Composable
internal fun ToolFailureBadge(status: String?) {
    if (!isToolStatusError(status)) return
    val color = MaterialTheme.colorScheme.error
    Surface(
        modifier = Modifier.testTag(ChatRowTestTags.TOOL_FAILURE_BADGE),
        shape = RoundedCornerShape(LettaDimens.Radius.sm),
        color = Color.Transparent,
        contentColor = color,
        border = BorderStroke(LettaDimens.Stroke.hairline, color),
    ) {
        Text(
            text = status.orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = LettaDimens.Space.sm, vertical = LettaDimens.Space.hair),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Monospace inset for tool arguments. */
@Composable
internal fun CodeBlock(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(LettaDimens.Radius.sm),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        SelectionContainer {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.md),
            )
        }
    }
}

/**
 * A generated-UI result rendered inline through the shared A2UI renderer when it adapts to a
 * known widget; otherwise the fallback text and raw props, so nothing renders as a silent
 * blank. Chat-anchored A2UI stays bounded and scrolls internally past
 * [ChatRowDimens.a2uiMaxHeight]. Actions go to ChatActions.submitA2uiAction (desktop's
 * `LocalDesktopA2uiActionHandler`, as a plain input).
 */
@Composable
internal fun GeneratedUiCard(
    generatedUi: UiGeneratedComponent,
    context: ChatRowContext,
    callbacks: ChatRowCallbacks,
) {
    val surface = remember(generatedUi) { generatedUi.toA2uiSurfaceStateOrNull() }
    ArtifactCard(icon = Lucide.LayoutGrid, title = generatedUi.name) {
        if (surface != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = ChatRowDimens.a2uiMaxHeight)
                    .verticalScroll(rememberScrollState()),
            ) {
                A2uiSurfaceRenderer(
                    surface = surface,
                    modifier = Modifier.fillMaxWidth(),
                    onAction = callbacks.actions::submitA2uiAction,
                    actionResolutionToken = context.a2uiResolvedActionCounters[surface.surfaceId] ?: 0,
                )
            }
        } else {
            generatedUi.fallbackText?.takeIf { it.isNotBlank() }?.let { fallback ->
                Text(text = fallback, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                text = generatedUi.propsJson,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * letta-mobile-bglj6.13: the canvas_compose artifacts a message narrates, one card each, in call
 * order. "Show on canvas" goes to [ChatSurfaceHost.showOnCanvas] (open the board and frame the
 * artifact), else to [ChatSurfaceHost.openCanvas]; with neither the card has no action.
 */
@Composable
internal fun CanvasArtifactCards(artifacts: List<CanvasArtifactReceipt>, callbacks: ChatRowCallbacks) {
    val host = callbacks.host
    val onShow = remember(host) {
        host.showOnCanvas ?: host.openCanvas?.let { open -> { _: CanvasArtifactReceipt -> open() } }
    }
    artifacts.forEach { receipt -> key(receipt.artifactId) { CanvasArtifactReceiptCard(receipt, onShow) } }
}

/**
 * One compose call as a card in [ArtifactCard]: its title, what it holds ("6 items · Note,
 * Checklist"), where it stands (adding, previewed, refused with the board's reason) and, once it is
 * on the board, "Show on canvas". The status is announced politely as it changes, and the action
 * is also offered as an accessibility action on the card itself.
 */
@Composable
internal fun CanvasArtifactReceiptCard(
    receipt: CanvasArtifactReceipt,
    onShowOnCanvas: ((CanvasArtifactReceipt) -> Unit)?,
) {
    val title = receipt.title ?: stringResource(Res.string.rows_canvas_untitled)
    val status = stringResource(receipt.status.label())
    val showLabel = stringResource(Res.string.rows_canvas_show)
    val show = onShowOnCanvas?.takeIf { receipt.canShowOnCanvas }
    ArtifactCard(
        icon = Lucide.Palette,
        title = title,
        modifier = Modifier
            .testTag(ChatRowTestTags.CANVAS_ARTIFACT)
            .semantics {
                contentDescription = "$title, $status"
                if (show != null) {
                    customActions = listOf(CustomAccessibilityAction(showLabel) { show(receipt); true })
                }
            },
    ) {
        CanvasArtifactSummary(receipt)
        CanvasArtifactStatusLine(receipt, status)
        receipt.error?.let { CanvasArtifactErrorText(it) }
        if (show != null) {
            val touch = touchStyle()
            OutlinedButton(
                onClick = { show(receipt) },
                modifier = Modifier
                    .then(if (touch) Modifier.fillMaxWidth() else Modifier)
                    .testTag(ChatRowTestTags.CANVAS_ARTIFACT_SHOW),
            ) {
                Icon(
                    imageVector = Lucide.Maximize2,
                    contentDescription = null,
                    modifier = Modifier.size(LettaDimens.Control.iconSm),
                )
                Spacer(Modifier.width(LettaDimens.Space.sm))
                Text(showLabel)
            }
        }
    }
}

/** "6 items · Text, Checklist, Note": the counts and kinds, read from the request while pending. */
@Composable
private fun CanvasArtifactSummary(receipt: CanvasArtifactReceipt) {
    if (receipt.itemCount <= 0) return
    val count = pluralStringResource(Res.plurals.rows_canvas_items, receipt.itemCount, receipt.itemCount)
    val kinds = receipt.kinds.map { stringResource(it.label()) }
    Text(
        text = if (kinds.isEmpty()) count else stringResource(Res.string.rows_canvas_summary, count, kinds.joinToString()),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Adding (a spinner, still under reduced motion), previewed or refused; a published card stays quiet. */
@Composable
private fun CanvasArtifactStatusLine(receipt: CanvasArtifactReceipt, status: String) {
    if (receipt.status == CanvasArtifactStatus.Published) return
    val failed = receipt.status == CanvasArtifactStatus.Failed
    Row(
        modifier = Modifier
            .testTag(ChatRowTestTags.CANVAS_ARTIFACT_STATUS)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (receipt.status == CanvasArtifactStatus.Pending && !LocalReducedMotion.current) {
            CircularProgressIndicator(
                modifier = Modifier.size(LettaDimens.Control.iconSm),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = LettaDimens.Stroke.hairline,
            )
        }
        Text(
            text = status,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The board's reason, as the refusal gave it, and how many more problems it listed. */
@Composable
private fun CanvasArtifactErrorText(error: CanvasArtifactError) {
    val message = error.message ?: return
    val more = (error.problemCount - 1).coerceAtLeast(0)
    SelectionContainer {
        Text(
            text = if (more > 0) "$message ${pluralStringResource(Res.plurals.rows_canvas_more_problems, more, more)}" else message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

private fun CanvasArtifactStatus.label(): StringResource = when (this) {
    CanvasArtifactStatus.Pending -> Res.string.rows_canvas_pending
    CanvasArtifactStatus.Published -> Res.string.rows_canvas_published
    CanvasArtifactStatus.Failed -> Res.string.rows_canvas_failed
    CanvasArtifactStatus.DryRun -> Res.string.rows_canvas_dry_run
}

private fun ComposeKind.label(): StringResource = when (this) {
    ComposeKind.NOTE -> Res.string.rows_canvas_kind_note
    ComposeKind.CHECKLIST -> Res.string.rows_canvas_kind_checklist
    ComposeKind.CARD -> Res.string.rows_canvas_kind_card
    ComposeKind.TEXT -> Res.string.rows_canvas_kind_text
    ComposeKind.GROUP -> Res.string.rows_canvas_kind_group
}
