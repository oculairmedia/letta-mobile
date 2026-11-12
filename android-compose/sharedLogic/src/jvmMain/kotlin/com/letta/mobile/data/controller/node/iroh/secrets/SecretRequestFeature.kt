package com.letta.mobile.data.controller.node.iroh.secrets

import com.letta.mobile.data.controller.extras.HostExternalTool
import com.letta.mobile.data.controller.node.iroh.AdminRpcRouter
import com.letta.mobile.data.controller.node.iroh.AdminRpcRoutes
import com.letta.mobile.data.controller.node.iroh.adminError
import com.letta.mobile.data.controller.node.iroh.auth.IrohAuthPolicy
import com.letta.mobile.data.secrets.host.SecretRequestService
import com.letta.mobile.data.secrets.host.SecretRequestSettings
import com.letta.mobile.data.secrets.journal.SecretRequestJournal
import com.letta.mobile.data.secrets.wire.SecretRequestRouteWire
import com.letta.mobile.plugin.keystore.KeystoreProvider
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.attr
import kotlinx.serialization.json.JsonElement

/**
 * The secure secret-input flow on a JVM host (`docs/reference/contracts/secret-request-v1.md`,
 * letta-mobile-n2stx.2.4): the admin RPC [routes] for the person's device and the `secret_request`
 * tool for agents ([hostTools]), both over one [SecretRequestService].
 *
 * Built only by [forHost], which answers null (the host registers, advertises and offers nothing)
 * unless the endpoint's auth policy is a secure one and a keystore provider is configured. The class
 * lives in `jvmMain` because the keystore SPI does (owner decision 8): no other host can build it.
 */
class SecretRequestFeature internal constructor(policy: IrohAuthPolicy, internal val handlers: SecretRequestHandlers) {
    /** The six routes; pass to [com.letta.mobile.data.controller.node.iroh.AdminRpcHostServices.secretRequestRoutes]. */
    val routes: AdminRpcRoutes = SecretRequestRoutes(policy, handlers)

    /** The agent-facing tool; offer it with the host's other tools, exactly when [routes] is registered. */
    val hostTools: List<HostExternalTool> = listOf(SecretRequestTool(handlers))

    /** The gated factory. */
    companion object {
        /**
         * The feature for a host whose endpoint enforces [policy], writing through [keystore] and
         * recording requests in [journal]; null under the anonymous test policy or without a keystore.
         */
        fun forHost(
            policy: IrohAuthPolicy,
            keystore: KeystoreProvider?,
            journal: SecretRequestJournal,
            settings: SecretRequestSettings = SecretRequestSettings(),
        ): SecretRequestFeature? {
            if (keystore == null || !policy.admitsSecretRequests()) {
                val reason = if (keystore == null) "no_keystore" else "insecure_policy"
                Telemetry.event(SECRET_ROUTE_TAG, "feature.withheld", "reason" attr reason)
                return null
            }
            return SecretRequestFeature(policy, SecretRequestHandlers(SecretRequestService(journal, keystore, settings)))
        }
    }
}

/**
 * Installs all six routes and advertises [SecretRequestRouteWire.CAPABILITY] under a secure
 * [policy]; under the anonymous policy, nothing. Each call's principal comes from the request
 * context the serving connection built ([SecretRequestCaller.Connection]); a refusal is a fixed
 * code through [adminError]. No route logs its params or result.
 */
internal class SecretRequestRoutes(
    private val policy: IrohAuthPolicy,
    private val handlers: SecretRequestHandlers,
) : AdminRpcRoutes {
    override fun register(router: AdminRpcRouter) {
        if (!policy.admitsSecretRequests()) {
            Telemetry.event(SECRET_ROUTE_TAG, "routes.withheld", "reason" attr "insecure_policy")
            return
        }
        SecretRequestRoute.entries.forEach { route ->
            router.registerScoped(route.method) { params, context ->
                handlers.handle(route, params, SecretRequestCaller.Connection(context), VIA_ADMIN_RPC).toAdminResult()
            }
        }
        router.featureCapabilities += SecretRequestRouteWire.CAPABILITY
        Telemetry.event(SECRET_ROUTE_TAG, "routes.registered")
    }

    private companion object {
        const val VIA_ADMIN_RPC = "admin_rpc"
    }
}

/** The admin RPC result, or the refusal as the router's failure envelope (`{"success": false, "error": <code>}`). */
private fun SecretRouteAnswer.toAdminResult(): JsonElement = when (this) {
    is SecretRouteAnswer.Ok -> result
    is SecretRouteAnswer.Refused -> adminError(text)
}

/** Exhaustive on purpose: a new policy kind must decide here whether it may serve secret requests. */
internal fun IrohAuthPolicy.admitsSecretRequests(): Boolean = when (this) {
    is IrohAuthPolicy.BearerToken -> true
    is IrohAuthPolicy.PeerAllowlist -> true
    IrohAuthPolicy.PairedPeersOnly -> true
    IrohAuthPolicy.InsecureAnonymousForTestOnly -> false
}
