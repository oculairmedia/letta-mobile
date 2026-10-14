package com.letta.mobile.data.repository

import com.letta.mobile.data.backend.bound
import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.backend.RemoteRoute
import com.letta.mobile.data.repository.api.McpServerRemoteSource

/**
 * Android binding for [CachedMcpServerRepository]. MCP servers over an Iroh host's `admin_rpc`.
 *
 * letta-mobile-ckmub.4: [remote] is the seam for a non-App-Server backend; production binds no
 * route (the Letta REST backend was removed).
 */
open class McpServerRepository(
    remote: RemoteRoute<McpServerRemoteSource> = RemoteRoute.Absent(SessionRoute.MCP_SERVERS),
    irohMcpSource: IrohAdminRpcMcpSource? = null,
) : CachedMcpServerRepository(
    remote = remote,
    irohMcpSource = irohMcpSource,
) {
    /** A repository over a bound [remote] (tests bind a fake). */
    constructor(remote: McpServerRemoteSource, irohMcpSource: IrohAdminRpcMcpSource? = null) : this(remote.bound(), irohMcpSource)
}
