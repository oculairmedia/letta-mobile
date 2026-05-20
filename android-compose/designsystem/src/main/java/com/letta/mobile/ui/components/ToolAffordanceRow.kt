package com.letta.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.Tool
import com.letta.mobile.designsystem.R
import com.letta.mobile.ui.icons.LettaIcons

private val FadingEdgeWidth = 24.dp

/**
 * Paints a horizontal gradient that fades the leading and trailing edges of
 * the row from [color] to transparent, so a scrollable LazyRow visibly
 * dissolves into the surrounding surface instead of hard-clipping at the
 * padding bounds. Pass [MaterialTheme.colorScheme.surface] (or whatever the
 * parent surface paints) so the gradient blends theme-aware in light and
 * dark modes.
 */
private fun Modifier.fadingEdges(color: Color): Modifier = drawWithContent {
    drawContent()
    val w = FadingEdgeWidth.toPx()
    drawRect(
        brush = Brush.horizontalGradient(
            colors = listOf(color, Color.Transparent),
            startX = 0f,
            endX = w,
        ),
        size = Size(w, size.height),
    )
    drawRect(
        brush = Brush.horizontalGradient(
            colors = listOf(Color.Transparent, color),
            startX = size.width - w,
            endX = size.width,
        ),
        topLeft = Offset(size.width - w, 0f),
        size = Size(w, size.height),
    )
}

object ToolAffordanceRowTestTags {
    const val Container = "tool-affordance-row"
    const val ChipPrefix = "tool-affordance-chip-"
}

@Composable
fun ToolAffordanceRow(
    tools: List<Tool>,
    onToolSelected: (Tool) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tools.isEmpty()) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(ToolAffordanceRowTestTags.Container),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.tool_affordance_row_label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            modifier = Modifier.padding(start = 4.dp),
        )
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .fadingEdges(MaterialTheme.colorScheme.surface),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(items = tools, key = { it.id.value }) { tool ->
                val chipDescription = stringResource(
                    R.string.tool_affordance_chip_content_description,
                    tool.name,
                )
                InputChip(
                    selected = false,
                    onClick = { onToolSelected(tool) },
                    label = {
                        Text(
                            text = tool.name,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = LettaIcons.Tool,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                    },
                    modifier = Modifier
                        .testTag(ToolAffordanceRowTestTags.ChipPrefix + tool.id.value)
                        .semantics { contentDescription = chipDescription },
                )
            }
        }
    }
}
