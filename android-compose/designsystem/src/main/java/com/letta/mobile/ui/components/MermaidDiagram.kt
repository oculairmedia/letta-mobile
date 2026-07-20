package com.letta.mobile.ui.components

import com.letta.mobile.ui.theme.LettaCodeFont

import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Size
import coil3.svg.SvgDecoder
import com.letta.mobile.ui.icons.LettaIconSizing
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaSizing
import com.letta.mobile.ui.theme.LettaSpacing
import com.letta.mobile.ui.zoom.ZoomViewportState

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

    var renderError by remember { mutableStateOf<String?>(null) }

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
        val error = renderError ?: return
        MermaidErrorFallback(
            source = source,
            errorMessage = error,
            modifier = modifier,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
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
            .size(Size(2000, 2000))
            .build()
    }

        Surface(
            shape = RoundedCornerShape(LettaSpacing.CARD_GAP),
            color = Color.Transparent,
            modifier = modifier.fillMaxWidth().padding(vertical = LettaSpacing.CARD_GROUP_ITEM_GAP + LettaSpacing.CARD_GROUP_ITEM_GAP),
        ) {
            Column {
                MermaidHeader(onCopy = onCopy)
                AsyncImage(
                    model = request,
                    contentDescription = "Mermaid diagram rendered natively",
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = LettaSizing.diagramPreviewMinHeight)
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { onFullscreenChange(true) },
                        ),
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
        val zoomState = remember { ZoomViewportState() }
        var viewportSize by remember { mutableStateOf(IntSize.Zero) }
        fun resetViewport() {
            zoomState.reset()
        }
        fun setHundredPercentViewport() {
            zoomState.setAbsoluteScale(FULLSCREEN_ONE_TO_ONE_APPROX_ZOOM)
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xE6000000)),
        ) {
            val isCompact = maxWidth < LettaSizing.compactWidthBreakpoint
            val contentModifier = if (isCompact) {
                Modifier.fillMaxSize()
            } else {
                Modifier
                    .widthIn(max = LettaSizing.readableDialogMaxWidth)
                    .fillMaxWidth()
                    .padding(LettaSpacing.INNER_PADDING + LettaSpacing.INNER_PADDING)
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
                            .padding(horizontal = LettaSpacing.INNER_PADDING_SMALL, vertical = LettaSpacing.CARD_GAP),
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
                            modifier = Modifier.padding(start = LettaSpacing.CARD_GAP),
                        ) {
                            Icon(
                                imageVector = LettaIcons.Copy,
                                contentDescription = null,
                                modifier = Modifier.size(LettaIconSizing.Inline),
                            )
                            Text(
                                text = "Copy",
                                modifier = Modifier.padding(start = LettaSpacing.CARD_GAP),
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = LettaSpacing.INNER_PADDING_SMALL, vertical = LettaSpacing.CARD_GAP)
                            .onSizeChanged { viewportSize = it }
                            .clip(RoundedCornerShape(LettaSpacing.INNER_PADDING))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.08f))
                            .pointerInput(Unit) {
                                detectTransformGestures { centroid, panChange, zoomChange, _ ->
                                    zoomState.onTransform(
                                        zoomChange = zoomChange,
                                        panChange = panChange,
                                        centroid = centroid,
                                        viewportSize = viewportSize,
                                    )
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        AsyncImage(
                            model = request,
                            contentDescription = "Mermaid diagram fullscreen",
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer(
                                    scaleX = zoomState.scale,
                                    scaleY = zoomState.scale,
                                    translationX = zoomState.pan.x,
                                    translationY = zoomState.pan.y,
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
            .padding(start = LettaSpacing.INNER_PADDING_SMALL, end = LettaSpacing.CARD_GROUP_ITEM_GAP + LettaSpacing.CARD_GROUP_ITEM_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "mermaid",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(vertical = LettaSpacing.CARD_GAP),
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
                modifier = Modifier.padding(LettaSpacing.CARD_GROUP_ITEM_GAP + LettaSpacing.CARD_GROUP_ITEM_GAP),
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
            shape = RoundedCornerShape(LettaSpacing.CARD_GAP),
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = modifier.fillMaxWidth().padding(vertical = LettaSpacing.CARD_GROUP_ITEM_GAP + LettaSpacing.CARD_GROUP_ITEM_GAP),
        ) {
            Column(modifier = Modifier.padding(LettaSpacing.INNER_PADDING_SMALL)) {
            Text(
                text = "Mermaid render failed: $errorMessage",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = source,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = LettaCodeFont),
                color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(top = LettaSpacing.CARD_GAP),
            )
        }
    }
}

private const val TAG = "MermaidDiagram"
private const val FULLSCREEN_ONE_TO_ONE_APPROX_ZOOM = 2f
