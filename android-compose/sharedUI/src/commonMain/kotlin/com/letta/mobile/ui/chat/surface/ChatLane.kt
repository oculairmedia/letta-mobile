package com.letta.mobile.ui.chat.surface

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import com.letta.mobile.data.chat.layout.ChatViews
import com.letta.mobile.data.model.view.RuntimeViewId
import com.letta.mobile.ui.theme.LettaDimens

// The frame projects slots rather than importing shell layout into the chat renderer (a package cycle).
internal val LocalChatLane = compositionLocalOf<List<Pair<RuntimeViewId, String?>>> { ChatViews.Default.map { it to null } }
internal val LocalChatModuleContent = compositionLocalOf<@Composable (Int, Modifier) -> Unit> { { _, _ -> } }

/** One content lane, reused by the full page and the dock's conversation area. */
@Composable
internal fun ChatLane(
    modifier: Modifier,
    docked: Boolean = false,
    transcript: @Composable (Modifier) -> Unit,
    status: @Composable () -> Unit,
    composer: @Composable (Modifier, Boolean) -> Unit,
) {
    val lane = LocalChatLane.current
    val hasExtras = lane.any { it.first !in ChatViews.Default }
    val wide = remember(hasExtras) { mutableStateOf(false) }
    val measuredModifier = if (hasExtras) {
        val breakpoint = with(LocalDensity.current) { LettaDimens.Pane.wideBreakpoint.toPx() }
        modifier.onSizeChanged { wide.value = it.width >= breakpoint }
    } else {
        modifier
    }
    val attachedStatus = lane.indexOfFirst { it.first == ChatViews.STATUS }.let { index ->
        index >= 0 && lane.getOrNull(index + 1)?.first == ChatViews.COMPOSER
    }
    val content: @Composable (Modifier) -> Unit = { bodyModifier ->
        Column(bodyModifier) {
            lane.forEachIndexed { index, slot ->
                key(slot) {
                    when (slot.first) {
                        ChatViews.TRANSCRIPT -> transcript(Modifier.weight(1f).fillMaxWidth())
                        ChatViews.STATUS -> if (!attachedStatus) status()
                        ChatViews.COMPOSER -> if (!docked) composer(Modifier.fillMaxWidth(), attachedStatus)
                        else -> ExtraChatModule(wide, index, Modifier.weight(1f).fillMaxWidth())
                    }
                }
            }
        }
    }
    // One composition position even when an optional view adds/removes the width observer.
    content(measuredModifier)
}

/** Only extra slots observe width changes; input and transcript retain their composition and focus. */
@Composable
private fun ExtraChatModule(wide: State<Boolean>, index: Int, modifier: Modifier) {
    if (wide.value) LocalChatModuleContent.current(index, modifier)
}
