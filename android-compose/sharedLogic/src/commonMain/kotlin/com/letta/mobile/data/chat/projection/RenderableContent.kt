package com.letta.mobile.data.chat.projection

import com.letta.mobile.data.model.UiMessage

/**
 * letta-mobile-jqiu3: an assistant text segment that carries nothing a renderer can draw.
 *
 * A model turn is segmented around its tool calls, and the segments between calls are often
 * whitespace (`"\n\n"`, `" "`). Such a segment has no text, no tool calls, no attachments and no
 * structured payload, yet as a render row it still carried the row's vertical padding and the
 * bubble's own padding, which is what drew the empty bands between tool cards and paragraphs in
 * a settled timeline. Segments like these are not rows: they are dropped before grouping, so no
 * render item exists to claim space.
 *
 * Reasoning rows are excluded (a blank thought still draws its "Thought" header), as are errors
 * and pending rows (a pending assistant row is the in-flight placeholder the live path owns).
 */
fun UiMessage.hasNoRenderableContent(): Boolean =
    role == "assistant" &&
        !isReasoning &&
        !isError &&
        !isPending &&
        content.isBlank() &&
        toolCalls.isNullOrEmpty() &&
        hasNoStructuredPayload()

private fun UiMessage.hasNoStructuredPayload(): Boolean =
    generatedUi == null &&
        approvalRequest == null &&
        approvalResponse == null &&
        subagentNotification == null &&
        attachments.isEmpty() &&
        agentMessageProvenance == null

/** Drops [hasNoRenderableContent] segments; returns the same list when there are none. */
fun List<UiMessage>.withoutContentlessSegments(): List<UiMessage> =
    if (none { it.hasNoRenderableContent() }) this else filterNot { it.hasNoRenderableContent() }
