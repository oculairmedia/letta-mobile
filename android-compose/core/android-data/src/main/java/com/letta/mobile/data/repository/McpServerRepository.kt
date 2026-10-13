package com.letta.mobile.data.repository

import com.letta.mobile.data.backend.noAppServerRoute
import com.letta.mobile.data.repository.api.McpServerRemoteSource

/**
 * Android binding for [CachedMcpServerRepository]. MCP servers over an Iroh host's `admin_rpc`.
 *
 * letta-mobile-ckmub.4: [remote] is the seam for a non-App-Server backend; production binds no
 * route (the Letta REST backend was removed).
 */
open class McpServerRepository(
    remote: McpServerRemoteSource = noAppServerRoute(),
    irohMcpSource: IrohAdminRpcMcpSource? = null,
) : CachedMcpServerRepository(
    remote = remote,
    irohMcpSource = irohMcpSource,
)
