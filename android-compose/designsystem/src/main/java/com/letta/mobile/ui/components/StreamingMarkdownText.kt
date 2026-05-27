package com.letta.mobile.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import com.letta.mobile.ui.text.ChatTextLayoutMode
import com.letta.mobile.ui.text.rememberChatTextGeometryMeasurer
import com.letta.mobile.ui.theme.LocalChatFontScale
import com.letta.mobile.ui.theme.LocalChatIsPinching
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Streaming-aware markdown renderer with a stable committed-prefix path.
 *
 * The stream is rate-limited to a paint cadence, then split at the last
 * safe block boundary. Completed markdown blocks render as independently
 * keyed [MarkdownText] instances, while only the active tail keeps
 * re-parsing as chunks arrive. This preserves live inline formatting in the
 * active block while preventing already-complete code blocks / paragraphs /
 * tables from being torn down on every tick.
 *
 * Earlier designs ([letta-mobile-c8of] ALT-2 + [letta-mobile-flk.1]) also
 * split at a safe boundary, but rendered the tail as plain text. That
 * achieved per-block render skipping but produced two visible regressions:
 *  - Short single-paragraph replies never had a `\n\n` boundary, so the
 *    boundary stayed at 0 and the entire bubble rendered as plain Text
 *    with literal asterisks until the stream completed.
 *  - When the boundary advanced mid-stream, content briefly appeared to
 *    duplicate as the tail moved into a new prefix block while a new
 *    tail was being computed for the next paragraph (the chunk-
 *    accumulation regression).
 *
 * @param text the live, possibly-incomplete markdown text. Safe to pass on
 *   every chunk.
 * @param modifier forwarded to the outer container.
 * @param textColor base text color.
 * @param tailTransform optional decorator applied to the active tail only —
 *   e.g. word-boundary holdback + streaming cursor (`streamingDisplayText`
 *   from MessageContentFactory).
 * @param isStreaming true while the stream is still open and chunks are arriving.
 *   When false, animateContentSize is applied so the bubble smoothly reaches its
 *   final height in one animation rather than fighting ~50ms-increment jumps that
 *   FastOutSlowInEasing cannot catch.
 * @param cursorText optional cursor glyph appended after the active tail. Pass null
 *   to omit the cursor entirely.
 * @param cursorAlpha caller-owned cursor visibility value. Values at or below
 *   the threshold suppress the glyph.
 */
