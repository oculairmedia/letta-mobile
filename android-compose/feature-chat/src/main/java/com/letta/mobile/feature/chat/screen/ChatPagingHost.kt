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
    var select: ((String, String, String?, Long) -> ChatPagingPresentation)? = null
}

class ChatPagingPresentation(
    val settled: Flow<PagingData<ChatRenderItem>>,
    val live: StateFlow<List<ChatRenderItem>>,
    val close: () -> Unit,
    // Only the engine can declare absence after exact-target lookup completes.
    val missingTarget: StateFlow<String?> = kotlinx.coroutines.flow.MutableStateFlow(null),
)

/** ViewModel-scoped resource binding; engine still owns all paging and generation policy. */
internal class ChatPagingBinding {
    private var selection: Pair<String, Long>? = null
    var presentation: ChatPagingPresentation? = null
        private set

    fun select(conversationId: String, generation: Long, create: () -> ChatPagingPresentation): ChatPagingPresentation {
        val next = conversationId to generation
        if (selection != next) {
            close()
            presentation = create()
            selection = next
        }
        return checkNotNull(presentation)
    }

    fun close() {
        val previous = presentation
        presentation = null
        selection = null
        previous?.close?.invoke()
    }
}

internal val LocalChatPagingPresentation = staticCompositionLocalOf<ChatPagingPresentation?> { null }
