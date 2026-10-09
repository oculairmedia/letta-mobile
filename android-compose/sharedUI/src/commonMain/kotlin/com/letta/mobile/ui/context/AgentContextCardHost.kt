package com.letta.mobile.ui.context

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.letta.mobile.data.compaction.CompactionController
import com.letta.mobile.data.compaction.CompactionOutcome
import com.letta.mobile.data.compaction.CompactionRequest
import com.letta.mobile.data.compaction.key
import com.letta.mobile.data.context.AgentContextCardInputs
import com.letta.mobile.data.context.AgentContextCardModel
import com.letta.mobile.data.context.ContextBreakdownLoader
import com.letta.mobile.data.context.ContextBreakdownRequest
import com.letta.mobile.data.context.ContextBreakdownState
import com.letta.mobile.data.context.ContextMeter
import com.letta.mobile.data.context.ContextTokenReadings
import com.letta.mobile.data.context.contextReadingKeyOf
import com.letta.mobile.data.context.overviewFor
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.repository.modelcontrol.ModelPickerController
import com.letta.mobile.data.repository.modelcontrol.ModelPickerEntry
import com.letta.mobile.data.repository.modelcontrol.ModelPickerSource
import com.letta.mobile.ui.modelcontrol.ModelControlModal
import com.letta.mobile.ui.modelcontrol.ModelControlPresentation
import com.letta.mobile.ui.modelcontrol.ModelPickerActions
import com.letta.mobile.ui.theme.AgentContextDimens
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** letta-mobile-3io8k: what a host supplies once per session; any part may be absent. */
class AgentContextCardDeps(
    val readings: ContextTokenReadings?,
    val breakdown: ContextBreakdownLoader?,
    val compaction: CompactionController?,
    val pickerSource: ModelPickerSource?,
)

/** The conversation the card describes, and its model as the host resolves it. */
@Immutable
data class AgentContextFocus(
    val agentId: String,
    val conversationId: String?,
    val modelLabel: String?,
    /** The picker token of the current model (marks the row selected). */
    val modelValue: String?,
    val effort: String?,
    /** The model's window from the host's catalog; the breakdown's record window wins when known. */
    val windowTokens: Int?,
    /** A turn is running: no breakdown fetch, no compaction. */
    val turnRunning: Boolean,
) {
    val isDefaultConversation: Boolean
        get() = conversationId.isNullOrBlank() || conversationId == DEFAULT || conversationId == "conv-default-$agentId"

    private companion object {
        const val DEFAULT = "default"
    }
}

/** Model picks; the host keeps its own per-conversation vs agent semantics behind them. */
data class AgentContextCardActions(
    val onModelSelected: (ModelPickerEntry) -> Unit,
    val onEffortSelected: ((ModelPickerEntry, String?) -> Unit)? = null,
)

/** A bottom sheet on a phone; a popover anchored under the card beside the desktop sidebar. */
enum class AgentContextPresentation { Sheet, Popover }

/**
 * letta-mobile-3io8k: the drawer card with its sheet, wired to the session. The meter is the
 * streamed total; the breakdown loads only while the sheet is open and no turn runs, and again
 * after a compaction; nothing polls.
 */
@Composable
fun AgentContextCardHost(
    deps: AgentContextCardDeps,
    focus: AgentContextFocus,
    actions: AgentContextCardActions,
    presentation: AgentContextPresentation,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    var lastOutcome by remember(focus.agentId, focus.conversationId) { mutableStateOf<CompactionOutcome?>(null) }
    val reading = rememberReading(deps.readings, focus)
    val request = ContextBreakdownRequest(
        agentId = AgentId(focus.agentId),
        conversationId = focus.conversationId?.let(::ConversationId),
        reportedTotal = reading.total.takeUnless { reading.estimated },
    )
    val breakdown by (deps.breakdown?.state ?: IDLE).collectAsState()
    val compacting by (deps.compaction?.compacting ?: NONE_COMPACTING).collectAsState()
    val supported by (deps.compaction?.supported ?: UNKNOWN_SUPPORT).collectAsState()
    val compactionRequest = CompactionRequest(AgentId(focus.agentId), focus.conversationId?.let(::ConversationId))
    val model = AgentContextCardModel.present(
        AgentContextCardInputs(
            modelLabel = focus.modelLabel,
            effort = focus.effort,
            meter = ContextMeter.of(reading.total, focus.windowTokens, breakdown.overviewFor(request), reading.estimated),
            conversationIsDefault = focus.isDefaultConversation,
            compactSupported = if (deps.compaction == null) false else supported,
            compacting = compactionRequest.key in compacting,
            turnRunning = focus.turnRunning,
            lastOutcome = lastOutcome,
        ),
    )
    LaunchedEffect(open, request, focus.turnRunning) {
        if (open && !focus.turnRunning) deps.breakdown?.load(request)
    }
    val scope = rememberCoroutineScope()
    val onCompact: () -> Unit = {
        scope.launch {
            val controller = deps.compaction ?: return@launch
            lastOutcome = controller.compact(compactionRequest)
            deps.breakdown?.load(request.copy(reportedTotal = null), force = true)
        }
    }
    Box(modifier) {
        AgentContextCard(model = model, onClick = { open = true })
        if (open) {
            AgentContextSurface(presentation, onDismiss = { open = false }) {
                AgentContextSheetContent(model, rememberPicker(deps.pickerSource, focus, actions) { open = false }, onCompact)
            }
        }
    }
}

