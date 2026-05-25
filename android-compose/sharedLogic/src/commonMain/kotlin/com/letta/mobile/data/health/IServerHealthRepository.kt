package com.letta.mobile.data.health

import kotlinx.coroutines.flow.StateFlow

interface IServerHealthRepository {
    val states: StateFlow<Map<String, ServerHealthState>>
    suspend fun refreshAll()
}
