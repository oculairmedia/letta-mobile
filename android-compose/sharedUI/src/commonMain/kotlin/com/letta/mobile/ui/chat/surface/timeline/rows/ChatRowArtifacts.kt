package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.composables.icons.lucide.LayoutGrid
import com.composables.icons.lucide.Lucide
import com.letta.mobile.data.a2ui.toA2uiSurfaceStateOrNull
import com.letta.mobile.data.model.UiGeneratedComponent
import com.letta.mobile.ui.a2ui.A2uiSurfaceRenderer
import com.letta.mobile.ui.theme.ChatRowDimens
import com.letta.mobile.ui.theme.LettaDimens

/** The rounded container approvals, questions and generated UI share (desktop's ArtifactCard). */
@Composable
internal fun ArtifactCard(
    icon: ImageVector?,
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
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
