package com.letta.mobile.desktop.memory

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.jewel.ui.component.TextField as JewelTextField
import androidx.compose.ui.text.input.TextFieldValue
import sh.calvin.autolinktext.SimpleTextMatchResult
import sh.calvin.autolinktext.TextMatcher
import sh.calvin.autolinktext.TextRule
import sh.calvin.autolinktext.rememberAutoLinkText

/** Bundled inputs for [BlockEditorPanel] (keeps the composable arity low). */
internal data class BlockEditorRequest(
    val target: BlockEditorTarget,
    val agentId: String,
    val blockApi: DesktopBlockApi,
    val onDismiss: () -> Unit,
    val onChanged: () -> Unit,
)

private data class BlockLoadResult(
    val value: String = "",
    val blockId: String? = null,
    val error: String? = null,
    val loadFailed: Boolean = false,
)

private suspend fun loadExistingBlock(
    target: BlockEditorTarget.Existing,
    blockApi: DesktopBlockApi,
): BlockLoadResult {
    val id = target.blockId
    if (id.isNullOrBlank()) {
        return BlockLoadResult(
            error = "This block has no id and can't be edited.",
            loadFailed = true,
        )
    }
    return runCatching { blockApi.getBlockById(id) }
        .fold(
            onSuccess = { BlockLoadResult(value = it.value, blockId = it.id.value) },
            onFailure = {
                BlockLoadResult(
                    error = it.message ?: "Could not load block",
                    loadFailed = true,
                )
            },
        )
}

private data class SaveBlockParams(
    val isNew: Boolean,
    val agentId: String,
    val label: String,
    val value: String,
    val blockId: String?,
    val blockApi: DesktopBlockApi,
)

private suspend fun saveBlock(params: SaveBlockParams): Result<Unit> = runCatching {
    when {
        params.isNew -> {
            params.blockApi.createAndAttachBlock(params.agentId, params.label.trim(), params.value)
        }
        params.blockId != null -> {
            params.blockApi.updateBlockById(params.blockId, params.value)
        }
        else -> error("Missing block id")
    }
}

private suspend fun deleteBlock(
    blockApi: DesktopBlockApi,
    blockId: String,
): Result<Unit> = runCatching { blockApi.deleteBlockById(blockId) }

private class BlockEditorUiState(
    initialLabel: String,
    initialBlockId: String?,
    isExisting: Boolean,
) {
    var label by mutableStateOf(initialLabel)
    var value by mutableStateOf("")
    var blockId by mutableStateOf(initialBlockId)
    var loading by mutableStateOf(isExisting)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    // When load fails, keep the editor read-only so Save can't clobber server content.
    var loadFailed by mutableStateOf(false)
}

@Composable
private fun rememberBlockEditorState(target: BlockEditorTarget): BlockEditorUiState =
    remember(target) {
        BlockEditorUiState(
            initialLabel = (target as? BlockEditorTarget.Existing)?.label.orEmpty(),
            initialBlockId = (target as? BlockEditorTarget.Existing)?.blockId,
            isExisting = target is BlockEditorTarget.Existing,
        )
    }

@Composable
private fun BlockEditorLoadEffect(request: BlockEditorRequest, state: BlockEditorUiState) {
    val target = request.target
    LaunchedEffect(target) {
        if (target !is BlockEditorTarget.Existing) return@LaunchedEffect
        val result = loadExistingBlock(target, request.blockApi)
        state.value = result.value
        if (result.blockId != null) state.blockId = result.blockId
        state.error = result.error
        state.loadFailed = result.loadFailed
        state.loading = false
    }
}

@Composable
internal fun BlockEditorPanel(request: BlockEditorRequest) {
    val scope = rememberCoroutineScope()
    val state = rememberBlockEditorState(request.target)
    val isNew = request.target is BlockEditorTarget.New
    BlockEditorLoadEffect(request, state)
    BlockEditorScaffold(
        BlockEditorScaffoldParams(
            title = if (isNew) "New memory block" else state.label,
            isNew = isNew,
            state = state,
            onDismiss = request.onDismiss,
            callbacks = blockEditorCallbacks(request, state, scope, isNew),
        ),
    )
}

private data class BlockEditorScaffoldParams(
    val title: String,
    val isNew: Boolean,
    val state: BlockEditorUiState,
    val onDismiss: () -> Unit,
    val callbacks: BlockEditorActionCallbacks,
)

