package com.letta.mobile.data.plugin.view

import com.letta.mobile.data.plugin.PluginCapability
import com.letta.mobile.data.plugin.PluginDisplayMode
import com.letta.mobile.data.schema.JsonSchemaCheck
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** What a platform host and the transport give a bridge. */
data class ViewBridgeServices(
    val host: ViewBridgeHost,
    val transport: PluginViewTransport,
    val links: ViewLinkPolicy = ViewLinkPolicy.DenyAll,
    val consent: PluginViewConsent = PluginViewConsent.DenyAll,
)

/** The answer to one view call: a result, or an error the bridge sends back. */
sealed interface ViewCallResult {
    data class Ok(val result: JsonElement) : ViewCallResult

    data class Err(val error: ViewRpcError) : ViewCallResult
}

/**
 * Carries out view calls the validator already shaped (plan section 7.2), against what the
 * manifest lets this page do: only its plugin's view actions with input that holds to their
 * schema, only its declared display modes, links only with `ui:openLink`, consent and the policy.
 */
internal class ViewCallHandler(private val spec: PluginViewSpec, private val services: ViewBridgeServices) {
    private val inputChecks: Map<String, JsonSchemaCheck> = spec.viewActions.mapValues { (_, schema) -> JsonSchemaCheck(schema) }

    /** `view.ready`: the page must be this view's page and speak a version this bridge knows. */
    fun ready(params: JsonObject): ViewCallResult {
        val pageId = params.text("pageId")
        val version = params.text("viewVersion")
        return when {
            pageId != spec.pageId -> forbidden("this view shows page '${spec.pageId}', not '$pageId'")
            version !in LcpView.SUPPORTED_VIEW_VERSIONS -> invalid("viewVersion '$version' is not supported; use ${LcpView.SUPPORTED_VIEW_VERSIONS.joinToString()}")
            else -> ViewCallResult.Ok(services.host.context().toJson())
        }
    }

    /** Every call after the handshake. */
    suspend fun handle(method: ViewMethod, params: JsonObject): ViewCallResult = when (method) {
        ViewMethod.READY -> ViewCallResult.Err(ViewRpcError(ViewErrorCode.INVALID_REQUEST, "the view is already ready"))
        ViewMethod.ACTION -> action(params)
        ViewMethod.RESIZE -> resize(params)
        ViewMethod.DISPLAY_MODE -> displayMode(params)
        ViewMethod.OPEN_LINK -> openLink(params)
        ViewMethod.LOG -> log(params)
    }

    private suspend fun action(params: JsonObject): ViewCallResult {
        val name = params.text("action")
        val check = inputChecks[name] ?: return forbidden("'$name' is not an action this page may call")
        val input = params["input"] as? JsonObject ?: JsonObject(emptyMap())
        val problems = check.check(input, "/params/input")
        if (problems.isNotEmpty()) return ViewCallResult.Err(ViewRpcError.invalidParams("the input of '$name' does not hold", problems))
        val call = PluginViewActionCall(spec.pluginId, spec.canvasId, spec.elementId, name, input)
        return outcome(services.transport.action(call))
    }

    private fun outcome(outcome: PluginViewActionOutcome): ViewCallResult = when (outcome) {
        is PluginViewActionOutcome.Done -> ViewCallResult.Ok(
            buildJsonObject {
                put("text", outcome.text)
                outcome.structured?.let { put("structured", it) }
            },
        )
        is PluginViewActionOutcome.Failed -> ViewCallResult.Err(ViewRpcError(ViewErrorCode.ACTION_FAILED, outcome.message))
        is PluginViewActionOutcome.Unavailable -> ViewCallResult.Err(ViewRpcError(ViewErrorCode.UNAVAILABLE, outcome.reason))
    }

    private fun resize(params: JsonObject): ViewCallResult {
        services.host.onResize(params.getValue("width").jsonPrimitive.double, params.getValue("height").jsonPrimitive.double)
        return EMPTY
    }

    private suspend fun displayMode(params: JsonObject): ViewCallResult {
        val wire = params.text("mode")
        val mode = PluginDisplayMode.entries.first { it.name.lowercase() == wire }
        if (mode !in spec.page.displayModes) return forbidden("the page does not declare the display mode '$wire'")
        val granted = services.host.requestDisplayMode(mode)
        return ViewCallResult.Ok(buildJsonObject { put("mode", granted.name.lowercase()) })
    }

    private suspend fun openLink(params: JsonObject): ViewCallResult {
        if (PluginCapability.UI_OPEN_LINK !in spec.page.permissions) return forbidden("the page does not declare the 'ui:openLink' permission")
        val link = ViewLink.parse(params.text("url")) ?: return invalid("only absolute http and https links open")
        if (!services.consent.allow(PluginCapability.UI_OPEN_LINK)) return denied("the person has not allowed this page to open links")
        if (!services.links.allows(link)) return denied("the link policy does not allow ${link.scheme}://${link.host}")
        services.host.openLink(link)
        return EMPTY
    }

    private fun log(params: JsonObject): ViewCallResult {
        services.host.onLog(ViewLogLevel.of(params.text("level")) ?: ViewLogLevel.INFO, params.text("message"))
        return EMPTY
    }

    private companion object {
        val EMPTY = ViewCallResult.Ok(JsonObject(emptyMap()))

        fun JsonObject.text(name: String): String = (getValue(name) as JsonPrimitive).content

        fun forbidden(message: String) = ViewCallResult.Err(ViewRpcError(ViewErrorCode.FORBIDDEN, message))

        fun denied(message: String) = ViewCallResult.Err(ViewRpcError(ViewErrorCode.DENIED, message))

        fun invalid(message: String) = ViewCallResult.Err(ViewRpcError(ViewErrorCode.INVALID_PARAMS, message))
    }
}
