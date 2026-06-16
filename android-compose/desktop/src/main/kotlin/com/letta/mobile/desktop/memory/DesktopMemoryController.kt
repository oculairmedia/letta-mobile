package com.letta.mobile.desktop.memory

import com.letta.mobile.data.memory.MemoryParityAgentOption
import com.letta.mobile.data.memory.MemoryParityController
import com.letta.mobile.data.memory.MemoryParityControllerState
import com.letta.mobile.desktop.data.DesktopRepositoryUnavailableException
import com.letta.mobile.desktop.data.DesktopSessionGraphProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

typealias DesktopMemorySurfaceState = MemoryParityControllerState
typealias DesktopMemoryAgentOption = MemoryParityAgentOption

class DesktopMemoryController(
    sessionGraphProvider: DesktopSessionGraphProvider,
    scope: CoroutineScope,
) : AutoCloseable {
    private val delegate = MemoryParityController(
        sessionGraphProvider = sessionGraphProvider,
        scope = scope,
        errorMessageMapper = { throwable -> throwable.toDesktopMemoryMessage() },
    )
    val state: StateFlow<DesktopMemorySurfaceState> = delegate.state

    fun start() {
        delegate.start()
    }

    fun reload() {
        delegate.reload()
    }

    fun selectAgent(agentId: String) {
        delegate.selectAgent(agentId)
    }

    override fun close() {
        delegate.close()
    }

    private fun Throwable.toDesktopMemoryMessage(): String =
        when (this) {
            is DesktopRepositoryUnavailableException -> "Desktop memory repositories are not available for this backend yet."
            else -> message ?: this::class.simpleName ?: "Memory data could not be loaded."
        }
}
