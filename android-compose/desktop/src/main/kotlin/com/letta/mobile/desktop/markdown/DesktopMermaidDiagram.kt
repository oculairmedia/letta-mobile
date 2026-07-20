package com.letta.mobile.desktop.markdown

import com.letta.mobile.mermaid.MermaidNativeRenderer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.markdown.MermaidDiagramRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Data
import org.jetbrains.skia.Rect
import org.jetbrains.skia.svg.SVGDOM
import java.nio.file.Files
import java.nio.file.StandardCopyOption

val DesktopMermaidDiagramRenderer = MermaidDiagramRenderer { source, modifier ->
    DesktopMermaidDiagram(source, modifier)
}

@Composable
private fun DesktopMermaidDiagram(source: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val isDarkTheme = isSystemInDarkTheme()
    val style = remember(colors, isDarkTheme) {
        DesktopMermaidStyle(
            textArgb = colors.onSurfaceVariant.toArgb(),
            borderArgb = colors.outlineVariant.toArgb(),
            surfaceArgb = colors.surfaceVariant.toArgb(),
            primaryArgb = colors.primaryContainer.toArgb(),
            secondaryArgb = colors.secondaryContainer.toArgb(),
            tertiaryArgb = colors.tertiaryContainer.toArgb(),
            darkTheme = isDarkTheme,
        )
    }
    val result by produceState<DesktopMermaidRenderResult>(
        initialValue = DesktopMermaidRenderResult.Loading,
        source,
        style,
    ) {
        value = withContext(Dispatchers.Default) {
            DesktopMermaidNativeBridge.renderToSvg(source, style)
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        color = colors.surfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Column {
            MermaidHeader(source)
            when (val current = result) {
                DesktopMermaidRenderResult.Loading -> Text(
                    text = "Rendering diagram...",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
                is DesktopMermaidRenderResult.Rendered -> MermaidSvg(current.svg, source)
                is DesktopMermaidRenderResult.Failed -> MermaidSourceFallback(source, current.reason)
            }
        }
    }
}

@Composable
private fun MermaidHeader(source: String) {
    val clipboard = LocalClipboardManager.current
    Row(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp)) {
        Text(
            text = "mermaid",
            modifier = Modifier.weight(1f).padding(vertical = 12.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(onClick = { clipboard.setText(AnnotatedString(source)) }) {
            Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy Mermaid source")
        }
    }
}

@Composable
private fun MermaidSvg(svg: String, source: String) {
    val parsed = remember(svg) {
        runCatching {
            val data = Data.makeFromBytes(svg.encodeToByteArray())
            MermaidSvgDom(data = data, dom = SVGDOM(data))
        }.getOrNull()
    }
    DisposableEffect(parsed) {
        onDispose { parsed?.close() }
    }
    if (parsed == null) {
        MermaidSourceFallback(svg, "Rendered SVG could not be decoded")
        return
    }
    val aspectRatio = mermaidAspectRatio(parsed.dom.root?.viewBox)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height((360f / aspectRatio).coerceIn(180f, 480f).dp)
            .padding(12.dp)
            .semantics { contentDescription = "Mermaid diagram:\n$source" },
    ) {
        parsed.dom.setContainerSize(size.width, size.height)
        drawContext.canvas.nativeCanvas.save()
        parsed.dom.render(drawContext.canvas.nativeCanvas)
        drawContext.canvas.nativeCanvas.restore()
    }
}

private fun mermaidAspectRatio(viewBox: Rect?): Float =
    viewBox?.takeIf(::hasPositiveSize)?.let { it.width / it.height } ?: 16f / 9f

private fun hasPositiveSize(viewBox: Rect): Boolean =
    viewBox.width > 0f && viewBox.height > 0f

private class MermaidSvgDom(
    private val data: Data,
    val dom: SVGDOM,
) {
    fun close() {
        runCatching { data.close() }
    }
}

@Composable
private fun MermaidSourceFallback(source: String, reason: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(12.dp),
    ) {
        Text(
            text = "Mermaid render failed: $reason",
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            text = source,
            modifier = Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
    }
}

private data class DesktopMermaidStyle(
    val textArgb: Int,
    val borderArgb: Int,
    val surfaceArgb: Int,
    val primaryArgb: Int,
    val secondaryArgb: Int,
    val tertiaryArgb: Int,
    val darkTheme: Boolean,
)

private sealed interface DesktopMermaidRenderResult {
    data object Loading : DesktopMermaidRenderResult
    data class Rendered(val svg: String) : DesktopMermaidRenderResult
    data class Failed(val reason: String) : DesktopMermaidRenderResult
}

private object DesktopMermaidNativeBridge {
    private val renderMutex = Mutex()

    private val available by lazy {
        runCatching {
            val libraryName = when {
                System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> "letta_mermaid_renderer.dll"
                System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> "libletta_mermaid_renderer.dylib"
                else -> "libletta_mermaid_renderer.so"
            }
            val resource = requireNotNull(javaClass.classLoader.getResourceAsStream(libraryName))
            val suffix = ".${libraryName.substringAfterLast('.')}"
            val extracted = Files.createTempFile("letta-mermaid-", suffix)
            resource.use { Files.copy(it, extracted, StandardCopyOption.REPLACE_EXISTING) }
            extracted.toFile().deleteOnExit()
            System.load(extracted.toAbsolutePath().toString())
            true
        }.getOrDefault(false)
    }

    suspend fun renderToSvg(source: String, style: DesktopMermaidStyle): DesktopMermaidRenderResult {
        if (!available) return DesktopMermaidRenderResult.Failed("Desktop Mermaid renderer is unavailable")
        // Native LAST_ERROR is process-global; serialize renders across diagrams.
        return renderMutex.withLock {
            runCatching {
                val svg = MermaidNativeRenderer.nativeRenderToSvg(
                    source = source,
                    darkTheme = style.darkTheme,
                    textArgb = style.textArgb,
                    borderArgb = style.borderArgb,
                    surfaceArgb = style.surfaceArgb,
                    primaryArgb = style.primaryArgb,
                    secondaryArgb = style.secondaryArgb,
                    tertiaryArgb = style.tertiaryArgb,
                )
                if (svg.isNullOrBlank()) {
                    DesktopMermaidRenderResult.Failed(
                        MermaidNativeRenderer.nativeTakeLastError().orEmpty().ifBlank { "Renderer returned no SVG" },
                    )
                } else {
                    DesktopMermaidRenderResult.Rendered(svg)
                }
            }.getOrElse { DesktopMermaidRenderResult.Failed(it.message ?: it::class.java.simpleName) }
        }
    }
}
