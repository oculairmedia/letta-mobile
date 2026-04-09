package com.letta.mobile.data.repository

import com.letta.mobile.data.api.RunApi
import com.letta.mobile.data.model.Run
import com.letta.mobile.data.model.RunListParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RunRepository @Inject constructor(
    private val runApi: RunApi,
) {
    private val _runs = MutableStateFlow<List<Run>>(emptyList())
    val runs: StateFlow<List<Run>> = _runs.asStateFlow()

    suspend fun refreshRuns(params: RunListParams = RunListParams()) {
        _runs.value = runApi.listRuns(params)
    }

    suspend fun getRun(runId: String): Run {
        return runApi.retrieveRun(runId)
    }

    fun upsertRun(run: Run) {
        _runs.update { current ->
            val index = current.indexOfFirst { it.id == run.id }
            if (index >= 0) {
                current.toMutableList().apply { this[index] = run }
            } else {
                current + run
            }
        }
    }
}
