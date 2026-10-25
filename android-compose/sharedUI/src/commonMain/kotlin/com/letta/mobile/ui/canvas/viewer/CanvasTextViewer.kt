package com.letta.mobile.ui.canvas.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.canvas_viewer_waiting
import org.jetbrains.compose.resources.stringResource

/** Shows the current scene snapshot as selectable text; there is no text editing or write path. */
@Composable
internal fun CanvasTextViewer(
    sceneJson: String?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        SelectionContainer {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, top = 112.dp, end = 16.dp, bottom = 16.dp),
            ) {
                Text(
                    text = sceneJson ?: stringResource(Res.string.canvas_viewer_waiting),
                    modifier = Modifier.testTag(CANVAS_TEXT_VIEWER_CONTENT_TAG),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

internal const val CANVAS_TEXT_VIEWER_CONTENT_TAG = "canvas-text-viewer-content"