@Composable
fun StreamingMarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    tailTransform: (String) -> String = { it },
    cursorText: String? = null,
    cursorAlpha: Float = 1f,
    deferUnstableMarkdown: Boolean = true,
    stabilizeTables: Boolean = false,
    isStreaming: Boolean = true,
) {
    if (text.isEmpty()) return

    // Stable block-prefix renderer.
    //
    // The original c8of design split the text at the last safe paragraph
    // boundary into a markdown-rendered prefix and a plain-text tail.
    // That worked when responses had paragraph breaks but completely
    // failed for short single-paragraph replies (boundary stayed at 0,
    // entire bubble rendered as plain Text with literal asterisks).
    //
    // The later full-bubble MarkdownText pass fixed that inline-formatting
    // regression but still re-emitted the entire markdown subtree on each
    // tick, which is especially visible for streaming code blocks, lists,
    // and tables. The current design keeps the committed prefix stable and
    // limits re-parse churn to the active tail.
    // letta-mobile-flk2 (revision 3): paint-rate coalescer using
    // rememberUpdatedState + a single long-running polling effect.
    //
    // The flk.3.1 design re-rendered the full MarkdownText subtree on
    // every char. With the new ClientModeStreamSmoother delivering
    // chars at 90–180 cps, the per-char re-parse caused visible
    // flicker.
    //
    // Revision 1 used `LaunchedEffect(text) { delay(50) }` which
    // cancelled+restarted on every char and never completed until
    // the stream ended (chars arrive every ~10ms; 50ms delay never
    // wins) — content popped in at end-of-stream.
    //
    // Revision 2 used `mutableStateOf` + write-during-composition,
    // which is illegal in Compose and fails silently or freezes.
    // Symptom: first three lines appear (initial composition), then
    // stalled poll loop reads stale state, then content pops at end.
    //
    // Revision 3 uses `rememberUpdatedState(text)` — Compose's
    // canonical pattern for "the long-running effect needs to read
    // the latest value of an input without restarting". The state
    // is updated via Compose's snapshot system at the END of
    // composition, so the polling effect reads the freshest value
    // safely on each tick.
    val latestText by rememberUpdatedState(text)

    // LazyColumn can dispose off-screen message items and recompose them when the user scrolls
    // back. Hydrated/non-streaming table messages must not replay the streaming reveal from an
    // empty string on every recycle, so initialize `displayed` with the CURRENT text once and
    // let the tick loop catch it up from there.
    //
    // letta-mobile-yh0c: previously this `remember` was keyed on
    // `(isStreaming, displayedInitialTextKey)`, which re-initialized
    // displayed to "" every time isStreaming flipped true. That made any
    // brief flicker of the upstream `isStreaming` flag (e.g. when
    // TimelineSendCoordinator sets it before the optimistic user Local
    // is appended) wipe a fully-rendered prior message and replay it
    // char-by-char. Initializing only on the FIRST composition means
    // mid-life flips don't reset displayed; the LaunchedEffect below
    // catches displayed up to latestText smoothly without going through
    // an empty intermediate state.
    var displayed by remember { mutableStateOf(text) }
    LaunchedEffect(isStreaming) {
        if (!isStreaming) return@LaunchedEffect
        // Tick at fixed cadence. Push displayed = latest whenever
        // they differ. When text stops changing (stream end),
        // `displayed` catches up within one PAINT_INTERVAL_MS window
        // and the loop idles cheaply (no MarkdownText re-render
        // when value is unchanged — Compose elides via structural
        // equality).
        while (true) {
            if (displayed != latestText) {
                displayed = latestText
            }
            delay(PAINT_INTERVAL_MS)
        }
    }

    LaunchedEffect(latestText, isStreaming) {
        if (!isStreaming && displayed != latestText) {
            displayed = latestText
        }
    }

    // letta-mobile-gqaw0: stable markdown document renderer.
    //
    // Architecture: the stream is parsed into typed block nodes with stable ids.
    // A syntax cue (``` fence, list marker, heading marker, table separator)
    // opens its block immediately; later chunks append to that same block.
    // Unchanged blocks keep their keys and object identity, so only the active
    // block should recompose at paint cadence.
    //
    // Why this is necessary: revision 14 instrumentation measured
    // ~17-18Hz (50ms coalesce) and ~10Hz (100ms coalesce) of
    // MarkdownTextRaw recompositions during streaming. At BOTH rates
    // Emmanuel observed flicker (18Hz "jarring", 10Hz "less jarring,
    // still apparent"). Single-pass MarkdownText cannot achieve
    // no-flicker because mikepenz tears down + re-emits the subtree on
    // every paint tick regardless of cadence — flicker is content-
    // independent ("every message"), confirming subtree rebuild rather
    // than mid-construct parse churn.
    //
    // The repair pass is render-only: source text and block ids stay untouched.
    // When the real closer arrives, the synthetic closer disappears on the next
    // paint tick inside the same block key.
    val documentState = remember { StreamingMarkdownDocumentState() }
    val document = remember(displayed) { documentState.update(displayed) }
    val blocksForRender = document.blocks
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val chatFontScale = LocalChatFontScale.current
    val geometryMeasurer = rememberChatTextGeometryMeasurer()
    val activeTextStyle = MaterialTheme.typography.bodyMedium.copy(color = textColor)
    val scaledDensity = remember(density, chatFontScale) {
        Density(
            density = density.density,
            fontScale = density.fontScale * chatFontScale,
        )
    }
    var measuredContentWidthPx by remember { mutableIntStateOf(0) }
    val activeLineCount = remember(
        blocksForRender,
        measuredContentWidthPx,
        activeTextStyle,
        scaledDensity.density,
        scaledDensity.fontScale,
        layoutDirection,
        isStreaming,
    ) {
        val activeBlock = blocksForRender.lastOrNull()
        if (
            isStreaming &&
            measuredContentWidthPx > 0 &&
            activeBlock != null &&
            activeBlock.supportsPlainTextHeightPrediction()
        ) {
            geometryMeasurer.measure(
                text = activeBlock.source,
                style = activeTextStyle,
                widthPx = measuredContentWidthPx,
                density = scaledDensity,
                layoutDirection = layoutDirection,
                mode = ChatTextLayoutMode.MarkdownParagraph,
            ).lineCount
        } else {
            null
        }
    }
    // letta-mobile-mmnn fix: stable height floor that only updates at committed-block boundaries.
    //
    // The original heightIn(min=settledPx) caused flicker because settledHeightPx changed on
    // every text tick (40→106→148→194...) — each change propagated as a new heightIn(min=N)
    // modifier, triggering LazyColumn re-measure at 50ms cadence.
    //
    // The fixed-height(height()) approach clipped the Column at the first-measured height
    // (e.g. 43px) and nothing rendered until scrolling forced a re-measure.
    //
    // This approach: heightIn(min=stableFloor) where stableFloor only updates when committed
    // block keys change (paragraph boundary or completed table-row advance) or, for simple
    // active prose, when the geometry cache says the visual line count changed. Between those
    // geometry boundaries, active-tail growth must NOT change the heightIn modifier; otherwise
    // LazyColumn remeasures the whole message at the paint cadence and styled tables visibly flash.
    var stableFloorHeightPx by remember { mutableStateOf(0) }
    var stableFloorBoundaryToken by remember { mutableStateOf<String?>(null) }
    val committedBoundaryToken = remember(document, isStreaming, activeLineCount) {
        document.stableHeightToken(
            isStreaming = isStreaming,
            activeLineCount = activeLineCount,
        )
    }
    val heightFloorModifier = if (stableFloorHeightPx > 0) {
        with(density) { Modifier.heightIn(min = stableFloorHeightPx.toDp()) }
    } else {
        Modifier
    }
    // letta-mobile-9hcg.b: also skip animateContentSize while the user is
    // pinching. The graphicsLayer scale runs at the compositor and never
    // mutates layout sizes, but if a content append happens to land while
    // the gesture is active animateContentSize would tween that delta on
    // top of the compositor scale and reproduce the choppy "phase-2"
    // judder we ship-blocked on in d9zy.5.
    val isPinching = LocalChatIsPinching.current
    val heightAnimation = if (isStreaming || isPinching) {
        Modifier
    } else {
        Modifier.animateContentSize(
            animationSpec = tween(
                durationMillis = 260,
                easing = FastOutSlowInEasing,
            ),
        )
    }

    Column(
        modifier = modifier
            .then(heightFloorModifier)
            .then(heightAnimation)
            .onSizeChanged { size ->
                measuredContentWidthPx = size.width
                if (committedBoundaryToken != stableFloorBoundaryToken) {
                    stableFloorBoundaryToken = committedBoundaryToken
                    // Only grow the floor upward. Never shrink it.
                    if (size.height > stableFloorHeightPx) {
                        stableFloorHeightPx = size.height
                    }
                }
            },
    ) {
        StreamingMarkdownDocumentBlocks(
            blocks = blocksForRender,
            textColor = textColor,
            stabilizeTables = stabilizeTables,
            tailTransform = tailTransform,
            cursorText = cursorText,
            cursorAlpha = cursorAlpha,
            repairIncompleteMarkdown = deferUnstableMarkdown,
        )
    }
}

