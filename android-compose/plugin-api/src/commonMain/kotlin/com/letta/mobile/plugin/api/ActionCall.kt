package com.letta.mobile.plugin.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * One action to run (wire `action.invoke` parameters): the manifest's [action] name, its [input]
 * (already valid against the action's `input` schema) and who asked, in [context].
 */
@Serializable
public data class ActionCall(
    public val action: String,
    public val input: JsonObject,
    public val context: ActionContext,
)

/**
 * Where an [ActionCall] comes from: its [origin], the board it concerns ([canvasId], when there is
 * one) and the element it concerns ([elementId], when there is one).
 */
@Serializable
public data class ActionContext(
    public val origin: ActionOrigin,
    public val canvasId: String? = null,
    public val elementId: String? = null,
)

/** Who asked for an action; on the wire a `type`-tagged object (`agent`, `view` or `host`). */
@Serializable
public sealed interface ActionOrigin {
    /** An agent's tool call: the action became the agent tool `<idShort>_<action>`. */
    @Serializable
    @SerialName("agent")
    public data class Agent(
        public val agentId: String,
        public val conversationId: String? = null,
        public val toolCallId: String? = null,
    ) : ActionOrigin

    /** One of the plugin's own pages, open for [elementId] on the client [peerId]. */
    @Serializable
    @SerialName("view")
    public data class View(public val elementId: String, public val peerId: String? = null) : ActionOrigin

    /** The host itself, for [reason] (a refresh, a migration, a management call). */
    @Serializable
    @SerialName("host")
    public data class Host(public val reason: String) : ActionOrigin
}

/**
 * What an action answers (wire: the `action.invoke` result, or a JSON-RPC error whose `data.code`
 * is [Error.code]).
 */
@Serializable
public sealed interface ActionResult {
    /**
     * Success: [text] for the agent or page, optional [structured] data, and an optional [emit]
     * the host applies as if [PluginHost.emit] were called (its receipt is added to the answer).
     */
    @Serializable
    @SerialName("ok")
    public data class Ok(
        public val text: String,
        public val structured: JsonObject? = null,
        public val emit: PluginEmit? = null,
    ) : ActionResult

    /** Failure: a stable machine [code] (the constants below, or the plugin's own) and a [message] for people. */
    @Serializable
    @SerialName("error")
    public data class Error(public val code: String, public val message: String) : ActionResult {
        public companion object {
            /** The action is not one this plugin handles. */
            public const val UNKNOWN_ACTION: String = "unknown_action"

            /** The input is valid against the schema but not acceptable to the plugin. */
            public const val INVALID_INPUT: String = "invalid_input"

            /** Something the plugin depends on is not reachable or not configured. */
            public const val UNAVAILABLE: String = "unavailable"

            /** The plugin failed in a way it did not expect. */
            public const val INTERNAL: String = "internal"
        }
    }
}
