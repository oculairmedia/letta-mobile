package com.letta.mobile.data.controller.node.iroh.servicecredentials

import com.letta.mobile.data.controller.node.iroh.AdminRpcRequestContext
import com.letta.mobile.data.controller.node.iroh.AdminRpcRouter
import com.letta.mobile.data.controller.node.iroh.AdminRpcRoutes
import com.letta.mobile.data.controller.node.iroh.adminError
import com.letta.mobile.data.servicecredentials.ServiceCredentialWire
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.attr
import io.ktor.client.engine.cio.CIO
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The service-credentials v1 routes (`docs/reference/contracts/service-credentials-v1.md`): the host
 * keeps the credentials of the non-LLM services the system calls, write-only over the wire. Register
 * [forHost] on the host's built admin router; it registers the four `service.credential.*` methods and
 * advertises `service_credentials_v1`. The transport's capability matrix gates each method first.
 */
object ServiceCredentialRoutes {
    /**
     * The routes for a host keeping its service state in [stateDirectory]: the settings in
     * `service-credentials.json` and the secrets in `service-credential-secrets`, a file only the
     * host's user may read. Serves TypeSafe Jev.
     */
    fun forHost(stateDirectory: Path, clock: () -> Long = System::currentTimeMillis): AdminRpcRoutes = ServiceCredentialRouteSet(
        ServiceCredentialStore(
            adapters = listOf(TypeSafeJevAdapter(CIO.create())),
            persistence = FileServiceCredentialPersistence(stateDirectory.resolve(SETTINGS_FILE)),
            secrets = FileServiceSecretStore(stateDirectory.resolve(SECRETS_FILE)),
        ),
        clock,
    )

    private const val SETTINGS_FILE = "service-credentials.json"
    private const val SECRETS_FILE = "service-credential-secrets"
}

/**
 * Registers the four methods. A [ServiceRefusal] answers exactly its code; any other failure is the
 * router's fixed internal error. Telemetry carries the method, the outcome code and the duration only:
 * never a secret, an address or any text.
 */
internal class ServiceCredentialRouteSet(store: ServiceCredentialStore, clock: () -> Long) : AdminRpcRoutes {
    private val handlers = ServiceCredentialHandlers(store, clock)

    override fun register(router: AdminRpcRouter) {
        router.service(ServiceCredentialWire.LIST) { _, context -> handlers.list(context) }
        router.service(ServiceCredentialWire.SET) { params, _ -> handlers.set(params) }
        router.service(ServiceCredentialWire.TEST) { params, _ -> handlers.test(params) }
        router.service(ServiceCredentialWire.REMOVE) { params, _ -> handlers.remove(params) }
        router.featureCapabilities += ServiceCredentialWire.CAPABILITY
    }

    private fun AdminRpcRouter.service(
        method: String,
        handler: suspend (JsonObject?, AdminRpcRequestContext) -> JsonElement,
    ) = registerScoped(method) { params, context ->
        val started = System.nanoTime()
        try {
            handler(params, context).also { answered(method, SUCCESS, started) }
        } catch (refusal: ServiceRefusal) {
            answered(method, refusal.code.wire, started)
            adminError(refusal.code.wire)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (expectedFailure: Exception) {
            answered(method, INTERNAL, started)
            throw expectedFailure
        }
    }

    private fun answered(method: String, outcome: String, startedNanos: Long) {
        val durationMs = (System.nanoTime() - startedNanos) / NANOS_PER_MILLI
        Telemetry.event(TAG, "request.answered", "method" attr method, "outcome" attr outcome, "durationMs" attr durationMs)
    }

    private companion object {
        const val TAG = "ServiceCredentials"
        const val SUCCESS = "success"
        const val INTERNAL = "internal"
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
