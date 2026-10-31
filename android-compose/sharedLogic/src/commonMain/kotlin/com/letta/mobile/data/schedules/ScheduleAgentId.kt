package com.letta.mobile.data.schedules

import com.letta.mobile.data.model.AgentId

/**
 * The agent a schedule runs, typed, as the schedules UI names it. The calendar package reaches the
 * id through this package (which it already imports) rather than adding an import of data.model,
 * whose package sits in a large import cycle.
 */
typealias ScheduleAgentId = AgentId
