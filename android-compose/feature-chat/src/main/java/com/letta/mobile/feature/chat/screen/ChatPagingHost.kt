package com.letta.mobile.feature.chat.screen

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.paging.PagingData
import com.letta.mobile.data.chat.projection.ChatRenderItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** Android injection boundary only; selection, ingestion and fencing belong to the engine. */
@javax.inject.Singleton
class ChatPagingHost @Inject constructor() {
    // Disabled until the host supplies an engine-backed selection and ingest binding.
    var select: ((String, String, String?) -> ChatPagingPresentation)? = null
}

class ChatPagingPresentation(
    val settled: Flow<PagingData<ChatRenderItem>>,
    val live: StateFlow<List<ChatRenderItem>>,
    val close: () -> Unit,
    // Only the engine can declare absence after exact-target lookup completes.
    val missingTarget: StateFlow<String?> = kotlinx.coroutines.flow.MutableStateFlow(null),
)

internal val LocalChatPagingPresentation = staticCompositionLocalOf<ChatPagingPresentation?> { null }
