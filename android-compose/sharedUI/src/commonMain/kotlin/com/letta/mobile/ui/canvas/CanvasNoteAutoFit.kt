package com.letta.mobile.ui.canvas

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import com.letta.mobile.data.canvas.CanvasGeometryOwner
import com.letta.mobile.data.canvas.CanvasSceneDocument
import com.letta.mobile.data.canvas.CanvasTextStyle
import com.letta.mobile.ui.theme.LettaDimens
import io.github.linreal.cascade.editor.core.Block
import io.github.linreal.cascade.editor.serialization.DocumentSchema
import io.github.linreal.cascade.editor.ui.CascadeDocumentPreview
import io.github.linreal.cascade.editor.ui.CascadeDocumentPreviewConfig
import io.github.linreal.cascade.editor.ui.ExperimentalCascadePreviewApi
import kotlin.math.ceil

/**
 * Auto-fit for note cards (canvas_compose C6, letta-mobile-bglj6.11; absorbs letta-mobile-8tlf9).
 *
 * The stored frame of an AUTO-owned note is a RESERVATION (plan section 3.4, decision D3): the
 * compose layer booked a height it could not measure. The card fits its content within it: it
 * shrinks to the content when the content is shorter; when the content is longer it first sets the
 * type smaller, down to [FONT_FLOOR], and only then grows taller than the booking, so an AUTO note
 * is never clipped. All of that is local and visual: nothing is written back, so Android and Skiko
 * can measure the same words differently without a single op, and a render never adds history.
 *
 * EXPLICIT and USER frames are drawn verbatim (the escape hatch and a person's resize); when their
 * content is longer than the frame the editor scrolls inside it, so nothing is unreachable.
 */
internal enum class NoteSizing {
    /** Fit the content within the reservation (AUTO, compose-made with no owner, or frameless). */
    FIT,

    /** The stored frame as it is (EXPLICIT, USER, or a person's note from before owners). */
    VERBATIM,
}

/** How [document]'s card is sized; a shape's label is always verbatim (its shape owns the box). */
internal fun noteSizing(document: CanvasSceneDocument, isLabel: Boolean = false): NoteSizing = when {
    isLabel -> NoteSizing.VERBATIM
    document.frame == null -> NoteSizing.FIT
    document.owner == CanvasGeometryOwner.AUTO -> NoteSizing.FIT
    document.owner == null && document.compose != null -> NoteSizing.FIT
    else -> NoteSizing.VERBATIM
}

/** What a fitted card draws at: its [height] in world units and the [fontScale] its type is set at. */
@Immutable
internal data class NoteFit(val height: Float, val fontScale: Float) {
    /** True when the content needed more than the reservation even at the font floor. */
    fun grewPast(reserved: Float): Boolean = height > reserved
}

/**
 * The note card's chrome, the one source the renderer draws and measures with. The values are
 * [Dp] because the card is composed under [CanvasWorldDensity], where one dp is one world unit:
 * `handleHeight.value` IS the handle's height in world units, on every display.
 */
internal object CanvasNoteChrome {
    /** The handle bar over a (non-plain) note's body. */
    val handleHeight: Dp get() = LettaDimens.Control.iconButton

    /** Vertical padding above and below the body text. */
    val bodyVertical: Dp get() = LettaDimens.Space.xs

    /** Horizontal padding of the body text: the leading side of a plain text block is wider. */
    val bodyStart: Dp get() = LettaDimens.Space.md
    val plainBodyStart: Dp get() = LettaDimens.Space.lg
    val bodyEnd: Dp get() = LettaDimens.Space.md

    /** A shape label's horizontal inset. */
    val labelHorizontal: Dp get() = LettaDimens.Space.xs

    /** The padding around a card's body text. */
    fun bodyPadding(plain: Boolean, isLabel: Boolean): PaddingValues = when {
        isLabel -> PaddingValues(horizontal = labelHorizontal)
        else -> PaddingValues(
            start = if (plain) plainBodyStart else bodyStart,
            end = bodyEnd,
            top = bodyVertical,
            bottom = bodyVertical,
        )
    }
}

internal object CanvasNoteAutoFit {
    /** The smallest the type is set to fit a reservation, as a fraction of the document's size. */
    const val FONT_FLOOR = 0.8f

    /** The scales tried, largest first; the last is [FONT_FLOOR]. */
    val FONT_SCALES: List<Float> = listOf(1f, 0.9f, FONT_FLOOR)

    /**
     * The fit for a card whose booked height is [reserved] and whose chrome takes [chrome], given
     * [bodyAt] (the body's height at a font scale). Tries [scales] largest first and takes the first
     * that fits; when none does, the last (the floor) and as tall as it needs. Heights are rounded
     * up to whole units, so a fit is stable from one measurement to the next.
     */
    fun resolve(
        reserved: Float,
        chrome: Float,
        scales: List<Float> = FONT_SCALES,
        bodyAt: (Float) -> Float,
    ): NoteFit {
        require(scales.isNotEmpty())
        var last = NoteFit(reserved, scales.first())
        for (scale in scales) {
            val height = ceil(chrome + bodyAt(scale))
            last = NoteFit(height, scale)
            if (height <= reserved) return last
        }
        return last
    }

