package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.util.attr
import com.letta.mobile.util.Telemetry

/** The agent index met more agent files than it covers: counts and the hidden set then leave the oldest out. */
internal fun reportAgentFilesOverCap(found: Int, max: Int) {
    Telemetry.event("AgentFileIndex", "capped", "agentFiles" attr found, "max" attr max, level = Telemetry.Level.WARN)
}

/** A full App Server list is longer than its cache holds, so each request fetches its own again. */
internal fun reportHeldListTooLarge(list: String, items: Int, max: Int) {
    Telemetry.event("HeldListCache", "not_held", "list" attr list, "items" attr items, "max" attr max, level = Telemetry.Level.WARN)
}