private const val PAINT_INTERVAL_MS = 50L

@Composable
private fun StreamingMarkdownDocumentBlocks(
    blocks: List<StreamingMarkdownDocumentBlock>,
    textColor: Color,
    stabilizeTables: Boolean,
    tailTransform: (String) -> String,
    cursorText: String?,
    cursorAlpha: Float,
    repairIncompleteMarkdown: Boolean,
) {
    // Each block renders in its own key group. Unchanged blocks keep the same object and key, while
    // the final active block alone receives tail transforms and cursor injection.
    blocks.forEachIndexed { index, block ->
        val isActiveBlock = index == blocks.lastIndex
        val renderSource = remember(block, isActiveBlock, tailTransform) {
            if (isActiveBlock) tailTransform(block.source) else block.source
        }
        val activeCursor = cursorText
            ?.takeIf {
                isActiveBlock &&
                    block.allowsInlineCursor &&
                    cursorAlpha > 0.001f &&
                    (renderSource.isNotEmpty() || blocks.isNotEmpty())
            }
        val repairedMarkdown = remember(renderSource, activeCursor, isActiveBlock, repairIncompleteMarkdown) {
            val markdown = if (repairIncompleteMarkdown && isActiveBlock) {
                block.renderMarkdownSource(renderSource)
            } else {
                renderSource
            }
            markdown + (activeCursor ?: "")
        }
        val parsedTable = if (
            activeCursor == null &&
            stabilizeTables &&
            block.kind == StreamingMarkdownBlockKind.Table &&
            renderSource.looksLikeMarkdownTable()
        ) {
            parseMarkdownTable(renderSource)
        } else {
            null
        }

        if (parsedTable != null) {
            key(block.key) {
                StreamingMarkdownTable(
                    table = parsedTable,
                    textColor = textColor,
                )
            }
        } else {
            key(block.key) {
                MarkdownText(
                    text = repairedMarkdown,
                    textColor = textColor,
                )
            }
        }
    }
}

