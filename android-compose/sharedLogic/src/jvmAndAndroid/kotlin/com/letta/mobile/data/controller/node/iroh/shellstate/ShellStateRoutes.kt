package com.letta.mobile.data.controller.node.iroh.shellstate

import com.letta.mobile.data.controller.node.iroh.AdminRpcRequestContext
import com.letta.mobile.data.controller.node.iroh.AdminRpcRouter
import com.letta.mobile.data.controller.node.iroh.AdminRpcRoutes
import com.letta.mobile.data.controller.node.iroh.AgentScopeGuard
import com.letta.mobile.data.hoststate.HostStateFileLocks
import com.letta.mobile.data.controller.node.iroh.adminError
import com.letta.mobile.data.serialization.LettaJson
import com.letta.mobile.data.shellstate.ShellStateActor
import com.letta.mobile.data.shellstate.ShellStateSnapshot
import com.letta.mobile.data.shellstate.wire.ShellStateChange
import com.letta.mobile.data.shellstate.wire.ShellStateWire
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.attr
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The shell-state v1 routes: the host keeps one versioned document of shell and user state for every
 * paired device and pushes each accepted change to all of them. Register [forHost] on the host's built
 * admin router; it registers `shell.state.get` and `shell.state.propose` and advertises
 * `shell_state_v1`. A host that never stored a document answers an empty one at revision 0. The
 * transport's capability matrix gates each method before it gets here.
 */
object ShellStateRoutes {
    /** The routes for a host keeping its document in `shell-state.json` in [stateDirectory], pushing through [publisher]. */
    fun forHost(stateDirectory: Path, publisher: ShellStatePublisher): AdminRpcRoutes {
        val file = stateDirectory.resolve(STATE_FILE)
        return ShellStateRouteSet(ShellStateStore(FileShellStatePersistence(file), HostStateFileLocks.forFile(file)), publisher)
    }

    /**
     * The routes of the on-device runtime host, whose in-process router serves this device alone: the
     * document lives in `host-state/` inside the runtime's [runtimeStorage], with the runtime's own state, and
     * no push is needed with one connection. Same contract and same client as a server host.
     */
    fun forOnDeviceHost(runtimeStorage: Path, scope: CoroutineScope): AdminRpcRoutes =
        forHost(runtimeStorage.resolve(ON_DEVICE_STATE_DIR), ShellStatePublisher(scope))

    private const val STATE_FILE = "shell-state.json"
    private const val ON_DEVICE_STATE_DIR = "host-state"
}

/**
 * Registers the two methods. A [ShellStateRefusal] answers exactly its code; any other failure is the
 * router's fixed internal error. Agent-scoped peers are refused every method. Over `admin_rpc` the actor
 * of a change is always the person: the host records the serving connection's authenticated endpoint as
 * the client id, never a value the caller sent. Telemetry carries the method, the outcome, the revision
 * and counts: never a key or a value.
 */
internal class ShellStateRouteSet(
    private val store: ShellStateStore,
    private val publisher: ShellStatePublisher,
) : AdminRpcRoutes {
    override fun register(router: AdminRpcRouter) {
        router.shellState(ShellStateWire.GET) { _, _ -> ShellStateWrite(store.snapshot(), changed = false) }
        router.shellState(ShellStateWire.PROPOSE) { params, context ->
            store.propose(ShellStateParams.changes(params), actorOf(context))
        }
        router.featureCapabilities += ShellStateWire.CAPABILITY
    }

    private fun actorOf(context: AdminRpcRequestContext): ShellStateActor =
        ShellStateActor.User(context.authenticatedEndpointId ?: ShellStateWire.LOCAL_CLIENT_ID)

    private fun AdminRpcRouter.shellState(
        method: String,
        handler: suspend (JsonObject?, AdminRpcRequestContext) -> ShellStateWrite,
    ) = registerScoped(method) { params, context ->
        // The document spans the whole host, so an agent-scoped peer may neither read nor change it.
        if (context.authorizedAgentIds != null) {
            answered(method, SCOPED, null)
            adminError(AgentScopeGuard.DENIED)
        }
        try {
            val write = handler(params, context)
            if (write.changed) publisher.publish(write.snapshot)
            answered(method, SUCCESS, write.snapshot)
            LettaJson.persisted.encodeToJsonElement(ShellStateSnapshot.serializer(), write.snapshot)
        } catch (refusal: ShellStateRefusal) {
            answered(method, refusal.code, null)
            adminError(refusal.code)
        }
    }

    private fun answered(method: String, outcome: String, snapshot: ShellStateSnapshot?) {
        Telemetry.event(
            TAG, "request.answered", "method" attr method, "outcome" attr outcome,
            "revision" attr snapshot?.documentRevision, "count" attr snapshot?.entries?.size,
        )
    }

    private companion object {
        const val TAG = "ShellStateHost"
        const val SUCCESS = "success"
        const val SCOPED = "agent_scoped"
    }
}

/**
 * Reads and checks the params of `shell.state.propose`; anything malformed (a key outside [ShellStateWire.KEY_NAMESPACES]
 * included) is an invalid request, anything oversized too large.
 */
internal object ShellStateParams {
    /** The changes of [params]; refuses a missing, empty or overlong list, a repeated key, or a bad change. */
    fun changes(params: JsonObject?): List<ShellStateChange> {
        val array = params?.get(ShellStateWire.CHANGES) as? JsonArray ?: invalid()
        if (array.isEmpty() || array.size > ShellStateWire.MAX_CHANGES) invalid()
        val changes = array.map(::change)
        if (changes.map { it.key }.toSet().size != changes.size) invalid()
        return changes
    }

    private fun change(element: JsonElement): ShellStateChange {
        val fields = element as? JsonObject ?: invalid()
        val value = fields[ShellStateWire.VALUE]?.takeUnless { it is JsonNull }
        if (value != null && value.toString().toByteArray(Charsets.UTF_8).size > ShellStateWire.MAX_VALUE_BYTES) {
            throw ShellStateRefusal(ShellStateWire.TOO_LARGE)
        }
        return ShellStateChange(key(fields), value, expectedRevision(fields))
    }

    private fun key(fields: JsonObject): String {
        val primitive = fields[ShellStateWire.KEY] as? JsonPrimitive
        val text = primitive?.takeIf { it.isString }?.content.orEmpty()
        if (text.length > ShellStateWire.MAX_KEY_CHARS || !ShellStateWire.KEY_PATTERN.matches(text)) invalid()
        // A chat-role device may only write the namespaces of the areas that live on the document.
        if (!ShellStateWire.isAllowedKey(text)) invalid()
        return text
    }

    private fun expectedRevision(fields: JsonObject): Long {
        val value = (fields[ShellStateWire.EXPECTED_REVISION] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        return value?.takeIf { it >= 0 } ?: invalid()
    }

    private fun invalid(): Nothing = throw ShellStateRefusal(ShellStateWire.INVALID_REQUEST)
}
