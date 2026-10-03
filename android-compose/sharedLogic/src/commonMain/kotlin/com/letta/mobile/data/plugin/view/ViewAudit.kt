package com.letta.mobile.data.plugin.view

import com.letta.mobile.util.Telemetry

/** Which way a message went across the bridge. */
enum class ViewDirection { VIEW_TO_HOST, HOST_TO_VIEW }

/**
 * One message across a view bridge, as the audit sees it: whose view, which method, and what came
 * of it ([refusal] null when accepted). Params and results are never recorded: a page's input may
 * hold anything the person typed.
 */
data class ViewAuditEntry(
    val view: PluginViewSpec,
    val direction: ViewDirection,
    val method: String?,
    val refusal: ViewErrorCode? = null,
)

/** Where a bridge reports every message it accepts, refuses or sends. */
fun interface ViewAuditSink {
    fun record(entry: ViewAuditEntry)

    companion object {
        const val TAG: String = "Plugin"
        const val EVENT: String = "view.rpc"

        /** Writes each entry to [Telemetry] as `Plugin view.rpc`, refusals at WARN. */
        val ToTelemetry: ViewAuditSink = ViewAuditSink { entry ->
            Telemetry.event(
                TAG,
                EVENT,
                "pluginId" to entry.view.pluginId,
                "pageId" to entry.view.pageId,
                "elementId" to entry.view.elementId,
                "direction" to entry.direction.name,
                "method" to entry.method,
                "refusal" to entry.refusal?.name,
                level = if (entry.refusal == null) Telemetry.Level.INFO else Telemetry.Level.WARN,
            )
        }

        val None: ViewAuditSink = ViewAuditSink { }
    }
}
