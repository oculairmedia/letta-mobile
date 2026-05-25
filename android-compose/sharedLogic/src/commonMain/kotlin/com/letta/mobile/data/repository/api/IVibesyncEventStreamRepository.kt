package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.VibesyncEvent
import kotlinx.coroutines.flow.SharedFlow

interface IVibesyncEventStreamRepository {
    val events: SharedFlow<VibesyncEvent>
    fun start()
    fun stop()
}