/** The conversation's streamed total and whether it is a post-compaction estimate. */
private data class Reading(val total: Int?, val estimated: Boolean)

@Composable
private fun rememberReading(readings: ContextTokenReadings?, focus: AgentContextFocus): Reading {
    val totals by (readings?.readings ?: NO_READINGS).collectAsState()
    val estimated by (readings?.estimated ?: NO_ESTIMATES).collectAsState()
    val key = contextReadingKeyOf(focus.agentId, focus.conversationId ?: DEFAULT_CONVERSATION)
    return Reading(total = key?.let(totals::get), estimated = key != null && key in estimated)
}

@Composable
private fun rememberPicker(
    source: ModelPickerSource?,
    focus: AgentContextFocus,
    actions: AgentContextCardActions,
    onPicked: () -> Unit,
): AgentContextPicker? {
    source ?: return null
    val scope = rememberCoroutineScope()
    val controller = remember(source) { ModelPickerController(scope, source) }
    LaunchedEffect(controller, focus.modelValue) { controller.setSelected(focus.modelValue) }
    LaunchedEffect(controller) { controller.ensureLoaded() }
    val state by controller.state.collectAsState()
    val bound = remember(controller, actions) {
        ModelPickerActions.bind(
            controller = controller,
            onSelect = { entry ->
                if (!entry.selected) actions.onModelSelected(entry)
                onPicked()
            },
            onEditModels = null,
            onEffortSelected = actions.onEffortSelected?.let { pick -> { entry, effort -> pick(entry, effort); onPicked() } },
        )
    }
    return AgentContextPicker(state, bound)
}

@Composable
private fun AgentContextSurface(
    presentation: AgentContextPresentation,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    when (presentation) {
        AgentContextPresentation.Sheet -> ModelControlModal(ModelControlPresentation.Sheet, onDismiss = onDismiss) {
            Column(Modifier.testTag(AgentContextTags.SHEET), content = content)
        }
        AgentContextPresentation.Popover -> AnchoredPopover(onDismiss, content)
    }
}

@Composable
private fun AnchoredPopover(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val gapPx = with(LocalDensity.current) { AgentContextDimens.popoverGap.roundToPx() }
    val provider = remember(gapPx) { BelowAnchorPositionProvider(gapPx) }
    Popup(popupPositionProvider = provider, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Surface(
            modifier = Modifier
                .width(AgentContextDimens.popoverWidth)
                .heightIn(max = AgentContextDimens.popoverMaxHeight)
                .testTag(AgentContextTags.SHEET),
            shape = RoundedCornerShape(LettaDimens.Radius.md),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(LettaDimens.Stroke.hairline, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = LettaDimens.Space.sm,
        ) {
            Column(Modifier.padding(vertical = LettaDimens.Space.sm), content = content)
        }
    }
}

/** Opens under the card's left edge, kept inside the window. */
internal class BelowAnchorPositionProvider(private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = anchorBounds.left.coerceAtMost(windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val below = anchorBounds.bottom + gapPx
        val y = below.coerceAtMost(windowSize.height - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

private const val DEFAULT_CONVERSATION = "default"
private val IDLE: StateFlow<ContextBreakdownState> = MutableStateFlow(ContextBreakdownState.Idle)
private val NONE_COMPACTING: StateFlow<Set<com.letta.mobile.data.compaction.CompactionKey>> = MutableStateFlow(emptySet())
private val UNKNOWN_SUPPORT: StateFlow<Boolean?> = MutableStateFlow(null)
private val NO_READINGS: StateFlow<Map<com.letta.mobile.data.context.ContextReadingKey, Int>> = MutableStateFlow(emptyMap())
private val NO_ESTIMATES: StateFlow<Set<com.letta.mobile.data.context.ContextReadingKey>> = MutableStateFlow(emptySet())
