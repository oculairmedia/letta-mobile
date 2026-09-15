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
import io.ak1.drawbox.DrawBox
import io.ak1.drawbox.domain.model.Event
import io.ak1.drawbox.presentation.viewmodel.rememberDrawBoxController
import io.ak1.drawbox.ui.controls.ControlsBar
import io.ak1.drawbox.ui.controls.defaultControlsBarItems

/**
 * Shared Canvas Workspace composable for Meridian.
 * Hosts DrawBox editor with lifted demo UI ControlsBar, sample diagram import,
 * and JSON/SVG export capabilities.
 */
@Composable
fun CanvasWorkspace(
    modifier: Modifier = Modifier,
    initialJson: String? = null,
    onNavigateBack: (() -> Unit)? = null,
    onExportJson: ((String) -> Unit)? = null,
    onExportSvg: ((String) -> Unit)? = null,
) {
    val controller = rememberDrawBoxController()
    val state by controller.state.collectAsState()
    val canUndo by controller.canUndo.collectAsState()
    val canRedo by controller.canRedo.collectAsState()

    var statusMessage by remember { mutableStateOf("Ready") }
    var lastExportedJson by remember { mutableStateOf<String?>(null) }
    var lastExportedSvg by remember { mutableStateOf<String?>(null) }

    // Load initial JSON diagram if provided
    LaunchedEffect(initialJson) {
        if (!initialJson.isNullOrBlank()) {
            controller.importPath(initialJson)
            statusMessage = "Loaded diagram (${state.elements.size} elements)"
        }
    }

    // Collect export/error events from DrawBoxController
    LaunchedEffect(controller) {
        controller.events.collect { event ->
            when (event) {
                is Event.JsonExported -> {
                    lastExportedJson = event.json
                    val hasElements = event.json.contains("\"elements\"")
                    statusMessage = if (hasElements) {
                        "Exported JSON (${event.json.length} chars, verified)"
                    } else {
                        "Warning: Exported JSON missing 'elements' key"
                    }
                    onExportJson?.invoke(event.json)
                }
                is Event.SvgExported -> {
                    lastExportedSvg = event.svg
                    val hasSvgTag = event.svg.contains("<svg", ignoreCase = true)
                    statusMessage = if (hasSvgTag) {
                        "Exported SVG (${event.svg.length} chars, verified)"
                    } else {
                        "Warning: Exported SVG missing '<svg' tag"
                    }
                    onExportSvg?.invoke(event.svg)
                }
                is Event.Error -> {
                    statusMessage = "Error: ${event.throwable?.message ?: "Unknown"}"
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

    val controlsItems = defaultControlsBarItems(
        state = controlsBarState,
        dispatch = { intent ->
            CanvasControlsBridge.dispatchIntent(
                controller = controller,
                intent = intent,
                hasSelection = hasSelection,
            )
        },
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
                            OutlinedButton(onClick = onNavigateBack) {
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
                    Text(
                        text = "Elements: ${state.elements.size} | $statusMessage",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            // Lifted Bottom ControlsBar
            ControlsBar(
                items = controlsItems,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
            )
        }
    }
}
