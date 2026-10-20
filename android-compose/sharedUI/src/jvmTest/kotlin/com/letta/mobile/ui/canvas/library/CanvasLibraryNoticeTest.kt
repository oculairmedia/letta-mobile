@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.canvas.library

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.canvas.CanvasDocument
import com.letta.mobile.data.canvas.CanvasDocumentStore
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.data.canvas.library.CanvasLibraryController
import com.letta.mobile.ui.host.OverlayHost
import com.letta.mobile.ui.host.OverlayHostRoot
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test

class CanvasLibraryNoticeTest {
    @Test
    fun retryRemainsAvailableAfterRepeatedFailureAndClearsOnRecovery() = runComposeUiTest {
        var failing = true
        var gate: CompletableDeferred<Unit>? = null
        val store = object : CanvasDocumentStore by InMemoryCanvasDocumentStore() {
            override suspend fun listAll(): List<CanvasDocument> {
                gate?.await()
                check(!failing) { "Lookup timed out" }
                return emptyList()
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = CanvasLibraryController(store, scope)
        val host = OverlayHost()
        try {
            setContent {
                MaterialTheme {
                    CompositionLocalProvider(LocalReducedMotion provides true) {
                        OverlayHostRoot(host, Modifier.size(600.dp)) {
                            CanvasLibraryNotice(controller)
                            Text("Home remains usable")
                        }
                    }
                }
            }
            runOnIdle { controller.refresh() }
            onNodeWithText("Home remains usable").assertExists()
            onNodeWithText("Retry").assertIsDisplayed()
            // Immediate repeated failure must restore the action after the host consumes it.
            onNodeWithText("Retry").performClick()
            onNodeWithText("Retry").assertExists()
            runOnIdle { gate = CompletableDeferred() }
            onNodeWithText("Retry").performClick()
            onNodeWithText("Retrying canvas storage…").assertExists()
            onNodeWithText("Retry").assertDoesNotExist()
            runOnIdle {
                failing = false
                gate?.complete(Unit)
            }
            onNodeWithText("Retrying canvas storage…").assertDoesNotExist()
            onNodeWithText("Retry").assertDoesNotExist()
            onNodeWithText("Home remains usable").assertExists()
        } finally {
            scope.cancel()
        }
    }
}
