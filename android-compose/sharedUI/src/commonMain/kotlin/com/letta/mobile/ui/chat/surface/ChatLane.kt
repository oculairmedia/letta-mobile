package com.letta.mobile.ui.chat.surface

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import com.letta.mobile.data.chat.layout.ChatViews
import com.letta.mobile.ui.theme.LettaDimens

// The frame projects slots rather than importing shell layout into the chat renderer (a package cycle).
internal val LocalChatLane = compositionLocalOf { ChatViews.Default.map { it to null as String? } }
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
    val module = LocalChatModuleContent.current
    val attachedStatus = lane.indexOfFirst { it.first == ChatViews.STATUS }.let { index ->
        index >= 0 && lane.getOrNull(index + 1)?.first == ChatViews.COMPOSER
    }
    BoxWithConstraints(modifier) {
        val wide = maxWidth >= LettaDimens.Pane.wideBreakpoint
        Column(Modifier.fillMaxSize()) {
            lane.forEachIndexed { index, slot ->
                key(slot) {
                    when (slot.first) {
                        ChatViews.TRANSCRIPT -> transcript(Modifier.weight(1f).fillMaxWidth())
                        ChatViews.STATUS -> if (!docked && !attachedStatus) status()
                        ChatViews.COMPOSER -> if (!docked) composer(Modifier.fillMaxWidth(), attachedStatus)
                        else -> if (wide) module(index, Modifier.weight(1f).fillMaxWidth())
                    }
                }
            }
        }
    }
}
