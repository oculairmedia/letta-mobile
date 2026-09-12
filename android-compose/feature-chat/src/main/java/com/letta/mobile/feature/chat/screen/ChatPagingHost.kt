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
    // Installed only alongside the canonical writer, after the host's dev-only gate passes.
    var openCanonical: (suspend (String, String, String?, kotlinx.coroutines.CoroutineScope) -> ChatPagingPresentation)? = null

    fun bindCanonical(
        coordinator: com.letta.mobile.data.timeline.CanonicalTimelineCoordinator,
        resolveOwner: suspend (String, String) -> com.letta.mobile.data.timeline.CanonicalTimelineCoordinator.Owner,
    ) {
        openCanonical = { agent, conversation, target, uiScope ->
            createCanonicalChatPagingPresentation(coordinator, resolveOwner(agent, conversation), uiScope, target)
        }
    }

    // Legacy test seam; never used to activate a canonical route.
    var select: ((String, String, String?, Long) -> ChatPagingPresentation)? = null

    /** Presentation disposal releases only its viewport lease, never the conversation writer. */
    suspend fun attachCanonicalPresentation(
        coordinator: com.letta.mobile.data.timeline.CanonicalTimelineCoordinator,
        owner: com.letta.mobile.data.timeline.CanonicalTimelineCoordinator.Owner,
        target: com.letta.mobile.data.timeline.TimelineMessageId?,
        create: (com.letta.mobile.data.timeline.CanonicalTimelineCoordinator.Presentation) -> ChatPagingPresentation,
        consume: suspend (ChatPagingPresentation) -> Unit,
    ): Boolean {
        val lease = coordinator.attach(owner, target) ?: return false
        try {
            val presentation = create(lease)
            try {
                consume(presentation)
            } finally {
                presentation.close()
            }
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                coordinator.detach(lease)
            }
        }
        return true
    }
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
    val opening: Boolean = false,
    val openError: String? = null,
    val retryOpen: () -> Unit = {},
    /**
     * The field is the caller's, not the reader's: which string a stored body should show is
     * discovered per row by [com.letta.mobile.data.timeline.resolveDeferredBody].
     */
    val deferredReader: (ChatRenderItem) -> DeferredBodyRead? = { null },
) {
    internal var viewport: ChatPagingViewport? = null
    internal var saveViewport: (ChatPagingViewport) -> Unit = {}
    internal var clearViewport: () -> Unit = {}
    internal var requestTail: () -> Unit = {}

    /**
     * A canonical presentation always has a bound route, so `hasBoundRoute` cannot decide whether
     * reaching the tail needs a new generation. Only a presentation anchored at a specific target
     * does; one already tailing just scrolls. Rebuilding it instead retires the presentation, which
     * blanks the list behind the opening placeholder and resets the resident set the live overlay
     * subtracts against, double-rendering every settled row.
     */
    internal val isAnchoredAwayFromTail: Boolean get() = hasBoundRoute && routeTarget != null
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
        val route = effectiveRoute(routeTarget)
        // A new search route needs a fresh Paging collection even in the same generation.
        if (needsFreshCollection(routeTarget)) close()
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

    fun effectiveRoute(routeTarget: String?): String? = routeTarget?.takeUnless { it in consumedRoutes }

    fun needsFreshCollection(routeTarget: String?, currentRouteTarget: String? = presentation?.routeTarget): Boolean {
        val route = effectiveRoute(routeTarget)
        return route != null && currentRouteTarget != route
    }

    fun rememberRoute(route: String?) {
        if (route != null) consumedRoutes += route
    }

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
