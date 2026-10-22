package com.letta.mobile.ui.modelcontrol

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.letta.mobile.data.repository.modelcontrol.ModelPickerEntry
import com.letta.mobile.ui.components.LettaSectionLabel
import com.letta.mobile.ui.host.LettaPane
import com.letta.mobile.ui.host.LettaPaneKind
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.coroutines.delay

/** Test tags of a model's effort options. */
object ModelEffortTags {
    /** The pointer's flyout beside a row. */
    const val FLYOUT = "model_effort_flyout"

    /** The options themselves, in the flyout or the touch sheet. */
    const val OPTIONS = "model_effort_options"

    /** The Thinking switch's row. */
    const val THINKING = "model_effort_thinking"

    /** One effort level's row, followed by its wire literal. */
    const val LEVEL_PREFIX = "model_effort_level_"

    /** A touch row's trailing effort pill, followed by the row's picker value. */
    const val PILL_PREFIX = "model_picker_effort_"
}

/** The row whose options are open, and what they offer. */
internal data class EffortTarget(val key: String, val entry: ModelPickerEntry, val menu: ModelEffortMenu)

/**
 * Which row's effort options are open, in the flyout (pointer) or the sheet (touch). A hover opens
 * them; they close a moment after the pointer has left both the row and the flyout. Opened on
 * purpose (Right, the touch pill, a long-press) they stay until dismissed or picked from, and
 * Up/Down/Enter act inside them.
 */
@Stable
internal class EffortOptionsState(private val onPick: (ModelPickerEntry, String?) -> Unit) {
    var target: EffortTarget? by mutableStateOf(null)
        private set

    /** The keyboard's place in the open options; [NONE] while the pointer drives them. */
    var highlight: Int by mutableIntStateOf(NONE)
        private set

    private var hoveredRow: String? by mutableStateOf(null)
    private var flyoutHovered: Boolean by mutableStateOf(false)
    private var pinned = false

    /** True while nothing that keeps a hover-opened flyout open is under the pointer. */
    val idle: Boolean get() = target != null && !pinned && hoveredRow == null && !flyoutHovered

    /** Opens [target]'s options; [pinned] ones (not opened by a hover) ignore where the pointer goes. */
    fun open(target: EffortTarget, pinned: Boolean = true) {
        this.target = target
        this.pinned = pinned
        highlight = NONE
    }

    fun close() {
        target = null
        pinned = false
        highlight = NONE
    }

    fun rowHover(target: EffortTarget, hovered: Boolean) {
        if (hovered) {
            hoveredRow = target.key
            if (this.target?.key != target.key && !pinned) open(target, pinned = false)
        } else if (hoveredRow == target.key) {
            hoveredRow = null
        }
    }

    fun flyoutHover(hovered: Boolean) {
        flyoutHovered = hovered
    }

    fun pick(effort: String?) {
        val open = target ?: return
        close()
        onPick(open.entry, effort)
    }

    /** Right opens [row]'s options; while they are open, Up/Down/Enter act in them and Left/Escape close them. */
    fun onKey(event: KeyEvent, row: EffortTarget?): Boolean {
        val open = target ?: return openFromKeyboard(event, row)
        val choices = open.menu.choices()
        when (event.key) {
            Key.DirectionDown -> highlight = (highlight + 1).coerceAtMost(choices.lastIndex)
            Key.DirectionUp -> highlight = (highlight - 1).coerceAtLeast(0)
            Key.Enter, Key.NumPadEnter -> if (highlight in choices.indices) pick(choices[highlight]) else return false
            Key.DirectionLeft, Key.Escape -> close()
            else -> return false
        }
        return true
    }

    private fun openFromKeyboard(event: KeyEvent, row: EffortTarget?): Boolean {
        if (event.key != Key.DirectionRight || row == null) return false
        open(row)
        highlight = 0
        return true
    }

    companion object {
        const val NONE = -1
    }
}

/** The options' choices in display order: the Thinking switch's flip first, then each level. */
private fun ModelEffortMenu.choices(): List<String?> = listOfNotNull(thinking).map { it.toggledEffort } + levels

/** How long a hover-opened flyout outlives the pointer leaving it, so the pointer can cross the gap. */
private const val CLOSE_GRACE_MS = 250L

