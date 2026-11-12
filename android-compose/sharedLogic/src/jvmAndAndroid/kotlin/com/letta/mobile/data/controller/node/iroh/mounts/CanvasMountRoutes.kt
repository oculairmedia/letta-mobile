package com.letta.mobile.data.controller.node.iroh.mounts

import com.letta.mobile.data.canvas.mount.AuthorizedCanvasMounts
import com.letta.mobile.data.canvas.mount.CanvasMountAuthority
import com.letta.mobile.data.canvas.mount.wire.CanvasMountWire
import com.letta.mobile.data.controller.node.iroh.AdminRpcRouter
import com.letta.mobile.data.controller.node.iroh.AdminRpcRoutes
import com.letta.mobile.data.controller.node.iroh.auth.IrohAuthPolicy
import com.letta.mobile.data.model.HostId
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.attr
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope

/**
 * The authenticated canvas mount admin RPC routes (`docs/reference/contracts/canvas-mount-v1.md`).
 *
 * [register] installs all four [CanvasMountWire.METHODS] and advertises [CanvasMountWire.CAPABILITY] only when
 * [policy] is a secure one (bearer token, peer allowlist or paired peers). Under the anonymous test policy it
 * registers nothing and advertises nothing. [policy] must be the policy the serving endpoint enforces.
 *
 * Each call derives its principal from the connection's verified endpoint identity, parses params strictly,
 * and reaches mount storage only through [AuthorizedCanvasMounts] with a per-call host authority built from the
 * connection's own scopes, the host's canvas [CanvasMountGrants] and its [CanvasMountDirectory]. There is no
 * agent-callable surface and no listing (owner decisions O4, O5).
 */
class CanvasMountRoutes internal constructor(
    private val policy: IrohAuthPolicy,
    private val host: CanvasMountHost,
) : AdminRpcRoutes {
    /**
     * Production routes over the host's mount records at [location]. Calls run in the host-owned [scope], which the
     * host cancels when it stops; a call stuck past its deadline is cancelled there, never left holding a request.
     */
    constructor(
        policy: IrohAuthPolicy,
        location: CanvasMountStoreLocation,
        sources: CanvasMountSources,
        scope: CoroutineScope,
    ) : this(policy, CanvasMountHost(location::mounts, sources.grants, sources.directory, CanvasMountBounds.production(scope)))

    /** Installs all four routes and the capability under a secure policy; under the anonymous policy, nothing. */
    override fun register(router: AdminRpcRouter) {
        if (!policy.admitsCanvasMounts()) {
            Telemetry.event(CANVAS_MOUNT_TAG, "routes.withheld", "reason" attr "insecure_policy")
            return
        }
        val handlers = CanvasMountHandlers(host)
        CanvasMountRoute.entries.forEach { route ->
            router.registerScoped(route.method) { params, context -> handlers.handle(route, params, context) }
        }
        router.featureCapabilities += CanvasMountWire.CAPABILITY
        Telemetry.event(CANVAS_MOUNT_TAG, "routes.registered")
    }
}

/**
 * Where the host keeps its mount records: the existing protected product-state directory and this host's stable
 * id (records written under another host id fail closed as invalid state).
 */
class CanvasMountStoreLocation(
    /** The administrator-owned product-state directory; `canvas-mounts/` is created beneath it. */
    val productStateDirectory: Path,
    /** This host's stable identity, recorded in and checked against every record. */
    val hostId: HostId,
) {
    internal fun mounts(authority: CanvasMountAuthority): AuthorizedCanvasMounts =
        AuthorizedCanvasMounts(productStateDirectory, hostId, authority)
}

/** The host-owned records the mount authority decides from. */
class CanvasMountSources(
    /** Canvas grants keyed by principal and semantic canvas id. */
    val grants: CanvasMountGrants,
    /** The host's own conversation and agent records. */
    val directory: CanvasMountDirectory,
)

/** Exhaustive on purpose: a new policy kind must decide here whether it may serve mount routes. */
private fun IrohAuthPolicy.admitsCanvasMounts(): Boolean = when (this) {
    is IrohAuthPolicy.BearerToken -> true
    is IrohAuthPolicy.PeerAllowlist -> true
    IrohAuthPolicy.PairedPeersOnly -> true
    IrohAuthPolicy.InsecureAnonymousForTestOnly -> false
}
