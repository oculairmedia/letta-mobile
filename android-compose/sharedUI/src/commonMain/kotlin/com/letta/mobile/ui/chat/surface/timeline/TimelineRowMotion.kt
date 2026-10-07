package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.letta.mobile.ui.theme.LocalReducedMotion

/**
 * letta-mobile-bglj6.1.19: a timeline row's placement motion (legacy ChatMessageListLazyColumn
 * `Modifier.animateItem()`): a new step (thought, then tool, then prose) fades in and the rows it
 * displaces glide to their new places instead of snapping. Off under reduced motion.
 *
 * [fadesIn] is false for a row whose entrance something else owns: the prompt the person just
 * sent is revealed by the send flight's hand-off, and a second fade would dip it.
 */
@Composable
internal fun LazyItemScope.timelineRowMotion(fadesIn: Boolean = true): Modifier {
    if (LocalReducedMotion.current) return Modifier
    return if (fadesIn) Modifier.animateItem() else Modifier.animateItem(fadeInSpec = null)
}
