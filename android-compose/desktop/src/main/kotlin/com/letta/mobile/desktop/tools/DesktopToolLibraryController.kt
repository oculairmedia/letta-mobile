package com.letta.mobile.desktop.tools

import com.letta.mobile.data.tools.ToolLibraryController
import com.letta.mobile.data.tools.ToolLibraryState
import com.letta.mobile.desktop.data.DesktopRepositoryUnavailableException
import com.letta.mobile.desktop.data.DesktopSessionGraphProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

typealias DesktopToolLibraryState = ToolLibraryState

class DesktopToolLibraryController(
    sessionGraphProvider: DesktopSessionGraphProvider,
    scope: CoroutineScope,
) : AutoCloseable {
    private val graph = sessionGraphProvider.current
    private val delegate = ToolLibraryController(
        toolRepository = graph.toolRepository,
        mcpServerRepository = graph.mcpServerRepository,
        scope = scope,
        errorMessageMapper = { throwable -> throwable.toDesktopToolsMessage() },
    )
    val state: StateFlow<DesktopToolLibraryState> = delegate.state

    fun start() {
        delegate.start()
    }

    fun reload() {
        delegate.loadTools()
    }

    fun loadMore() {
        delegate.loadMoreTools()
    }

    fun updateSearchQuery(query: String) {
        delegate.updateSearchQuery(query)
    }

    fun toggleTag(tag: String) {
        delegate.toggleTag(tag)
    }

    fun clearTags() {
        delegate.clearTags()
    }

    override fun close() {
        delegate.close()
    }

    private fun Throwable.toDesktopToolsMessage(): String =
        when (this) {
            is DesktopRepositoryUnavailableException -> "Desktop tool repositories are not available for this backend yet."
            else -> message ?: this::class.simpleName ?: "Tools could not be loaded."
        }
}
