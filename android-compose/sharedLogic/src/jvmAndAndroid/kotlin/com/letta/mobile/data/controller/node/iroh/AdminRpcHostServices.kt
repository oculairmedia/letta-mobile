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
    /** Optional bd serve endpoint. Unset omits all beads methods. */
    val beadsBaseUrl: String? = null,
    /** Wrapper-only bearer token file for bd serve. */
    val beadsTokenFile: String? = null,
    /** Wrapper binding for the optional Beads routes. */
    val beadsRoutes: AdminRpcRoutes? = null,
    /** Host-specific plugin routes; null registers capability-unavailable responses for plugin methods. */
    val pluginRoutes: AdminRpcRoutes? = null,
    /**
     * The secret request routes; null registers and advertises none. Only the JVM host can
     * build them, and only with a paired-peers-only auth policy and a keystore provider; they register nothing otherwise.
     */
    val secretRequestRoutes: AdminRpcRoutes? = null,
)