@Composable
private fun StreamingMarkdownTable(
    table: ParsedMarkdownTable,
    textColor: Color,
) {
    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    val headerContainer = MaterialTheme.colorScheme.surfaceContainerHigh
    val bodyContainer = LettaCardDefaults.listContainerColor
    val cellTextStyle = MaterialTheme.typography.bodySmall

    Column(
        modifier = Modifier
            .padding(vertical = 4.dp)
            .border(1.dp, outlineColor),
    ) {
        key("header") {
            StreamingMarkdownTableRow(
                cells = table.header,
                textColor = textColor,
                containerColor = headerContainer,
                outlineColor = outlineColor,
                fontWeight = FontWeight.SemiBold,
                cellTextStyle = cellTextStyle,
            )
        }
        table.rows.forEach { row ->
            key(row.key) {
                StreamingMarkdownTableRow(
                    cells = row.cells,
                    textColor = textColor,
                    containerColor = bodyContainer,
                    outlineColor = outlineColor,
                    fontWeight = null,
                    cellTextStyle = cellTextStyle,
                )
            }
        }
    }
}

@Composable
private fun StreamingMarkdownTableRow(
    cells: List<String>,
    textColor: Color,
    containerColor: Color,
    outlineColor: Color,
    fontWeight: FontWeight?,
    cellTextStyle: androidx.compose.ui.text.TextStyle,
) {
    Row(modifier = Modifier.background(containerColor)) {
        cells.forEach { cell ->
            Text(
                text = cell,
                color = textColor,
                style = if (fontWeight != null) cellTextStyle.copy(fontWeight = fontWeight) else cellTextStyle,
                modifier = Modifier
                    .weight(1f)
                    .widthIn(min = 56.dp)
                    .border(0.5.dp, outlineColor)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}