/** Closes a hover-opened flyout once the pointer has stayed away for [CLOSE_GRACE_MS]. */
@Composable
internal fun CloseWhenUnhovered(state: EffortOptionsState) {
    val idle = state.idle
    LaunchedEffect(idle) {
        if (idle) {
            delay(CLOSE_GRACE_MS)
            state.close()
        }
    }
}

/** The pointer's flyout beside [target]'s row, declared inside that row. It never takes focus from the search. */
@Composable
internal fun EffortFlyout(state: EffortOptionsState, target: EffortTarget) {
    val gap = with(LocalDensity.current) { LettaDimens.Space.xs.roundToPx() }
    val provider = remember(gap) { BesideAnchorPositionProvider(gap) }
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    LaunchedEffect(hovered) { state.flyoutHover(hovered) }
    Popup(popupPositionProvider = provider, onDismissRequest = state::close, properties = PopupProperties(focusable = false)) {
        Surface(
            modifier = Modifier.width(FLYOUT_WIDTH).hoverable(hover).testTag(ModelEffortTags.FLYOUT),
            shape = RoundedCornerShape(LettaDimens.Radius.md),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(LettaDimens.Stroke.hairline, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = LettaDimens.Space.sm,
        ) {
            ModelEffortOptions(target.menu, state.highlight, state::pick, Modifier.padding(vertical = LettaDimens.Space.xs))
        }
    }
}

/** Touch: the same options in a sheet, titled with the model. */
@Composable
internal fun EffortOptionsSheet(state: EffortOptionsState, target: EffortTarget) {
    LettaPane(LettaPaneKind.Sheet, onDismiss = state::close, title = target.entry.displayName) {
        ModelEffortOptions(target.menu, state.highlight, state::pick, Modifier.padding(bottom = LettaDimens.Space.lg))
    }
}

/**
 * A model's options: OPTIONS with the Thinking switch (where the model can turn thinking off) and
 * EFFORT with its levels, humanized, the one in force checked. [onPick] gets the effort a choice
 * selects; [highlight] marks the keyboard's choice.
 */
@Composable
internal fun ModelEffortOptions(menu: ModelEffortMenu, highlight: Int, onPick: (String?) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().testTag(ModelEffortTags.OPTIONS)) {
        var index = 0
        menu.thinking?.let { thinking ->
            OptionsLabel(ModelControlStrings.OPTIONS)
            OptionRow(highlighted = highlight == index++, onClick = { onPick(thinking.toggledEffort) }, tag = ModelEffortTags.THINKING) {
                Text(ModelControlStrings.THINKING, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = thinking.on, onCheckedChange = null)
            }
        }
        if (menu.levels.isNotEmpty()) OptionsLabel(ModelControlStrings.EFFORT)
        menu.levels.forEach { level ->
            val current = level.equals(menu.current, ignoreCase = true)
            OptionRow(
                highlighted = highlight == index++,
                onClick = { onPick(level) },
                tag = ModelEffortTags.LEVEL_PREFIX + level,
                modifier = Modifier.semantics { selected = current },
            ) {
                Text(ModelControlStrings.effortName(level), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (current) {
                    Icon(
                        LettaIcons.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(LettaDimens.Control.icon),
                    )
                }
            }
        }
    }
}

@Composable
private fun OptionsLabel(text: String) {
    LettaSectionLabel(text, Modifier.padding(horizontal = LettaDimens.Space.sm))
}

@Composable
private fun OptionRow(
    highlighted: Boolean,
    onClick: () -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = LettaDimens.Space.xs)
            .clip(MaterialTheme.shapes.small)
            .then(if (highlighted) Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = LettaDimens.Space.md, vertical = LettaDimens.Space.sm)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
        Spacer(Modifier.size(LettaDimens.Space.xs))
    }
}

private val FLYOUT_WIDTH = 220.dp

/** Beside the row, top-aligned: after its end edge, or before its start when the window has no room there. */
private class BesideAnchorPositionProvider(private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val after = anchorBounds.right + gapPx
        val before = anchorBounds.left - gapPx - popupContentSize.width
        val fitsAfter = after + popupContentSize.width <= windowSize.width
        val fitsBefore = before >= 0
        val ltr = layoutDirection == LayoutDirection.Ltr
        val x = when {
            ltr && fitsAfter || !ltr && !fitsBefore -> after
            else -> before
        }
        return IntOffset(
            x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            anchorBounds.top.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
        )
    }
}
