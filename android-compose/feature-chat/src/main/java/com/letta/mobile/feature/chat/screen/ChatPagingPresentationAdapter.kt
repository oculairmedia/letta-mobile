package com.letta.mobile.feature.chat.screen

import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.timeline.CanonicalTimelineCoordinator
import com.letta.mobile.data.timeline.CanonicalTimelinePresentation
import com.letta.mobile.data.timeline.TimelineMessageId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Called on the UI dispatcher; the application installs this only after canonical writer gating. */
internal suspend fun createCanonicalChatPagingPresentation(
    coordinator: CanonicalTimelineCoordinator,
    owner: CanonicalTimelineCoordinator.Owner,
    uiScope: CoroutineScope,
    target: String?,
    decodeDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default,
): ChatPagingPresentation {
    val canonical = CanonicalTimelinePresentation.open(coordinator, owner, uiScope, target?.let(::TimelineMessageId))
    val job = SupervisorJob(uiScope.coroutineContext[Job])
    val scope = CoroutineScope(uiScope.coroutineContext + job)
    // Bounded metadata only. Evicted keys cannot acknowledge settlement until seen again.
    val rows = java.util.IdentityHashMap<ChatRenderItem, CanonicalTimelinePresentation.Row>()
    val order = java.util.ArrayDeque<ChatRenderItem>()
    return ChatPagingPresentation(
        settled = canonical.settled.map { page ->
            page.map { row ->
                synchronized(rows) {
                    if (!rows.containsKey(row.item)) order.addLast(row.item)
                    rows[row.item] = row
                    while (rows.size > 128) rows.remove(order.removeFirst())
                }
                row.item
            }
        }.cachedIn(scope),
        deferredReader = { item ->
            val row = synchronized(rows) { rows[item] }
            if (row?.deferred == null) null else {
                val read: suspend (Long) -> com.letta.mobile.data.timeline.TimelineSemanticWindowResult = { offset ->
                    canonical.readTextWindow(row, com.letta.mobile.data.timeline.TimelineSemanticField.Content, offset, decodeDispatcher)
                }
                read
            }
        },
        live = canonical.live,
        missingTarget = MutableStateFlow(canonical.missingTarget),
        onResidentRows = { resident -> canonical.onResidentRows(synchronized(rows) { resident.mapNotNull { rows[it] } }) },
        close = {
            job.cancel()
            synchronized(rows) { rows.clear(); order.clear() }
            // Cleanup must survive UI scope cancellation; canonical.close only detaches the viewport.
            CoroutineScope(uiScope.coroutineContext + kotlinx.coroutines.NonCancellable).launch { canonical.close() }
        },
    )
}
