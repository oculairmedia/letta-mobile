package com.letta.mobile.feature.chat.coordination

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.plus

/** Subscriber-local jobs, including legacy collectors; retirement never aborts the server turn. */
internal class ChatPipelineLifetime(parent: CoroutineScope) {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    val scope = parent + job
    suspend fun retire() = job.cancelAndJoin()
}
