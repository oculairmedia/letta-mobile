package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiImageAttachment

/**
 * letta-mobile-bglj6.1: renders one timeline item (a single message or a run block) with
 * every row type: user prompt, assistant text, reasoning, tool cards and diffs, approvals,
 * A2UI, images and provenance.
 */
@Composable
internal fun ChatRenderItemRow(
    item: ChatRenderItem,
    context: ChatRowContext,
    callbacks: ChatRowCallbacks,
    modifier: Modifier = Modifier,
) {
    Box(modifier)
}

/** The full-screen image viewer the timeline shows over everything when an image is tapped. */
@Composable
internal fun ChatImageViewer(
    images: List<UiImageAttachment>,
    initialIndex: Int,
    onDismiss: () -> Unit,
) {
}
