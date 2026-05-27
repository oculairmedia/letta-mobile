package com.letta.mobile.feature.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.data.tooloutput.DiffFile
import com.letta.mobile.data.tooloutput.DiffLine
import com.letta.mobile.data.tooloutput.DiffLineType
import com.letta.mobile.data.tooloutput.ToolOutputBlock
import com.letta.mobile.ui.theme.LettaChatTheme
import com.letta.mobile.ui.theme.LettaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@Tag("unit")
class ToolOutputRendererTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun expandedJsonOutputShowsStructuredHeaderAndPrettyBody() {
        composeRule.setToolOutputContent(
            raw = """{"ok":true,"count":2}""",
            expanded = true,
        )

        composeRule.onNodeWithText("\"ok\": true", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("\"count\": 2", substring = true).assertIsDisplayed()
    }

    @Test
    fun expandedDiffOutputShowsDiffHeaderAndLines() {
        composeRule.setToolOutputContent(
            raw = """
                diff --git a/a.txt b/a.txt
                --- a/a.txt
                +++ b/a.txt
                @@ -1 +1 @@
                -old
                +new
            """.trimIndent(),
            expanded = true,
        )

        composeRule.onNodeWithText("a.txt").assertIsDisplayed()
        composeRule.onNodeWithText("+new").assertIsDisplayed()
    }

    @Test
    fun collapsedPlainOutputShowsCompactPreview() {
        composeRule.setToolOutputContent(
            raw = "ordinary prose output",
            expanded = false,
        )

        composeRule.onNodeWithText("ordinary prose output").assertIsDisplayed()
    }

