package com.letta.mobile.data.repository

import com.letta.mobile.data.backend.RemoteRoute
import com.letta.mobile.data.model.McpServer
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.repository.api.IMcpServerRepository
import com.letta.mobile.data.repository.api.McpServerIrohSource
import com.letta.mobile.data.repository.api.McpServerRemoteSource
import com.letta.mobile.util.runCatchingCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Phase 5l: platform-neutral cached MCP server repository. */
open class CachedMcpServerRepository(
    private val remote: RemoteRoute<McpServerRemoteSource>,
    private val irohMcpSource: McpServerIrohSource? = null,
) : IMcpServerRepository {
    private val _servers = MutableStateFlow<List<McpServer>>(emptyList())
    override val servers: StateFlow<List<McpServer>> = _servers.asStateFlow()

    override suspend fun refreshServers() {
        val irohSource = irohMcpSource
        if (irohSource != null && irohSource.shouldUseIroh()) {
            _servers.update { irohSource.listMcpServers() }
            return
        }
        _servers.update { remote.source().listMcpServers() }
    }

    override suspend fun fetchAllMcpTools(): List<Tool> {
        if (irohMcpSource?.shouldUseIroh() == true) {
            refreshServers()
            return emptyList()
        }
        refreshServers()
        return _servers.value.flatMap { server ->
            runCatchingCancellable {
                remote.source().listMcpServerTools(server.id.value)
            }.getOrElse { emptyList() }
        }
    }
}
