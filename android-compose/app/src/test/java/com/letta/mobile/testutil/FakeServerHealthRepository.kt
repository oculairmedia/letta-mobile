package com.letta.mobile.testutil

import com.letta.mobile.data.health.IServerHealthRepository
import com.letta.mobile.data.health.ServerHealthState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeServerHealthRepository(
    initialStates: Map<String, ServerHealthState> = emptyMap(),
) : IServerHealthRepository {
    private val statesFlow = MutableStateFlow(initialStates)
    override val states: StateFlow<Map<String, ServerHealthState>> = statesFlow.asStateFlow()
    var refreshCount: Int = 0

    override suspend fun refreshAll() {
        refreshCount += 1
    }

    fun setStates(states: Map<String, ServerHealthState>) {
        statesFlow.value = states
    }
}
