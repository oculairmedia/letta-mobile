package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.AppServerController

/** Host integration services used by admin routes; missing optional services keep their routes unavailable. */
class AdminRpcHostServices(
    /** Existing controller for runtime operations; null keeps controller-backed routes unavailable. */
    val controller: AppServerController? = null,
    /** Host-owned subagent registry; null omits subagent methods from the router. */
    val subagentRegistrySource: SubagentRegistrySource? = null,
    /** Product service for project methods; null keeps those methods capability-unavailable. */
    val vibesyncBaseUrl: String? = null,
    /** Host-specific plugin routes; null registers capability-unavailable responses for plugin methods. */
    val pluginRoutes: AdminRpcRoutes? = null,
)
