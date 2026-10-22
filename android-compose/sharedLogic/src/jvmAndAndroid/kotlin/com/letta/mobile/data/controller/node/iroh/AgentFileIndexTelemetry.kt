package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.util.Telemetry

/** The agent index met more agent files than it covers: counts and the hidden set then leave the oldest out. */
internal fun reportAgentFilesOverCap(found: Int, max: Int) {
    Telemetry.event("AgentFileIndex", "capped", "agentFiles" to found, "max" to max, level = Telemetry.Level.WARN)
}
