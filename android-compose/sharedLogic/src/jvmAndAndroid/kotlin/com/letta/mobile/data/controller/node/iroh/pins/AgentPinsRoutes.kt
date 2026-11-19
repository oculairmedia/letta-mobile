package com.letta.mobile.data.controller.node.iroh.pins

import com.letta.mobile.data.controller.node.iroh.AdminRpcRouter
import com.letta.mobile.data.controller.node.iroh.AdminRpcRoutes
import com.letta.mobile.data.controller.node.iroh.AgentScopeGuard
import com.letta.mobile.data.controller.node.iroh.adminError
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.pins.shared.AgentPinsSnapshot
import com.letta.mobile.data.pins.shared.AgentPinsWire
import com.letta.mobile.data.serialization.LettaJson
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.attr
import java.nio.file.Path
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The pinned-agents v1 routes: the host keeps one ordered list of pinned agents for every paired device
 * and pushes each accepted change to all of them. Register [forHost] on the host's built admin router;
 * it registers `pins.get` and `pins.set` and advertises `pinned_agents_v1`. A host that never stored a
 * list answers an empty one at revision 0. The
 * transport's capability matrix gates each method before it gets here.
 */
object AgentPinsRoutes {
    /** The routes for a host keeping its list in `pinned-agents.json` in [stateDirectory], pushing through [publisher]. */
    fun forHost(stateDirectory: Path, publisher: AgentPinsPublisher): AdminRpcRoutes =
        AgentPinsRouteSet(AgentPinsStore(FileAgentPinsPersistence(stateDirectory.resolve(STATE_FILE))), publisher)

    private const val STATE_FILE = "pinned-agents.json"
}

/**
 * Registers the two methods. An [AgentPinsRefusal] answers exactly its code; any other failure is the
 * router's fixed internal error. Agent-scoped peers are refused every method. Telemetry carries the method, the outcome, the revision and a count:
 * never an agent name.
 */
internal class AgentPinsRouteSet(
    private val store: AgentPinsStore,
    private val publisher: AgentPinsPublisher,
) : AdminRpcRoutes {
    override fun register(router: AdminRpcRouter) {
        router.pins(AgentPinsWire.GET) { _ -> store.snapshot() }
        router.pins(AgentPinsWire.SET) { params ->
            val ids = AgentPinsParams.agentIds(params)
            store.set(ids, AgentPinsParams.expectedRevision(params)).published()
        }
        router.featureCapabilities += AgentPinsWire.CAPABILITY
    }

    private fun AgentPinsWrite.published(): AgentPinsSnapshot {
        if (changed) publisher.publish(snapshot)
        return snapshot
    }

    private fun AdminRpcRouter.pins(method: String, handler: suspend (JsonObject?) -> AgentPinsSnapshot) =
        registerScoped(method) { params, context ->
            // The list names agents across the whole host, so an agent-scoped peer may neither read nor
            // replace it.
            if (context.authorizedAgentIds != null) {
                answered(method, SCOPED, null)
                adminError(AgentScopeGuard.DENIED)
            }
            try {
                val snapshot = handler(params)
                answered(method, SUCCESS, snapshot)
                LettaJson.persisted.encodeToJsonElement(AgentPinsSnapshot.serializer(), snapshot)
            } catch (refusal: AgentPinsRefusal) {
                answered(method, refusal.code, null)
                adminError(refusal.code)
            }
        }

    private fun answered(method: String, outcome: String, snapshot: AgentPinsSnapshot?) {
        Telemetry.event(
            TAG, "request.answered", "method" attr method, "outcome" attr outcome,
            "revision" attr snapshot?.revision, "count" attr snapshot?.agentIds?.size,
        )
    }

    private companion object {
        const val TAG = "AgentPinsHost"
        const val SUCCESS = "success"
        const val SCOPED = "agent_scoped"
    }
}

/** Reads and checks the params of the pins methods; anything malformed is an invalid request. */
internal object AgentPinsParams {
    /** The ordered, de-duplicated agent ids of [params]; refuses a missing list, a blank or overlong id, or too many. */
    fun agentIds(params: JsonObject?): List<AgentId> {
        val array = params?.get(AgentPinsWire.AGENT_IDS) as? JsonArray ?: invalid()
        val ids = array.map { element -> agentId(element) }.distinct()
        if (ids.size > AgentPinsWire.MAX_PINS) invalid()
        return ids
    }

    /** The non-negative `expected_revision` of [params]. */
    fun expectedRevision(params: JsonObject?): Long {
        val value = (params?.get(AgentPinsWire.EXPECTED_REVISION) as? JsonPrimitive)
            ?.takeUnless { it.isString }?.longOrNull
        return value?.takeIf { it >= 0 } ?: invalid()
    }

    private fun agentId(element: JsonElement): AgentId {
        val primitive = element as? JsonPrimitive
        val text = primitive?.takeIf { it.isString }?.content?.trim().orEmpty()
        if (text.isEmpty() || text.length > AgentPinsWire.MAX_ID_CHARS) invalid()
        return AgentId(text)
    }

    private fun invalid(): Nothing = throw AgentPinsRefusal(AgentPinsWire.INVALID_REQUEST)
}
