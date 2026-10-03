package com.letta.mobile.plugin.testkit

import com.letta.mobile.plugin.api.ActionCall
import com.letta.mobile.plugin.api.ActionResult
import com.letta.mobile.plugin.api.CanvasPlugin
import com.letta.mobile.plugin.api.ElementEvent
import com.letta.mobile.plugin.api.ElementEventType
import com.letta.mobile.plugin.api.ElementFallback
import com.letta.mobile.plugin.api.LogLevel
import com.letta.mobile.plugin.api.PlaceElement
import com.letta.mobile.plugin.api.PluginEmit
import com.letta.mobile.plugin.api.PluginHealth
import com.letta.mobile.plugin.api.PluginHost
import com.letta.mobile.plugin.api.PluginInfo
import com.letta.mobile.plugin.api.SnapshotSource
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A trivial plugin that keeps the contract (the in-repo fixture until the reference plugin, bead
 * .34): `place` checks its service with the API token in a header, stores a snapshot and places a
 * `card`; `echo` answers its text or the greeting setting. The broken variants in
 * [PluginConformanceTest] each override one member to break one rule.
 */
open class SamplePlugin : CanvasPlugin {
    protected lateinit var host: PluginHost

    override fun initialize(host: PluginHost): PluginInfo {
        this.host = host
        return PluginInfo(build = "sample-test")
    }

    override fun activate() {
        host.log(LogLevel.INFO, "activated", mapOf("greeting" to greeting()))
    }

    override suspend fun invoke(call: ActionCall): ActionResult = when (call.action) {
        "place" -> place(call)
        "echo" -> echo(call)
        else -> unknown(call)
    }

    protected open suspend fun place(call: ActionCall): ActionResult {
        host.httpClient.get("$SERVICE/status", mapOf("Authorization" to "Bearer ${host.secret("apiToken")}"))
        val card = Card(label = call.input.getValue("label").jsonPrimitive.content, snapshotRef = host.putAsset("image/png", byteArrayOf(1, 2, 3)))
        return ActionResult.Ok("Placed ${card.label}", emit = PluginEmit(place = listOf(card(card))))
    }

    /** What `place` puts on the board. */
    data class Card(val label: String, val snapshotRef: String)

    protected open fun card(card: Card): PlaceElement = PlaceElement(
        kind = "card",
        v = 2,
        props = buildJsonObject {
            put("label", JsonPrimitive(card.label))
            put("status", JsonPrimitive("idle"))
        },
        fallback = ElementFallback(title = card.label),
        snapshot = SnapshotSource.Asset(card.snapshotRef),
    )

    protected open suspend fun echo(call: ActionCall): ActionResult =
        ActionResult.Ok(call.input["text"]?.jsonPrimitive?.content ?: greeting())

    protected open fun unknown(call: ActionCall): ActionResult =
        ActionResult.Error(ActionResult.Error.UNKNOWN_ACTION, "Sample has no action '${call.action}'")

    override suspend fun onElementEvent(event: ElementEvent) {
        if (event.event == ElementEventType.REMOVED) host.log(LogLevel.INFO, "card removed", mapOf("elementId" to event.elementId))
    }

    override fun health(): PluginHealth = PluginHealth.Ok

    override fun deactivate() = Unit

    private fun greeting(): String = host.settings["greeting"]?.jsonPrimitive?.content ?: "hi"

    companion object {
        const val SERVICE = "https://api.sample.test"

        val manifestText: String =
            requireNotNull(SamplePlugin::class.java.getResource("/sample/letta-plugin.json")).readText()

        val manifest: ConformanceManifest = ConformanceManifest.parse(manifestText)
    }
}
