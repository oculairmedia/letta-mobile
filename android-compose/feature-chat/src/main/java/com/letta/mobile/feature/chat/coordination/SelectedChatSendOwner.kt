package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter
import com.letta.mobile.runtime.BackendDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.plus

/** Writer resolves newly created conversations within this captured backend, never active settings. */
internal class SelectedChatSendOwner(
    val config: LettaConfig,
    val descriptor: BackendDescriptor,
    val writer: TimelineExternalTransportWriter,
    parent: CoroutineScope,
    private val prepareConversation: suspend (String) -> Unit,
) {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    val scope = parent + job
    private val readiness = Mutex()
    private val prepared = mutableSetOf<String>()

    fun requireCurrent() { check(job.isActive) { "Selected send backend retired" } }

    suspend fun ready(conversationId: String) = readiness.withLock {
        requireCurrent()
        if (conversationId !in prepared) {
            prepareConversation(conversationId)
            requireCurrent()
            prepared += conversationId
        }
    }

    suspend fun retire() = job.cancelAndJoin()
}