    /** [style] with its type scaled by [fitScale] on top of whatever scale it already had. */
    fun scaledStyle(style: CanvasTextStyle?, fitScale: Float): CanvasTextStyle? {
        if (fitScale == 1f) return style
        val base = style ?: CanvasTextStyle()
        return base.copy(fontScale = (base.fontScale ?: 1f) * fitScale)
    }

    /** The blocks of a stored document, decoded now (not in an effect), so they can be measured at once. */
    fun blocksOf(json: String): List<Block> {
        if (json.isBlank()) return listOf(Block.paragraph(""))
        return runCatching { DocumentSchema.decodeFromString(json) }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: listOf(Block.paragraph(""))
    }
}

/** The font scale an auto-fitted card is drawn at; tests read it, nothing else does. */
internal val NoteFontScaleKey = SemanticsPropertyKey<Float>("NoteFontScale")
internal var SemanticsPropertyReceiver.noteFontScale by NoteFontScaleKey

/** What [NoteFitMeasurer] measures: everything the card's text is laid out from. */
@Immutable
internal data class NoteFitRequest(
    val json: String,
    val style: CanvasTextStyle?,
    val onLightSurface: Boolean,
    val plain: Boolean,
    /** The card's width and booked height, in world units (one px before the board's zoom; see CanvasWorldDensity). */
    val width: Float,
    val reserved: Float,
    /** Only this scale, while the card is being typed into: the type does not change size mid-sentence. */
    val fixedScale: Float? = null,
    /** Never shorter than this, while typing: deleting a line does not make the card jump. */
    val minHeight: Float = 0f,
)

/**
 * Measures a note's text for auto-fit and reports the [NoteFit] through [onFit]; it takes no space
 * and draws nothing. The same blocks the card shows are laid out read-only (the editor is a lazy
 * list that fills whatever it is given, so it has no height of its own, as in `VerticallyCentred`)
 * at the card's width and the card's padding, at each candidate font scale until one fits.
 *
 * Measured in world units ([InWorldUnits]), like the card it measures for, so the fit is the same
 * on every display and at every system font scale. It all happens in the layout phase, in a node
 * outside the zoomed card whose constraints never change with the zoom: a pan or zoom never
 * remeasures it, and [onFit] is only called with a new value when the text, width, style or
 * booking changed.
 */
@OptIn(ExperimentalCascadePreviewApi::class)
@Composable
internal fun NoteFitMeasurer(request: NoteFitRequest, onFit: (NoteFit) -> Unit) {
    val blocks = remember(request.json) { CanvasNoteAutoFit.blocksOf(request.json) }
    val content = NoteFitContent(
        blocks = blocks,
        registry = rememberCanvasBlockRegistry(),
        padding = CanvasNoteChrome.bodyPadding(request.plain, isLabel = false),
        chrome = if (request.plain) null else CanvasNoteChrome.handleHeight,
    )
    InWorldUnits { NoteFitLayout(request, content, onFit) }
}

/** What a fit lays out: the card's blocks with its block registry, its body padding and the handle above it. */
private class NoteFitContent(
    val blocks: List<Block>,
    val registry: io.github.linreal.cascade.editor.registry.BlockRegistry,
    val padding: PaddingValues,
    val chrome: Dp?,
)

@OptIn(ExperimentalCascadePreviewApi::class)
@Composable
private fun NoteFitLayout(request: NoteFitRequest, content: NoteFitContent, onFit: (NoteFit) -> Unit) {
    SubcomposeLayout(Modifier.clearAndSetSemantics {}) { _ ->
        val widthPx = request.width.toInt().coerceAtLeast(1)
        val loose = Constraints(minWidth = widthPx, maxWidth = widthPx, minHeight = 0, maxHeight = Constraints.Infinity)
        val chromePx = content.chrome?.toPx() ?: 0f
        val scales = request.fixedScale?.let { listOf(it) } ?: CanvasNoteAutoFit.FONT_SCALES
        val fit = CanvasNoteAutoFit.resolve(request.reserved, chromePx, scales) { scale ->
            val measurables = subcompose("fit-$scale") {
                val theme = rememberCascadeTheme(
                    forceLight = request.onLightSurface,
                    style = CanvasNoteAutoFit.scaledStyle(request.style, scale),
                )
                CascadeDocumentPreview(
                    blocks = content.blocks,
                    // Never placed and never read out: the card's own editor is the text a screen
                    // reader meets, so this copy must not show up as a second one.
                    modifier = Modifier.clearAndSetSemantics {}.fillMaxWidth().padding(content.padding),
                    registry = content.registry,
                    theme = theme,
                    // Unbounded: the default preview caps blocks and lines per block, which is a
                    // card summary, not the text the editor lays out.
                    config = CascadeDocumentPreviewConfig.Unbounded,
                )
            }
            (measurables.maxOfOrNull { it.measure(loose).height } ?: 0).toFloat()
        }
        onFit(if (fit.height < request.minHeight) fit.copy(height = request.minHeight) else fit)
        layout(0, 0) {}
    }
}
