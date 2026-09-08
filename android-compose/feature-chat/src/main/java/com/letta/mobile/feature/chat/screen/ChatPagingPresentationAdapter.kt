package com.letta.mobile.feature.chat.screen

import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.letta.mobile.data.chat.projection.ChatRenderItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Android Paging cache lifetime only. Call once per selected tail/search view with a fresh
 * Pager flow. The caller owns lookup/fallback and supplies live and resident callbacks from
 * the same canonical session. Revision mapping and settlement remain in sharedLogic.
 *
 * Create, report resident rows and close on the UI dispatcher. No ingestion lease or runtime
 * close callback is accepted: detaching this presentation must not stop background ingestion.
 * Not installed in ChatPagingHost until the writer and body contracts are approved.
 */
internal fun createChatPagingPresentation(
    uiScope: CoroutineScope,
    settled: Flow<PagingData<ChatRenderItem>>,
    live: StateFlow<List<ChatRenderItem>>,
    missingTarget: StateFlow<String?>,
    onResidentRows: (List<ChatRenderItem>) -> Unit,
): ChatPagingPresentation {
    val presentationJob = SupervisorJob(uiScope.coroutineContext[Job])
    val pagingScope = CoroutineScope(uiScope.coroutineContext + presentationJob)
    return ChatPagingPresentation(
        settled = settled.cachedIn(pagingScope),
        live = live,
        missingTarget = missingTarget,
        close = { presentationJob.cancel() },
        onResidentRows = { rows ->
            if (presentationJob.isActive) onResidentRows(rows)
        },
    )
}