@Composable
private fun BlockEditorScaffold(params: BlockEditorScaffoldParams) {
    val state = params.state
    Column(
        modifier = Modifier
            .width(380.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        BlockEditorHeader(title = params.title, onDismiss = params.onDismiss)
        BlockEditorFields(
            BlockEditorFieldsParams(
                isNew = params.isNew,
                label = state.label,
                onLabelChange = { state.label = it },
                value = state.value,
                onValueChange = { state.value = it },
                loading = state.loading,
                busy = state.busy,
                loadFailed = state.loadFailed,
            ),
        )
        state.error?.let { BlockEditorError(it) }
        BlockEditorActions(
            state = BlockEditorActionState(
                isNew = params.isNew,
                blockId = state.blockId,
                busy = state.busy,
                loading = state.loading,
                loadFailed = state.loadFailed,
            ),
            callbacks = params.callbacks,
        )
    }
}

@Composable
private fun BlockEditorError(message: String) {
    Text(text = message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

private fun blockEditorCallbacks(
    request: BlockEditorRequest,
    state: BlockEditorUiState,
    scope: CoroutineScope,
    isNew: Boolean,
): BlockEditorActionCallbacks = BlockEditorActionCallbacks(
    onDismiss = request.onDismiss,
    onDelete = {
        val currentBlockId = state.blockId ?: return@BlockEditorActionCallbacks
        state.busy = true
        state.error = null
        scope.launch {
            deleteBlock(request.blockApi, currentBlockId)
                .onSuccess { request.onChanged() }
                .onFailure { state.error = it.message ?: "Delete failed"; state.busy = false }
        }
    },
    onSave = {
        if (state.label.isBlank()) {
            state.error = "Label is required"
            return@BlockEditorActionCallbacks
        }
        state.busy = true
        state.error = null
        scope.launch {
            saveBlock(
                SaveBlockParams(
                    isNew = isNew,
                    agentId = request.agentId,
                    label = state.label,
                    value = state.value,
                    blockId = state.blockId,
                    blockApi = request.blockApi,
                ),
            )
                .onSuccess { request.onChanged() }
                .onFailure { state.error = it.message ?: "Save failed"; state.busy = false }
        }
    },
)

@Composable
private fun BlockEditorHeader(
    title: String,
    onDismiss: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
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
}

private data class BlockEditorFieldsParams(
    val isNew: Boolean,
    val label: String,
    val onLabelChange: (String) -> Unit,
    val value: String,
    val onValueChange: (String) -> Unit,
    val loading: Boolean,
    val busy: Boolean,
    val loadFailed: Boolean,
)

@Composable
private fun ColumnScope.BlockEditorFields(params: BlockEditorFieldsParams) {
    if (params.isNew) {
        Text("Label", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        var labelField by remember { mutableStateOf(TextFieldValue(params.label)) }
        JewelTextField(
            value = labelField,
            onValueChange = { labelField = it; params.onLabelChange(it.text) },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Text("Value", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (params.loading) {
        Text(
            text = "Loading…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    } else {
        DesktopTextArea(
            value = params.value,
            onValueChange = params.onValueChange,
            enabled = !params.busy && !params.loadFailed,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            placeholder = "Block contents…",
            decorationBoxModifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

private data class BlockEditorActionState(
    val isNew: Boolean,
    val blockId: String?,
    val busy: Boolean,
    val loading: Boolean,
    val loadFailed: Boolean,
)

private data class BlockEditorActionCallbacks(
    val onDismiss: () -> Unit,
    val onDelete: () -> Unit,
    val onSave: () -> Unit,
)

@Composable
private fun BlockEditorActions(
    state: BlockEditorActionState,
    callbacks: BlockEditorActionCallbacks,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!state.isNew && state.blockId != null) {
            DesktopOutlinedButton(
                onClick = callbacks.onDelete,
                enabled = !state.busy,
            ) { DesktopButtonContent(text = "Delete") }
        }
        Box(modifier = Modifier.weight(1f))
        DesktopOutlinedButton(onClick = callbacks.onDismiss, enabled = !state.busy) {
            DesktopButtonContent(text = "Cancel")
        }
        DesktopDefaultButton(
            onClick = callbacks.onSave,
            enabled = !state.busy && !state.loading && !state.loadFailed,
        ) { DesktopButtonContent(text = if (state.busy) "Saving…" else "Save") }
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
