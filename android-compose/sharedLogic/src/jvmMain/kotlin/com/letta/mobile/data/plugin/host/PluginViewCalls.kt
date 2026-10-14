package com.letta.mobile.data.plugin.host

import com.letta.mobile.data.plugin.InstalledPlugin
import com.letta.mobile.data.plugin.PluginPage
import com.letta.mobile.data.plugin.view.PluginViewActionCall
import com.letta.mobile.data.plugin.view.PluginViewActionOutcome
import com.letta.mobile.data.plugin.view.PluginViewSpec
import com.letta.mobile.data.plugin.wire.LcpCallException
import com.letta.mobile.data.schema.JsonSchemaCheck
import com.letta.mobile.plugin.api.ActionCall
import com.letta.mobile.plugin.api.ActionContext
import com.letta.mobile.plugin.api.ActionOrigin
import com.letta.mobile.plugin.api.ActionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files

/** One page of an enabled plugin's current version: its manifest entry, its document, and the actions a view may call. */
class PluginPageDocument(
    val plugin: InstalledPlugin,
    val pageId: String,
    val page: PluginPage,
    val bytes: ByteArray,
    val viewActions: Map<String, JsonObject>,
)

/** Why a view's call was not run; [message] is safe to show the person. */
class PluginViewRefusal(message: String) : Exception(message)

/**
 * The host end of a live view (letta-mobile-cdgpr.7): the page a view loads and the actions it
 * calls, held to the same rules [com.letta.mobile.data.plugin.view.ViewCallHandler] holds a page to
 * on the client, since a client is not trusted to have checked. Only an enabled plugin serves
 * pages; only an action whose manifest visibility includes `view` runs, with input that holds to
 * its schema, as [ActionOrigin.View]. An `emit` in the answer is applied through the plugin's own
 * handlers (rate limits, placement, ownership), as an agent call's is.
 */
class PluginViewCalls(private val runtime: PluginHostRuntime) {
    /** [pageId] of [pluginId]; [version], when given, must be the installed one (a view of an older version reloads). */
    suspend fun page(pluginId: String, pageId: String, version: String?): PluginPageDocument {
        val plugin = enabled(pluginId)
        if (version != null && version != plugin.version) refuse("$pluginId is at ${plugin.version}, not $version")
        val page = plugin.manifest.pages[pageId] ?: refuse("$pluginId has no page '$pageId'")
        val dir = runtime.packageDir(plugin)
        val file = dir.resolve(page.html).normalize().takeIf { it.startsWith(dir) } ?: refuse("page '$pageId' leaves its package")
        val bytes = withContext(Dispatchers.IO) { if (Files.isRegularFile(file)) Files.readAllBytes(file) else null }
            ?: refuse("page '$pageId' of $pluginId is missing from its package")
        return PluginPageDocument(plugin, pageId, page, bytes, PluginViewSpec.viewActionsOf(plugin.manifest))
    }

    /** Runs [call]; refusals throw [PluginViewRefusal], what the plugin answered comes back as an outcome. */
    suspend fun action(call: PluginViewActionCall): PluginViewActionOutcome {
        val plugin = enabled(call.pluginId)
        val schema = PluginViewSpec.viewActionsOf(plugin.manifest)[call.action]
            ?: refuse("'${call.action}' is not an action a view of ${call.pluginId} may call")
        val problems = JsonSchemaCheck(schema).check(call.input, "/input")
        if (problems.isNotEmpty()) refuse("the input of '${call.action}' does not hold: " + problems.joinToString("; ") { "${it.path}: ${it.message}" })
        val live = runtime.live(call.pluginId) ?: return PluginViewActionOutcome.Unavailable("${call.pluginId} is not running on the host")
        val origin = ActionContext(ActionOrigin.View(call.elementId), canvasId = call.canvasId, elementId = call.elementId)
        return try {
            when (val result = live.driver.invoke(ActionCall(call.action, call.input, origin))) {
                is ActionResult.Error -> PluginViewActionOutcome.Failed("${result.code}: ${result.message}")
                is ActionResult.Ok -> {
                    result.emit?.let { live.handlers.emit(it) }
                    PluginViewActionOutcome.Done(result.text, result.structured)
                }
            }
        } catch (e: LcpCallException) {
            PluginViewActionOutcome.Failed("${call.action} failed: ${e.message}")
        }
    }

    private fun enabled(pluginId: String): InstalledPlugin {
        val plugin = runtime.state.value[pluginId] ?: refuse("$pluginId is not installed")
        if (!plugin.state.advertises) refuse("$pluginId is not enabled")
        return plugin
    }

    private fun refuse(message: String): Nothing = throw PluginViewRefusal(message)
}
