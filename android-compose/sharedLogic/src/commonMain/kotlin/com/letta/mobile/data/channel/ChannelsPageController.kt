package com.letta.mobile.data.channel

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.session.SessionRepositoryGraph
import com.letta.mobile.data.session.SessionRepositoryGraphProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The port the shared Channels page reads from: the live channel library and a way to re-read it.
 * [ChannelLibraryController] is the session-graph implementation every host binds.
 */
interface ChannelLibrarySource : AutoCloseable {
    val state: StateFlow<ChannelLibraryState>

    fun start()

    fun refresh()
}

/** What the shared Channels page can ask of its controller (letta-mobile-c3np7.3.6). */
interface ChannelsPageActions {
    fun refresh()

    fun updateQuery(query: String)

    /** Null shows every channel. */
    fun selectStatusFilter(status: ChannelDisplayStatus?)

    /** Opens the channel's detail; hosts without a detail view simply never call it. */
    fun selectChannel(channelId: String)

    fun clearSelection()
}

/**
 * Everything the Channels page draws: the live [library], the user's search and status filter, the
 * [projection] those produce, and the channel whose detail is open.
 */
@Immutable
data class ChannelsPageState(
    val library: ChannelLibraryState = ChannelLibraryState(),
    val query: String = "",
    val statusFilter: ChannelDisplayStatus? = null,
    val selectedChannelId: String? = null,
    val projection: ChannelLibraryProjection = projectChannelLibrary(library.channels, statusFilter, query),
) {
    /** The open channel, or null when none is open or it has left the library. */
    val selectedChannel: ChannelDisplayItem?
        get() = selectedChannelId?.let { id -> library.channels.firstOrNull { it.id == id } }

    /** Why the list is empty, or null when it has channels to show. */
    val emptyReason: ChannelsEmptyReason?
        get() = when {
            library.channels.isEmpty() -> ChannelsEmptyReason.NoChannels
            projection.filteredChannels.isEmpty() -> ChannelsEmptyReason.NoMatches
            else -> null
        }

    /** True when any channel's data may be stale (reconnecting or disconnected). */
    val hasStaleChannels: Boolean
        get() = library.channels.any { it.status.isStale }
}

enum class ChannelsEmptyReason(val message: String) {
    NoChannels("No live channels are available for the active backend."),
    NoMatches("No channels match your filter."),
}

/** Pure transitions of [ChannelsPageState]; every one re-derives the projection. */
object ChannelsPageReducer {
    fun withLibrary(state: ChannelsPageState, library: ChannelLibraryState): ChannelsPageState =
        rebuild(state.copy(library = library))

    fun withQuery(state: ChannelsPageState, query: String): ChannelsPageState =
        if (state.query == query) state else rebuild(state.copy(query = query))

    fun withStatusFilter(state: ChannelsPageState, status: ChannelDisplayStatus?): ChannelsPageState =
        if (state.statusFilter == status) state else rebuild(state.copy(statusFilter = status))

    /** Selecting an id the library does not hold leaves the state as it was. */
    fun withSelection(state: ChannelsPageState, channelId: String?): ChannelsPageState = when {
        channelId == null -> state.copy(selectedChannelId = null)
        state.library.channels.none { it.id == channelId } -> state
        else -> state.copy(selectedChannelId = channelId)
    }

    private fun rebuild(state: ChannelsPageState): ChannelsPageState =
        state.copy(projection = projectChannelLibrary(state.library.channels, state.statusFilter, state.query))
}

/**
 * Platform-neutral controller behind the shared Channels page (desktop and Android): follows the
 * [source] and holds the page's search, status filter and open channel.
 */
class ChannelsPageController(
    private val source: ChannelLibrarySource,
    private val scope: CoroutineScope,
) : ChannelsPageActions, AutoCloseable {
    private val stateFlow = MutableStateFlow(ChannelsPageReducer.withLibrary(ChannelsPageState(), source.state.value))
    val state: StateFlow<ChannelsPageState> = stateFlow.asStateFlow()

    private var followJob: Job? = null

    fun start() {
        source.start()
        if (followJob != null) return
        followJob = scope.launch {
            source.state.collect { library -> stateFlow.update { ChannelsPageReducer.withLibrary(it, library) } }
        }
    }

    override fun refresh() {
        source.refresh()
    }

    override fun updateQuery(query: String) {
        stateFlow.update { ChannelsPageReducer.withQuery(it, query) }
    }

    override fun selectStatusFilter(status: ChannelDisplayStatus?) {
        stateFlow.update { ChannelsPageReducer.withStatusFilter(it, status) }
    }

    override fun selectChannel(channelId: String) {
        stateFlow.update { ChannelsPageReducer.withSelection(it, channelId) }
    }

    override fun clearSelection() {
        stateFlow.update { ChannelsPageReducer.withSelection(it, null) }
    }

    override fun close() {
        followJob?.cancel()
        followJob = null
        source.close()
    }

    companion object {
        /** Standard wiring: the session graph's live channel transport. */
        fun <Graph : SessionRepositoryGraph> forSession(
            sessionGraphProvider: SessionRepositoryGraphProvider<Graph>,
            scope: CoroutineScope,
        ): ChannelsPageController = ChannelsPageController(
            source = ChannelLibraryController(sessionGraphProvider = sessionGraphProvider, scope = scope),
            scope = scope,
        )
    }
}
