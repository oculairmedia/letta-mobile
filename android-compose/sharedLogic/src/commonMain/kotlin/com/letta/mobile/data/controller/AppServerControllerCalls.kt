package com.letta.mobile.data.controller

import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlinx.coroutines.CancellationException

/**
 * Runs one App Server request for this runtime. Cancellation propagates untouched; any other
 * failure is wrapped in an [AppServerControllerException] naming [action] and the runtime.
 */
internal inline fun <T> AppServerRuntimeScope.controllerCall(action: String, call: () -> T): T =
    try {
        call()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw AppServerControllerException("Failed to $action runtime $agentId/$conversationId", e)
    }
