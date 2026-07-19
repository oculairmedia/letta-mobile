package com.letta.mobile.desktop.memory

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.DpOffset
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dk.kuiver.model.buildKuiver
import com.dk.kuiver.model.edge
import com.dk.kuiver.model.layout.LayoutConfig
import com.dk.kuiver.model.nodes
import com.dk.kuiver.rememberKuiverViewerState
import com.dk.kuiver.renderer.KuiverViewer
import com.dk.kuiver.renderer.KuiverViewerConfig
import com.dk.kuiver.ui.StyledEdgeContent
import com.letta.mobile.desktop.components.DesktopChipTab
import com.letta.mobile.desktop.components.DesktopRefreshAction
import com.letta.mobile.data.memory.MemoryAccentRole
import com.letta.mobile.data.memory.MemoryCategories
import com.letta.mobile.data.memory.MemoryCategory
import com.letta.mobile.data.memory.MemoryGraphNode
import com.letta.mobile.data.memory.MemoryGraphNodeKind
import com.letta.mobile.data.memory.MemoryParityGraph
import com.letta.mobile.data.memory.MemoryParityItem
import com.letta.mobile.data.memory.MemoryParitySectionKind
import com.letta.mobile.data.memory.MemoryParitySummary
import com.letta.mobile.data.memory.MemorySummaryMetric
import com.letta.mobile.data.memory.MemorySummaryMetricKind
import com.letta.mobile.ui.theme.customColors
import com.letta.mobile.data.memory.MemoryTextLink
import com.letta.mobile.data.memory.accentRole
import com.letta.mobile.data.memory.validForText
import com.letta.mobile.desktop.DesktopButtonContent
import com.letta.mobile.desktop.DesktopDefaultButton
import com.letta.mobile.desktop.DesktopInlineError
import com.letta.mobile.desktop.DesktopOutlinedButton
import com.letta.mobile.desktop.DesktopTextArea
import kotlinx.coroutines.launch
import org.jetbrains.jewel.ui.component.TextField as JewelTextField
import androidx.compose.ui.text.input.TextFieldValue
import sh.calvin.autolinktext.SimpleTextMatchResult
import sh.calvin.autolinktext.TextMatcher
import sh.calvin.autolinktext.TextRule
import sh.calvin.autolinktext.rememberAutoLinkText

@Composable
internal fun BlockEditorPanel(
    target: BlockEditorTarget,
    agentId: String,
    blockApi: DesktopBlockApi,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val isNew = target is BlockEditorTarget.New
    var label by remember(target) {
        mutableStateOf((target as? BlockEditorTarget.Existing)?.label.orEmpty())
    }
    var value by remember(target) { mutableStateOf("") }
    var blockId by remember(target) {
        mutableStateOf((target as? BlockEditorTarget.Existing)?.blockId)
    }
    var loading by remember(target) { mutableStateOf(target is BlockEditorTarget.Existing) }
    var busy by remember(target) { mutableStateOf(false) }
    var error by remember(target) { mutableStateOf<String?>(null) }
    // When an existing block fails to load, the editor is read-only — saving the
    // empty value would clobber the real block content on the server.
    var loadFailed by remember(target) { mutableStateOf(false) }

    LaunchedEffect(target) {
        if (target is BlockEditorTarget.Existing) {
            val id = target.blockId
            if (id.isNullOrBlank()) {
                error = "This block has no id and can't be edited."
                loadFailed = true
                loading = false
            } else {
                runCatching { blockApi.getBlockById(id) }
                    .onSuccess {
                        value = it.value
                        blockId = it.id.value
                        loading = false
                    }
                    .onFailure {
                        error = it.message ?: "Could not load block"
                        loadFailed = true
                        loading = false
                    }
            }
        }
    }

    Column(
        modifier = Modifier
            .width(380.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (isNew) "New memory block" else label,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "Close",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp).clickable(onClick = onDismiss),
            )
        }

        if (isNew) {
            Text("Label", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            var labelField by remember { mutableStateOf(TextFieldValue("")) }
            JewelTextField(
                value = labelField,
                onValueChange = { labelField = it; label = it.text },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Text("Value", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (loading) {
            Text(
                text = "Loading…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        } else {
            DesktopTextArea(
                value = value,
                onValueChange = { value = it },
                enabled = !busy && !loadFailed,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                placeholder = "Block contents…",
                decorationBoxModifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }

        error?.let {
            Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val currentBlockId = blockId
            if (!isNew && currentBlockId != null) {
                DesktopOutlinedButton(
                    onClick = {
                        busy = true; error = null
                        scope.launch {
                            runCatching { blockApi.deleteBlockById(currentBlockId) }
                                .onSuccess { onChanged() }
                                .onFailure { error = it.message ?: "Delete failed"; busy = false }
                        }
                    },
                    enabled = !busy,
                ) { DesktopButtonContent(text = "Delete") }
            }
            Box(modifier = Modifier.weight(1f))
            DesktopOutlinedButton(onClick = onDismiss, enabled = !busy) {
                DesktopButtonContent(text = "Cancel")
            }
            DesktopDefaultButton(
                onClick = {
                    if (label.isBlank()) {
                        error = "Label is required"
                    } else {
                        busy = true; error = null
                        val id = blockId
                        scope.launch {
                            runCatching {
                                if (isNew) {
                                    blockApi.createAndAttachBlock(agentId, label.trim(), value)
                                } else if (id != null) {
                                    blockApi.updateBlockById(id, value)
                                } else {
                                    error("Missing block id")
                                }
                            }
                                .onSuccess { onChanged() }
                                .onFailure { error = it.message ?: "Save failed"; busy = false }
                        }
                    }
                },
                enabled = !busy && !loading && !loadFailed,
            ) { DesktopButtonContent(text = if (busy) "Saving…" else "Save") }
        }
    }
}

internal fun MemoryParitySectionKind.icon(): ImageVector = when (this) {
    MemoryParitySectionKind.Skills -> Icons.Outlined.Build
    MemoryParitySectionKind.Memory -> Icons.Outlined.Memory
    MemoryParitySectionKind.Schedules -> Icons.Outlined.Schedule
    MemoryParitySectionKind.Channels -> Icons.Outlined.Hub
}

@Composable
internal fun MemoryParityItem.accentColor(): Color = accentRole.color()

@Composable
internal fun MemoryAccentRole.color(): Color = when (this) {
    MemoryAccentRole.Primary -> MaterialTheme.colorScheme.primary
    MemoryAccentRole.Secondary -> MaterialTheme.colorScheme.secondary
    MemoryAccentRole.Tertiary -> MaterialTheme.colorScheme.tertiary
    MemoryAccentRole.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    MemoryAccentRole.Error -> MaterialTheme.colorScheme.error
}

@Composable
internal fun MemoryCategory.categoryColor(): Color = when (this) {
    MemoryCategory.Persona -> MaterialTheme.customColors.categoryPersonaColor
    MemoryCategory.Human -> MaterialTheme.customColors.categoryHumanColor
    MemoryCategory.Onboarding -> MaterialTheme.customColors.categoryOnboardingColor
    MemoryCategory.Project -> MaterialTheme.customColors.categoryProjectColor
    MemoryCategory.Archival -> MaterialTheme.customColors.categoryArchivalColor
}
