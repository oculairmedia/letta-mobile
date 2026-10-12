package com.letta.mobile.ui.components.list

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.components.LettaListRow
import com.letta.mobile.ui.components.LettaListRowSpec
import com.letta.mobile.ui.theme.LettaDimens
import kotlin.math.roundToInt

/** The plain roster row: the shared [LettaListRow], selected or under the cursor, clicking through the roster. */
@Composable
fun RosterListRow(
    spec: LettaListRowSpec,
    row: RosterRowState,
    slots: RosterRowSlots = RosterRowSlots(),
    modifier: Modifier = Modifier,
) {
    LettaListRow(
        spec = spec.copy(selected = spec.selected || row.selected),
        onClick = row.onClick,
        modifier = modifier,
        leading = slots.leading,
        end = slots.end,
    )
}

/**
 * A title edited in place. Enter (or the keyboard's Done) saves a changed, non-blank title,
 * trimmed; Escape, or saving the same title, leaves it as it was.
 */
@Composable
fun RosterRenameField(request: RosterRenameRequest, modifier: Modifier = Modifier) {
    val title = request.title
    val onDone = request.onDone
    var value by remember { mutableStateOf(TextFieldValue(title, selection = TextRange(0, title.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val commit = {
        val next = value.text.trim()
        if (next.isNotEmpty() && next != title) request.onRename(next)
        onDone()
    }
    OutlinedTextField(
        value = value,
        onValueChange = { value = it },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
        placeholder = { Text(request.placeholder) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = LettaDimens.Space.xs)
            .focusRequester(focus)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Enter, Key.NumPadEnter -> true.also { commit() }
                    Key.Escape -> true.also { onDone() }
                    else -> false
                }
            },
    )
}

/** What a row's gestures need: its tag, the input, whether it is hovered, its swipe and quick actions. */
@Immutable
internal class RosterGestureSpec(
    val tag: RosterRowTag,
    val touch: Boolean,
    val hovered: Boolean,
    val swipe: RosterAction?,
    val quick: List<RosterAction>,
)

/** A touch swipe from the end runs [RosterGestureSpec.swipe]; on pointer, hover shows the quick actions. */
@Composable
internal fun RosterGestures(spec: RosterGestureSpec, content: @Composable () -> Unit) {
    val swipe = spec.swipe
    when {
        spec.touch && swipe != null -> SwipeRow(swipe, spec.tag, content)
        !spec.touch && spec.quick.isNotEmpty() -> HoverActions(spec, content)
        else -> content()
    }
}

@Composable
private fun SwipeRow(action: RosterAction, tag: RosterRowTag, content: @Composable () -> Unit) {
    var offset by remember { mutableFloatStateOf(0f) }
    // The action is rebuilt on each composition; the gesture must not restart with it.
    val current by rememberUpdatedState(action)
    val threshold = with(LocalDensity.current) { SWIPE_THRESHOLD.toPx() }
    Box {
        if (offset < 0f) SwipeBackdrop(action, Modifier.matchParentSize().testTag(tag.swipeBackdrop))
        Box(
            Modifier
                .offset { IntOffset(offset.roundToInt(), 0) }
                .pointerInput(threshold) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (offset <= -threshold) current.onClick()
                            offset = 0f
                        },
                        onDragCancel = { offset = 0f },
                    ) { change, amount ->
                        change.consume()
                        offset = (offset + amount).coerceIn(-threshold * SWIPE_OVERSHOOT, 0f)
                    }
                },
        ) { content() }
    }
}

@Composable
private fun SwipeBackdrop(action: RosterAction, modifier: Modifier) {
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = LettaDimens.Space.md),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(action.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

@Composable
private fun HoverActions(spec: RosterGestureSpec, content: @Composable () -> Unit) {
    Box {
        content()
        if (spec.hovered) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = LettaDimens.Space.xs)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                spec.quick.forEach { action -> QuickActionButton(action, spec.tag) }
            }
        }
    }
}

@Composable
private fun QuickActionButton(action: RosterAction, tag: RosterRowTag) {
    IconButton(
        onClick = action.onClick,
        modifier = Modifier.size(LettaDimens.Control.iconButton).testTag(tag.quick(action.label)),
    ) {
        val icon = action.icon
        if (icon != null) {
            Icon(icon, contentDescription = action.label, modifier = Modifier.size(LettaDimens.Control.icon))
        } else {
            Text(action.label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private val SWIPE_THRESHOLD = 72.dp
private const val SWIPE_OVERSHOOT = 1.5f
