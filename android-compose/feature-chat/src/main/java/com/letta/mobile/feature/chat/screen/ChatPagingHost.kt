package com.letta.mobile.feature.chat.screen

import com.letta.mobile.data.timeline.CanonicalTimelineCoordinator
import com.letta.mobile.data.timeline.CanonicalTimelinePresentation
import com.letta.mobile.data.timeline.TimelineMessageId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import javax.inject.Inject

/** Android injection boundary only; selection, ingestion and fencing belong to the engine. */
@javax.inject.Singleton
class ChatPagingHost @Inject constructor() {
    // Installed only alongside the canonical writer, after the host's dev-only gate passes.
    var openCanonical: (suspend (String, String, String?, CoroutineScope) -> ChatTimelinePresentation)? = null

    fun bindCanonical(
        coordinator: CanonicalTimelineCoordinator,
        resolveOwner: suspend (String, String) -> CanonicalTimelineCoordinator.Owner,
    ) {
        openCanonical = { agent, conversation, target, uiScope ->
            openCanonicalTimeline(coordinator, resolveOwner(agent, conversation), uiScope, target)
        }
    }
}

/**
 * letta-mobile-bglj6.1: an opened conversation timeline as the chat page reads it, with its
 * release. [timeline] is the canonical presentation the shared chat page pages through; closing
 * releases only its viewport lease, never the conversation writer.
 */
class ChatTimelinePresentation(
    val timeline: CanonicalTimelinePresentation?,
    val close: () -> Unit,
)

/** Called on the UI dispatcher; the application installs this only after canonical writer gating. */
internal suspend fun openCanonicalTimeline(
    coordinator: CanonicalTimelineCoordinator,
    owner: CanonicalTimelineCoordinator.Owner,
    uiScope: CoroutineScope,
    target: String?,
): ChatTimelinePresentation {
    val canonical = CanonicalTimelinePresentation.open(coordinator, owner, uiScope, target?.let(::TimelineMessageId))
    return ChatTimelinePresentation(
        timeline = canonical,
        // Cleanup must survive UI scope cancellation; canonical.close only detaches the viewport.
        close = { (uiScope + NonCancellable).launch { canonical.close() } },
    )
}