    @Test
    fun collapsedPreviewClipsLongLogicalLineByVisualLines() {
        val raw = (0..220).joinToString(" ") { index -> "token$index" }

        composeRule.setContent {
            LettaTheme(
                appTheme = AppTheme.LIGHT,
                themePreset = ThemePreset.DEFAULT,
                dynamicColor = false,
            ) {
                LettaChatTheme {
                    Box(modifier = Modifier.width(180.dp)) {
                        ToolOutputRenderer(
                            raw = raw,
                            expanded = false,
                            isError = false,
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithText("token0", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("token80", substring = true).assertDoesNotExist()
    }

    @Test
    fun outputCanToggleBetweenCollapsedPreviewAndExpandedBody() {
        var expanded by mutableStateOf(false)
        composeRule.setToolOutputContent(
            raw = "first line\nsecond line\nthird line",
            expandedProvider = { expanded },
        )

        composeRule.onNodeWithText("first line", substring = true).assertIsDisplayed()

        composeRule.runOnIdle { expanded = true }
        composeRule.onNodeWithText("second line", substring = true).assertIsDisplayed()

        composeRule.runOnIdle { expanded = false }
        composeRule.onNodeWithText("first line", substring = true).assertIsDisplayed()
    }

    @Test
    fun expandedOutputDoesNotExposeHorizontalScrollSemantics() {
        composeRule.setToolOutputContent(
            raw = "x".repeat(600),
            expanded = true,
        )

        composeRule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange),
            useUnmergedTree = true,
        ).assertCountEquals(0)
    }

    @Test
    fun longPressCopiesRawOutputInsteadOfRenderedText() {
        val raw = "\u001B[31mERROR\u001B[0m raw output"
        var clipboardManager: ClipboardManager? = null
        composeRule.setContent {
            clipboardManager = LocalClipboardManager.current
            LettaTheme(
                appTheme = AppTheme.LIGHT,
                themePreset = ThemePreset.DEFAULT,
                dynamicColor = false,
            ) {
                LettaChatTheme {
                    ToolOutputRenderer(
                        raw = raw,
                        expanded = true,
                        isError = false,
                    )
                }
            }
        }

        composeRule.onNodeWithText("ERROR raw output", substring = true)
            .performTouchInput { longClick() }

        composeRule.runOnIdle {
            assertEquals(raw, clipboardManager?.getText()?.text)
        }
    }

    @Test
    fun initialLargeOutputUsesBoundedPlainPreviewAndPreservesRaw() {
        val raw = "\u001B[31mx\u001B[0m" + "y".repeat(ToolOutputMaxRenderedChars + 1)

        val document = initialToolOutputDocument(raw)
        val block = document.blocks.single() as ToolOutputBlock.PlainText

        assertEquals(raw, document.raw)
        assertTrue(document.isTruncated)
        assertEquals(raw.length - block.raw.length, document.omittedCharCount)
        assertTrue(block.raw.length <= ToolOutputMaxRenderedChars)
        assertTrue("ANSI escape sequences should be stripped from the bounded preview", !block.raw.contains("\u001B"))
    }

    @Test
    fun cachedDocumentReusesParsedToolOutputAcrossCompositions() {
        clearToolOutputRenderCachesForTest()
        val raw = """{"ok":true,"count":2}"""

        val first = cachedToolOutputDocument(raw)
        val second = cachedToolOutputDocument(raw)

        assertSame(first, second)
    }

    @Test
    fun cachedDocumentSkipsOversizedRawOutput() {
        clearToolOutputRenderCachesForTest()
        val raw = "x".repeat(ToolOutputDocumentMaxCacheableRawChars + 1)

        val first = cachedToolOutputDocument(raw)
        val second = cachedToolOutputDocument(raw)

        assertNotSame(first, second)
        assertEquals(raw, first.raw)
        assertEquals(raw, second.raw)
    }

    @Test
    fun cachedHighlightSpansReuseTokenizationAcrossCompositions() {
        clearToolOutputRenderCachesForTest()
        val json = """{"ok":true,"count":2}"""

        val first = cachedToolOutputHighlightSpans(json, ToolOutputHighlightMode.Json)
        val second = cachedToolOutputHighlightSpans(json, ToolOutputHighlightMode.Json)

        assertSame(first, second)
    }

    @Test
    fun limitRenderedTextCapsLinesAndCharactersDeterministically() {
        val oversized = (0..ToolOutputMaxRenderedLines).joinToString("\n") { index ->
            "line-$index-" + "x".repeat(ToolOutputMaxRenderedChars)
        }

        val limited = limitRenderedText(oversized)

        assertTrue(limited.text.length <= ToolOutputMaxRenderedChars)
        assertEquals(1, limited.omittedLines)
        assertTrue(limited.omittedChars > 0)
    }

    @Test
    fun limitRenderedTextAcceptsPreviewSizedBudgets() {
        val limited = limitRenderedText(
            text = "first\nsecond\nthird",
            maxLines = 2,
            maxChars = 9,
        )

        assertEquals("first\nsec", limited.text)
        assertEquals(1, limited.omittedLines)
        assertTrue(limited.omittedChars > 0)
    }

    @Test
    fun limitDiffFilesForRenderingCapsLinesGloballyAcrossFiles() {
        val files = listOf(
            diffFile("a.txt", 3),
            diffFile("b.txt", 3),
            diffFile("c.txt", 3),
        )

        val limited = limitDiffFilesForRendering(files, maxLines = 5)

        assertEquals(2, limited.files.size)
        assertEquals(listOf(3, 2), limited.files.map { it.lines.size })
        assertEquals(4, limited.omittedLines)
    }

    @Test
    fun jsonHighlightingClassifiesKeysValuesAndPunctuation() {
        val json = """
            {
              "ok": true,
              "count": 2,
              "name": "agent"
            }
        """.trimIndent()

        val spans = highlightToolOutputText(json, ToolOutputHighlightMode.Json)

        assertTrue(spans.containsSpan(json, "\"ok\"", ToolOutputHighlightKind.Key))
        assertTrue(spans.containsSpan(json, "true", ToolOutputHighlightKind.Literal))
        assertTrue(spans.containsSpan(json, "2", ToolOutputHighlightKind.Number))
        assertTrue(spans.containsSpan(json, "\"agent\"", ToolOutputHighlightKind.StringLiteral))
        assertTrue(spans.containsSpan(json, "{", ToolOutputHighlightKind.Punctuation))
    }

    @Test
    fun codeHighlightingClassifiesCommentsStringsKeywordsFunctionsAndNumbers() {
        val code = """
            fun run(count: Int) {
                // say hello
                println("hello ${'$'}count")
                return 42
            }
        """.trimIndent()

        val spans = highlightToolOutputText(code, ToolOutputHighlightMode.Code, languageHint = "kotlin")

        assertTrue(spans.containsSpan(code, "fun", ToolOutputHighlightKind.Keyword))
        assertTrue(spans.containsSpan(code, "run", ToolOutputHighlightKind.Function))
        assertTrue(spans.containsSpan(code, "// say hello", ToolOutputHighlightKind.Comment))
        assertTrue(spans.containsSpan(code, "\"hello ${'$'}count\"", ToolOutputHighlightKind.StringLiteral))
        assertTrue(spans.containsSpan(code, "42", ToolOutputHighlightKind.Number))
    }

    @Test
    fun shellAndLogHighlightingClassifiesPromptCommandAndSeverityWords() {
        val shell = "\$ rg --files\nERROR failed\nOK done"

        val spans = highlightToolOutputText(shell, ToolOutputHighlightMode.Shell)

        assertTrue(spans.containsSpan(shell, "\$ ", ToolOutputHighlightKind.Prompt))
        assertTrue(spans.containsSpan(shell, "rg", ToolOutputHighlightKind.Keyword))
        assertTrue(spans.containsSpan(shell, "--files", ToolOutputHighlightKind.Literal))
        assertTrue(spans.containsSpan(shell, "ERROR", ToolOutputHighlightKind.Error))
        assertTrue(spans.containsSpan(shell, "OK", ToolOutputHighlightKind.Success))
    }

    @Test
    fun tableHighlightingClassifiesHeaderAndNumbers() {
        val table = """
            name  count
            api   12
        """.trimIndent()

        val spans = highlightToolOutputText(table, ToolOutputHighlightMode.Table)

        assertTrue(spans.containsSpan(table, "name  count", ToolOutputHighlightKind.Header))
        assertTrue(spans.containsSpan(table, "12", ToolOutputHighlightKind.Number))
    }

    @Test
    fun highlighterCapsDenseSpanOutput() {
        val dense = buildString {
            repeat(ToolOutputMaxHighlightSpans + 100) {
                append("{}")
            }
        }

        val spans = highlightToolOutputText(dense, ToolOutputHighlightMode.Code, languageHint = "kotlin")

        assertTrue(spans.size <= ToolOutputMaxHighlightSpans)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.setToolOutputContent(
        raw: String,
        expanded: Boolean,
    ) {
        setToolOutputContent(raw = raw, expandedProvider = { expanded })
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.setToolOutputContent(
        raw: String,
        expandedProvider: () -> Boolean,
    ) {
        setContent {
            LettaTheme(
                appTheme = AppTheme.LIGHT,
                themePreset = ThemePreset.DEFAULT,
                dynamicColor = false,
            ) {
                LettaChatTheme {
                    ToolOutputRenderer(
                        raw = raw,
                        expanded = expandedProvider(),
                        isError = false,
                    )
                }
            }
        }
    }

    private fun List<ToolOutputHighlightSpan>.containsSpan(
        source: String,
        text: String,
        kind: ToolOutputHighlightKind,
    ): Boolean = any { span ->
        span.kind == kind && source.substring(span.start, span.end) == text
    }

    private fun diffFile(path: String, lineCount: Int): DiffFile = DiffFile(
        oldPath = path,
        newPath = path,
        lines = List(lineCount) { index ->
            DiffLine(DiffLineType.Context, " $path line $index")
        },
    )
}
