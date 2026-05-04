package com.letta.mobile.ui.components

import android.util.Log
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Size
import coil3.svg.SvgDecoder
import com.letta.mobile.ui.icons.LettaIcons

@Composable
fun MermaidDiagram(
    source: String,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var showFullscreen by rememberSaveable { mutableStateOf(false) }

    val isDark = isSystemInDarkTheme()
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant
    val primaryColor = MaterialTheme.colorScheme.primaryContainer
    val secondaryColor = MaterialTheme.colorScheme.secondaryContainer
    val tertiaryColor = MaterialTheme.colorScheme.tertiaryContainer
    val nativeStyle = remember(
        textColor,
        borderColor,
        surfaceColor,
        primaryColor,
        secondaryColor,
        tertiaryColor,
    ) {
        MermaidStyleSpec(
            textArgb = textColor.toArgb(),
            borderArgb = borderColor.toArgb(),
            surfaceArgb = surfaceColor.toArgb(),
            primaryArgb = primaryColor.toArgb(),
            secondaryArgb = secondaryColor.toArgb(),
            tertiaryArgb = tertiaryColor.toArgb(),
        )
    }

    var renderError by mutableStateOf<String?>(null)

    val nativeRender = remember(source, isDark, nativeStyle) {
        MermaidNativeBridge.renderToSvg(
            source = source,
            darkTheme = isDark,
            style = nativeStyle,
        )
    }

    when (nativeRender) {
        is MermaidNativeRenderResult.Rendered -> {
            MermaidSvgDiagram(
                svg = nativeRender.svg,
                modifier = modifier,
                onCopy = { clipboard.setText(AnnotatedString(source)) },
                context = context,
                showFullscreen = showFullscreen,
                onFullscreenChange = { showFullscreen = it },
            )
            return
        }
        is MermaidNativeRenderResult.Failed -> {
            Log.w(TAG, "Native Mermaid render failed: ${nativeRender.reason}")
            renderError = nativeRender.reason
        }
        MermaidNativeRenderResult.Unavailable -> {
            renderError = "Native Mermaid renderer not available"
        }
    }

    if (renderError != null) {
        MermaidErrorFallback(
            source = source,
            errorMessage = renderError!!,
            modifier = modifier,
        )
    }
}

@Composable
private fun MermaidSvgDiagram(
    svg: String,
    modifier: Modifier,
    onCopy: () -> Unit,
    context: android.content.Context,
    showFullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
) {
    val request = remember(svg, context) {
        ImageRequest.Builder(context)
            .data(svg.toByteArray(Charsets.UTF_8))
            .decoderFactory(SvgDecoder.Factory())
            .build()
    }

    val fullscreenRequest = remember(svg, context) {
        ImageRequest.Builder(context)
            .data(svg.toByteArray(Charsets.UTF_8))
            .decoderFactory(SvgDecoder.Factory())
            .size(coil3.size.Size(2000, 2000))
            .build()
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color.Transparent,
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column {
            MermaidHeader(onCopy = onCopy)
            AsyncImage(
                model = request,
                contentDescription = "Mermaid diagram rendered natively",
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 120.dp)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            val upBeforeTimeout = withTimeoutOrNull(
                                viewConfiguration.longPressTimeoutMillis,
                            ) {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.changes.any { !it.pressed }) break
                                }
                            }
                            if (upBeforeTimeout != null) {
                                onCopy()
                            } else {
                                onFullscreenChange(true)
                            }
                        }
                    },
                contentScale = ContentScale.Fit,
            )
        }
    }

    if (showFullscreen) {
        MermaidFullscreenDialog(
            request = fullscreenRequest,
            onDismiss = { onFullscreenChange(false) },
            onCopy = onCopy,
        )
    }
}

@Composable
private fun MermaidFullscreenDialog(
    request: ImageRequest,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        var zoom by mutableStateOf(1f)
        var pan by mutableStateOf(Offset.Zero)
        fun resetViewport() {
            zoom = 1f
            pan = Offset.Zero
        }
        fun setHundredPercentViewport() {
            zoom = FULLSCREEN_ONE_TO_ONE_APPROX_ZOOM
            pan = Offset.Zero
        }

        val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
            val nextZoom = (zoom * zoomChange).coerceIn(1f, 4f)
            val appliedScale = nextZoom / zoom
            zoom = nextZoom
            pan = if (zoom <= 1.01f) {
                Offset.Zero
            } else {
                (pan + panChange * appliedScale)
            }
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xE6000000)),
        ) {
            val isCompact = maxWidth < 600.dp
            val contentModifier = if (isCompact) {
                Modifier.fillMaxSize()
            } else {
                Modifier
                    .widthIn(max = 1000.dp)
                    .fillMaxWidth()
                    .padding(32.dp)
            }

            Card(
                modifier = contentModifier.align(Alignment.Center),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "mermaid",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = ::resetViewport) {
                            Text("Fit")
                        }
                        TextButton(onClick = ::setHundredPercentViewport) {
                            Text("100%")
                        }
                        TextButton(onClick = onDismiss) {
                            Text("Done")
                        }
                        FilledTonalButton(
                            onClick = onCopy,
                            modifier = Modifier.padding(start = 8.dp),
                        ) {
                            Icon(
                                imageVector = LettaIcons.Copy,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = "Copy",
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.08f))
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onDoubleTap = { resetViewport() },
                                )
                            }
                            .transformable(transformableState),
                        contentAlignment = Alignment.Center,
                    ) {
                        AsyncImage(
                            model = request,
                            contentDescription = "Mermaid diagram fullscreen",
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer(
                                    scaleX = zoom,
                                    scaleY = zoom,
                                    translationX = pan.x,
                                    translationY = pan.y,
                                ),
                            contentScale = ContentScale.Fit,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MermaidHeader(onCopy: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "mermaid",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(vertical = 8.dp),
        )
        Box(modifier = Modifier.weight(1f))
        IconButton(
            onClick = onCopy,
            colors = IconButtonDefaults.iconButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            ),
        ) {
            Icon(
                imageVector = LettaIcons.Copy,
                contentDescription = "Copy diagram source",
                modifier = Modifier.padding(4.dp),
            )
        }
    }
}

@Composable
private fun MermaidErrorFallback(
    source: String,
    errorMessage: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Mermaid render failed: $errorMessage",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = source,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

private const val TAG = "MermaidDiagram"
private const val FULLSCREEN_ONE_TO_ONE_APPROX_ZOOM = 2f
