package com.letta.mobile.data.schedules

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.schedules.leases.Lease

/** Optional shared wrapper record readers for schedule runs and read-only leases. */
data class ScheduleRecordSources(
    /** Loads wrapper run records for the selected agent and schedules. */
    val runHistory: (suspend (AgentId, List<String>) -> List<RunRecord>)? = null,
    /** Loads read-only wrapper leases for the selected agent. */
    val leases: (suspend (AgentId) -> List<Lease>)? = null,
)
