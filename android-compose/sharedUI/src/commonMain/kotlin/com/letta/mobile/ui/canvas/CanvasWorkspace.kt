package com.letta.mobile.ui.canvas

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.CanvasSessionRegistry
import io.ak1.drawbox.DrawBox
import io.ak1.drawbox.domain.model.Event
import io.ak1.drawbox.domain.usecase.UseCase
import io.ak1.drawbox.presentation.reducer.Reducer
import io.ak1.drawbox.presentation.viewmodel.DrawBoxController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Shared Canvas Workspace composable for Meridian.
 * Hosts DrawBox editor with lifted demo UI ControlsBar, sample diagram import,
 * JSON/SVG export capabilities, and optional persistent [CanvasSession] integration.
 */
@Composable
fun CanvasWorkspace(
    modifier: Modifier = Modifier,
    controller: DrawBoxController = remember { DrawBoxController(Reducer(UseCase())) },
    session: CanvasSession? = null,
    sessions: CanvasSessionRegistry = CanvasSessionRegistry(),
    initialJson: String? = null,
    onNavigateBack: (() -> Unit)? = null,
    onExportJson: ((String) -> Unit)? = null,
    onExportSvg: ((String) -> Unit)? = null,
) {
    val state by controller.state.collectAsState()
    val canUndo by controller.canUndo.collectAsState()
    val canRedo by controller.canRedo.collectAsState()
    val sessionDoc by (session?.document?.collectAsState() ?: remember { mutableStateOf(null) })

    var statusMessage by remember { mutableStateOf("Ready") }
    var initialLoadDone by remember { mutableStateOf(false) }
    var lastExportedJson by remember { mutableStateOf<String?>(null) }

    DisposableEffect(session, sessions) {
        if (session != null) {
            sessions.register(session)
        }
        onDispose {
            if (session != null) {
                sessions.unregister(session.canvasId)
            }
        }
    }

    // Load initial JSON diagram or session document & observe external session updates (Card I2.3)
    LaunchedEffect(session, initialJson) {
        if (session != null) {
            session.load()
            val sessionJson = session.sceneJsonOrEmpty()
            var lastImportedRev = session.document.value?.revision ?: 0L
            if (sessionJson.isNotBlank()) {
                controller.importPath(sessionJson)
                lastExportedJson = sessionJson
                statusMessage = "Loaded from session (rev ${session.document.value?.revision ?: 1})"
            }
            delay(100)
            initialLoadDone = true

            // Card I2.3: Session observes revision bump -> controller.importPath if JSON changed externally.
            // Conflict: agent replace wins; toast/status.
            session.document.collect { doc ->
                if (doc != null && doc.revision > lastImportedRev) {
                    lastImportedRev = doc.revision
                    if (doc.sceneJson.isNotBlank() && doc.sceneJson != lastExportedJson) {
                        controller.importPath(doc.sceneJson)
                        statusMessage = "Agent updated canvas (rev ${doc.revision})"
                    }
                }
            }
        } else {
            if (!initialJson.isNullOrBlank()) {
                controller.importPath(initialJson)
                statusMessage = "Loaded diagram (${state.elements.size} elements)"
            }
            delay(100)
            initialLoadDone = true
        }
    }

    var isAutosaving by remember { mutableStateOf(false) }

    // Card I1.6: Autosave debounce
    // Debounce ~500ms on dirty signal (elements change) -> exportJson()
    // The resulting Event.JsonExported persists the updated scene off the main thread.
    // Document: Full JSON replace is acceptable for single-player session in P1;
    // multi-writer op-log projection will be introduced in P3.
    LaunchedEffect(state.elements, initialLoadDone, session) {
        if (!initialLoadDone || session == null) return@LaunchedEffect
        delay(500)
        isAutosaving = true
        controller.exportJson()
    }

    // Collect export/error events from DrawBoxController
    LaunchedEffect(controller, session) {
        controller.events.collect { event ->
            when (event) {
                is Event.JsonExported -> {
                    lastExportedJson = event.json
                    val wasAutosaving = isAutosaving
                    isAutosaving = false
                    val hasElements = event.json.contains("\"elements\"")
                    if (!wasAutosaving) {
                        statusMessage = if (hasElements) {
                            "Exported JSON (${event.json.length} chars, verified)"
                        } else {
                            "Warning: Exported JSON missing 'elements' key"
                        }
                    }
                    if (session != null && session.sceneJsonOrEmpty() != event.json) {
                        withContext(Dispatchers.Default) {
                            session.saveScene(event.json)
                        }
                    }
                    onExportJson?.invoke(event.json)
                }
                is Event.SvgExported -> {
                    val hasSvgTag = event.svg.contains("<svg", ignoreCase = true)
                    statusMessage = if (hasSvgTag) {
                        "Exported SVG (${event.svg.length} chars, verified)"
                    } else {
                        "Warning: Exported SVG missing '<svg' tag"
                    }
                    onExportSvg?.invoke(event.svg)
                }
                is Event.Error -> {
                    val err = event.message.ifBlank { event.throwable?.message ?: "Unknown" }
                    statusMessage = "Error: $err"
                }
                else -> Unit
            }
        }
    }

    val hasSelection = state.selectedIds.isNotEmpty()
    val controlsBarState = CanvasControlsBridge.buildControlsBarState(
        state = state,
        canUndo = canUndo,
        canRedo = canRedo,
    )

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // DrawBox Canvas layer
            DrawBox(
                state = state,
                onIntent = controller::onIntent,
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds(),
            )

            // Top action bar: Sample loader + Exports + Status
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
                    shadowElevation = 3.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (onNavigateBack != null) {
                            OutlinedButton(
                                onClick = {
                                    if (session != null) {
                                        controller.exportJson()
                                    }
                                    onNavigateBack()
                                },
                            ) {
                                Text("Back")
                            }
                        }

                        Button(
                            onClick = {
                                controller.importPath(CanvasSamples.buildCycleJson)
                                statusMessage = "Imported Build Cycle sample"
                            },
                        ) {
                            Text("Import Build Cycle")
                        }

                        Button(
                            onClick = {
                                controller.importPath(CanvasSamples.dailyLoopJson)
                                statusMessage = "Imported Daily Loop sample"
                            },
                        ) {
                            Text("Import Daily Loop")
                        }

                        OutlinedButton(
                            onClick = {
                                controller.reset()
                                statusMessage = "Cleared canvas"
                            },
                        ) {
                            Text("Clear")
                        }

                        Button(
                            onClick = { controller.exportJson() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary,
                            ),
                        ) {
                            Text("Export JSON")
                        }

                        Button(
                            onClick = { controller.exportSvg() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.tertiary,
                            ),
                        ) {
                            Text("Export SVG")
                        }
                    }
                }

                // Status chip
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    shadowElevation = 1.dp,
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    val sessionSuffix = sessionDoc?.let { " | ${it.title} (rev ${it.revision})" }.orEmpty()
                    Text(
                        text = "Elements: ${state.elements.size}$sessionSuffix | $statusMessage",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            // Lifted Bottom ControlsBar
            // Our own bar: the drawbox-ui one loads drawables its Android artifact never ships
            // (letta-mobile-r5f3r). See CanvasControlsBar.
            CanvasControlsBar(
                state = controlsBarState,
                dispatch = { intent ->
                    CanvasControlsBridge.dispatchIntent(
                        controller = controller,
                        intent = intent,
                        hasSelection = hasSelection,
                    )
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
            )
        }
    }
}
