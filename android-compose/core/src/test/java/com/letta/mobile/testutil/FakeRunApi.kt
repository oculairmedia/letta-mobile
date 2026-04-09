package com.letta.mobile.testutil

import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.api.RunApi
import com.letta.mobile.data.model.Run
import com.letta.mobile.data.model.RunListParams

class FakeRunApi : RunApi(null!!) {
    var runs = mutableListOf<Run>()
    var shouldFail = false
    val calls = mutableListOf<String>()

    override suspend fun listRuns(params: RunListParams): List<Run> {
        calls.add("listRuns")
        if (shouldFail) throw ApiException(500, "Server error")
        return runs.filter { run ->
            (params.agentId == null || run.agentId == params.agentId) &&
                (params.active == null || (run.status == "running") == params.active)
        }
    }

    override suspend fun retrieveRun(runId: String): Run {
        calls.add("retrieveRun:$runId")
        if (shouldFail) throw ApiException(500, "Server error")
        return runs.firstOrNull { it.id == runId } ?: throw ApiException(404, "Not found")
    }
}
