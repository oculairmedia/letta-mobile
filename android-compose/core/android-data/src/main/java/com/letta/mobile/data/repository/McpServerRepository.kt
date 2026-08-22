package com.letta.mobile.data.repository

import com.letta.mobile.data.api.McpServerApi

/** Android binding for [CachedMcpServerRepository]. Phase 5l. */
open class McpServerRepository(
    mcpServerApi: McpServerApi,
    irohMcpSource: IrohAdminRpcMcpSource? = null,
) : CachedMcpServerRepository(
    remote = mcpServerApi,
    irohMcpSource = irohMcpSource,
)
