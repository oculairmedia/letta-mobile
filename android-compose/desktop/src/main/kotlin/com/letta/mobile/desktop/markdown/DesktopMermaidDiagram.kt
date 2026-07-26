package com.letta.mobile.desktop.markdown

import com.letta.mobile.mermaid.MermaidNativeRenderer
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.markdown.MermaidDiagramRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    // The chat list is a LazyColumn: the same diagram re-enters composition on every scroll pass.
    // A synchronous cache probe lets us seed produceState with the finished SVG so a revisit never
    // flashes "Rendering diagram..." nor pays for another mutex-serialized JNI render.
    val cached = remember(source, style) { DesktopMermaidRenderCache.get(source, style) }
    val result by produceState<DesktopMermaidRenderResult>(
        initialValue = cached ?: DesktopMermaidRenderResult.Loading,
        source,
        style,
    ) {
        if (cached != null) return@produceState
        value = withContext(Dispatchers.Default) {
            DesktopMermaidNativeBridge.renderToPng(source, style).also { rendered ->
                // Only successful renders are worth keeping; failures may be transient (library
                // still loading, native error state) and must stay retryable.
                if (rendered is DesktopMermaidRenderResult.Rendered) {
                    DesktopMermaidRenderCache.put(source, style, rendered)
                }
            }
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        color = colors.surfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Column {
            MermaidHeader()
            when (val current = result) {
                DesktopMermaidRenderResult.Loading -> Text(
                    text = "Rendering diagram...",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
                is DesktopMermaidRenderResult.Rendered -> MermaidImage(current.png, source)
                is DesktopMermaidRenderResult.Failed -> MermaidSourceFallback(source, current.reason)
            }
        }
    }
}

@Composable
private fun MermaidHeader() {
    // No copy affordance here: the message-level "Copy response" pill floats at
    // the same top-right corner and already includes the mermaid fence source,
    // so a second glyph read as a confusing duplicate.
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Text(
            text = "mermaid",
            modifier = Modifier.weight(1f).padding(vertical = 10.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MermaidImage(png: ByteArray, source: String) {
    // The native renderer rasterizes with real system fonts (skiko's SVGDOM has no font manager,
    // so the old SVG path silently dropped every label). Decoding the PNG is cheap relative to the
    // render and is remembered per composition; the bytes themselves live in the process-wide cache.
    val bitmap = remember(png) {
        runCatching {
            org.jetbrains.skia.Image.makeFromEncoded(png).toComposeImageBitmap()
        }.getOrNull()
    }
    if (bitmap == null) {
        MermaidSourceFallback(source, "Rendered image could not be decoded")
        return
    }
    val aspectRatio = mermaidAspectRatio(bitmap)
    // Size from the diagram's intrinsic aspect ratio at the width we were actually granted (not a
    // fixed reference width). Height is capped at a screenful; past that, ContentScale.Fit shrinks
    // the whole diagram to fit rather than cropping it or letting it swallow the viewport.
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val drawWidth = (maxWidth - MermaidCanvasPadding * 2).coerceAtLeast(1.dp)
        val drawHeight = (drawWidth.value / aspectRatio)
            .coerceIn(MermaidMinDrawHeight, MermaidMaxDrawHeight)
            .dp
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .height(drawHeight + MermaidCanvasPadding * 2)
                .padding(MermaidCanvasPadding)
                .semantics { contentDescription = "Mermaid diagram:\n$source" },
        )
    }
}

private val MermaidCanvasPadding: Dp = 12.dp

/**
 * Floor keeps single-node diagrams from collapsing. The ceiling keeps tall
 * flowcharts inside a screenful: ContentScale.Fit scales the whole diagram
 * down to fit the capped box, so everything stays visible at once instead of
 * the block swallowing the viewport.
 */
private const val MermaidMinDrawHeight = 120f
private const val MermaidMaxDrawHeight = 560f

private fun mermaidAspectRatio(bitmap: ImageBitmap): Float =
    if (bitmap.width > 0 && bitmap.height > 0) {
        bitmap.width.toFloat() / bitmap.height.toFloat()
    } else {
        16f / 9f
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

    /** PNG bytes from the native rasterizer. Identity equality is fine: instances are cache-shared. */
    class Rendered(val png: ByteArray) : DesktopMermaidRenderResult

    data class Failed(val reason: String) : DesktopMermaidRenderResult
}

/**
 * Process-wide LRU of successful renders. Keyed by source *and* style because the same diagram must
 * be re-rendered when the theme palette changes.
 */
private object DesktopMermaidRenderCache {
    private const val MAX_ENTRIES = 64

    private data class Key(val source: String, val style: DesktopMermaidStyle)

    // accessOrder = true so a scroll-back counts as a use and keeps the entry hot.
    private val entries = object : LinkedHashMap<Key, DesktopMermaidRenderResult.Rendered>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<Key, DesktopMermaidRenderResult.Rendered>): Boolean =
            size > MAX_ENTRIES
    }

    // Reads happen on the composition thread, writes on Dispatchers.Default; LinkedHashMap is not
    // thread-safe and even get() mutates access order, so every touch is guarded by the same lock.
    fun get(source: String, style: DesktopMermaidStyle): DesktopMermaidRenderResult.Rendered? =
        synchronized(entries) { entries[Key(source, style)] }

    fun put(source: String, style: DesktopMermaidStyle, rendered: DesktopMermaidRenderResult.Rendered) {
        synchronized(entries) { entries[Key(source, style)] = rendered }
    }
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

    /**
     * Rasterization target width. Fixed rather than measured so the cache key stays width-free
     * (window resizes don't trigger re-renders); at 1600px a diagram stays sharp up to ~800dp of
     * column width at 2x density, and ContentScale.Fit handles everything narrower.
     */
    private const val TARGET_WIDTH_PX = 1600

    suspend fun renderToPng(source: String, style: DesktopMermaidStyle): DesktopMermaidRenderResult {
        if (!available) return DesktopMermaidRenderResult.Failed("Desktop Mermaid renderer is unavailable")
        // Native LAST_ERROR is process-global; serialize renders across diagrams.
        return renderMutex.withLock {
            runCatching {
                val png = MermaidNativeRenderer.nativeRenderToPng(
                    source = source,
                    darkTheme = style.darkTheme,
                    textArgb = style.textArgb,
                    borderArgb = style.borderArgb,
                    surfaceArgb = style.surfaceArgb,
                    primaryArgb = style.primaryArgb,
                    secondaryArgb = style.secondaryArgb,
                    tertiaryArgb = style.tertiaryArgb,
                    targetWidthPx = TARGET_WIDTH_PX,
                )
                if (png == null || png.isEmpty()) {
                    DesktopMermaidRenderResult.Failed(
                        MermaidNativeRenderer.nativeTakeLastError().orEmpty().ifBlank { "Renderer returned no image" },
                    )
                } else {
                    DesktopMermaidRenderResult.Rendered(png)
                }
            }.getOrElse { DesktopMermaidRenderResult.Failed(it.message ?: it::class.java.simpleName) }
        }
    }
}
