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

internal data class ChatPagingViewport(val messageId: String, val offset: Int, val following: Boolean = false)

class ChatPagingPresentation(
    val settled: Flow<PagingData<ChatRenderItem>>,
    val live: StateFlow<List<ChatRenderItem>>,
    val close: () -> Unit,
    // Only the engine can declare absence after exact-target lookup completes.
    val missingTarget: StateFlow<String?> = kotlinx.coroutines.flow.MutableStateFlow(null),
    // The host maps only actually resident rows to their durable revisions before settlement.
    val onResidentRows: (List<ChatRenderItem>) -> Unit = {},
) {
    internal var viewport: ChatPagingViewport? = null
    internal var saveViewport: (ChatPagingViewport) -> Unit = {}
    internal var clearViewport: () -> Unit = {}
    internal var requestTail: () -> Unit = {}
    internal var routeTarget: String? = null
    internal var hasBoundRoute = false
}

/** ViewModel-scoped resource binding; engine still owns all paging and generation policy. */
internal class ChatPagingBinding {
    private val viewports = mutableMapOf<String, ChatPagingViewport>()
    private val consumedRoutes = mutableSetOf<String>()

    fun selectRoute(
        conversationId: String,
        generation: Long,
        routeTarget: String?,
        publish: (ChatPagingPresentation) -> Unit,
        create: (String?) -> ChatPagingPresentation,
    ): ChatPagingPresentation {
        val route = routeTarget?.takeUnless { it in consumedRoutes }
        // A new search route needs a fresh Paging collection even in the same generation.
        if (route != null && presentation?.routeTarget != route) close()
        return select(conversationId, generation) {
            create(route ?: target(conversationId))
        }.also { current ->
            if (!current.hasBoundRoute) {
                current.hasBoundRoute = true
                current.routeTarget = route
                if (route != null) {
                    consumedRoutes += route
                    current.viewport = null
                }
            }
            current.requestTail = {
                if (presentation === current) {
                    current.clearViewport()
                    close()
                    val tail = selectRoute(conversationId, generation, null, publish, create)
                    publish(tail)
                }
            }
        }
    }
    fun target(conversationId: String): String? = viewports[conversationId]?.takeUnless { it.following }?.messageId
    private var selection: Pair<String, Long>? = null
    var presentation: ChatPagingPresentation? = null
        private set

    fun select(conversationId: String, generation: Long, create: () -> ChatPagingPresentation): ChatPagingPresentation {
        val next = conversationId to generation
        if (selection != next) {
            close()
            presentation = create().also { current ->
                current.viewport = viewports[conversationId]
                current.clearViewport = {
                    if (presentation === current) {
                        current.viewport = null
                        viewports.remove(conversationId)
                    }
                }
                current.saveViewport = { anchor ->
                    if (presentation === current) {
                        current.viewport = anchor
                        viewports[conversationId] = anchor
                    }
                }
            }
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
