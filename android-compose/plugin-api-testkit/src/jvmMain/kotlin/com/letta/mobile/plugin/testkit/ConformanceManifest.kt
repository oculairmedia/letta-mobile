package com.letta.mobile.plugin.testkit

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The parts of a plugin's `letta-plugin.json` the conformance kit holds a plugin to: its id,
 * capabilities, secrets, network origins, settings defaults, element kinds and actions. It reads
 * the manifest leniently (other fields are ignored); the host's own strict parser is the judge of
 * the manifest itself.
 */
@Serializable
public data class ConformanceManifest(
    public val id: String,
    public val capabilities: List<String> = emptyList(),
    public val secrets: List<Secret> = emptyList(),
    public val net: Net = Net(),
    public val settings: Map<String, JsonObject> = emptyMap(),
    public val elements: Map<String, Kind> = emptyMap(),
    public val actions: Map<String, Action> = emptyMap(),
) {
    /** A declared secret. */
    @Serializable
    public data class Secret(public val name: String)

    /** The declared network origins. */
    @Serializable
    public data class Net(public val connect: List<String> = emptyList())

    /** One element kind at its current [schemaVersion], with its [props] schema. */
    @Serializable
    public data class Kind(public val schemaVersion: Int, public val props: JsonObject)

    /** One action with its [input] schema. */
    @Serializable
    public data class Action(public val input: JsonObject)

    /** Whether the manifest asks for [capability]. */
    public fun has(capability: ConformanceCapability): Boolean = capability.wire in capabilities

    /** This manifest without [capability], for testing that a plugin copes with its absence. */
    public fun without(capability: ConformanceCapability): ConformanceManifest = copy(capabilities = capabilities - capability.wire)

    /** The settings a host resolves with no owner input: every declared `default`. */
    public fun defaultSettings(): JsonObject = JsonObject(
        settings.mapNotNull { (name, field) -> field["default"]?.let { name to it } }.toMap<String, JsonElement>(),
    )

    public companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** The manifest in [text] (the content of `letta-plugin.json`). */
        public fun parse(text: String): ConformanceManifest = json.decodeFromString(serializer(), text)
    }
}

/** The install-time capabilities the kit checks (plan section 3.3), by their manifest spelling [wire]. */
public enum class ConformanceCapability(public val wire: String) {
    CANVAS_PLACE("canvas:place"),
    CANVAS_READ("canvas:read"),
    ASSETS_WRITE("assets:write"),
    NET_CONNECT("net:connect"),
    ;

    override fun toString(): String = wire
}
