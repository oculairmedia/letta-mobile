package com.letta.mobile.data.canvas.compose

import com.letta.mobile.data.canvas.CanvasToolContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The canvas_compose markdown subset (letta-mobile-bglj6.8): what it reads, and what it refuses and where. */
class CanvasComposeMarkdownTest {
    private val path = "/items/3/markdown"

    private fun blocks(markdown: String): List<MdBlock> {
        val parse = CanvasComposeMarkdown.parse(markdown, path)
        return assertIs<MdParse.Parsed>(parse, "expected $markdown to parse, got $parse").blocks
    }

    private fun refused(markdown: String): List<ComposeProblem> =
        assertIs<MdParse.Refused>(CanvasComposeMarkdown.parse(markdown, path), "expected a refusal of $markdown").problems

    /** Each problem as (code, offset), the offset read back from the message the agent sees. */
    private fun problems(markdown: String): List<Pair<String, Int>> = refused(markdown).map { problem ->
        assertEquals(path, problem.path)
        val offset = Regex("""at offset (\d+)""").find(problem.message)?.groupValues?.get(1)?.toInt()
        assertTrue(offset != null, "no offset in '${problem.message}'")
        problem.code to offset
    }

    private fun unsupportedAt(vararg offsets: Int) = offsets.map { ComposeProblemCode.UNSUPPORTED_MARKDOWN.name to it }

    private fun p(text: String, vararg spans: MdSpan) = MdBlock.Paragraph(MdText(text, spans.toList()))

    private fun t(text: String) = MdText(text)

    // --- blocks -------------------------------------------------------------------------------

    @Test
    fun paragraphsJoinTheirLinesWithASpaceAndEndAtABlankLine() {
        assertEquals(listOf(p("one two"), p("three")), blocks("one\ntwo\n\nthree"))
    }

    @Test
    fun atxHeadingsOneToThreeWithOptionalClosingHashes() {
        assertEquals(
            listOf(MdBlock.Heading(1, t("One")), MdBlock.Heading(2, t("Two")), MdBlock.Heading(3, t("Three #3"))),
            blocks("# One\n## Two ##\n### Three #3"),
        )
        assertEquals(listOf(p("#hashtag")), blocks("#hashtag"))
    }

    @Test
    fun bulletItemsWithEveryMarkerAndOneLevelOfChildren() {
        assertEquals(
            listOf(
                MdBlock.BulletItem(t("dash")),
                MdBlock.BulletItem(t("child"), indent = 1),
                MdBlock.BulletItem(t("star")),
                MdBlock.BulletItem(t("plus")),
            ),
            blocks("- dash\n  - child\n* star\n+ plus"),
        )
    }

    @Test
    fun numberedItemsCountFromOneAsCommonMarkDoesAndChildrenCountAgain() {
        assertEquals(
            listOf(
                MdBlock.NumberedItem(1, t("a")),
                MdBlock.NumberedItem(1, t("x"), indent = 1),
                MdBlock.NumberedItem(2, t("y"), indent = 1),
                MdBlock.NumberedItem(2, t("b")),
                MdBlock.NumberedItem(3, t("c")),
            ),
            blocks("1. a\n   1. x\n   2. y\n1. b\n7) c"),
        )
    }

    @Test
    fun taskItemsAreTodosAtEitherLevel() {
        assertEquals(
            listOf(
                MdBlock.TodoItem(false, t("open")),
                MdBlock.TodoItem(true, t("done")),
                MdBlock.TodoItem(true, t("child"), indent = 1),
                MdBlock.TodoItem(false, t("")),
            ),
            blocks("- [ ] open\n- [x] done\n  - [X] child\n- [ ]"),
        )
        assertEquals(listOf(MdBlock.BulletItem(t("[ ]tight"))), blocks("- [ ]tight"))
    }

    @Test
    fun listsContinueLazilyLooselyAndEndAtAParagraph() {
        assertEquals(listOf(MdBlock.BulletItem(t("a b c"))), blocks("- a\nb\n  c"))
        assertEquals(listOf(MdBlock.BulletItem(t("a")), MdBlock.BulletItem(t("b"))), blocks("- a\n\n- b"))
        assertEquals(listOf(MdBlock.BulletItem(t("a")), p("para"), MdBlock.BulletItem(t("b"))), blocks("- a\n\npara\n\n- b"))
        assertEquals(listOf(p("intro"), MdBlock.BulletItem(t("item"))), blocks("intro\n- item"))
        assertEquals(listOf(MdBlock.BulletItem(t("")), MdBlock.BulletItem(t("x"))), blocks("-\n- x"))
    }

    @Test
    fun aNumberInsideAParagraphIsTextAsInCommonMark() {
        assertEquals(listOf(p("The year was 1984. A good year.")), blocks("The year was\n1984. A good year."))
    }

    @Test
    fun quotesHoldPlainTextContinueLazilyAndSplitAtAnEmptyQuoteLine() {
        assertEquals(
            listOf(MdBlock.Quote(MdText("a b c", listOf(MdSpan(MdSpanStyle.ITALIC, 2, 3))))),
            blocks("> a\n>*b*\nc"),
        )
        assertEquals(listOf(MdBlock.Quote(t("one")), MdBlock.Quote(t("two"))), blocks("> one\n>\n> two"))
    }

    @Test
    fun fencedCodeIsVerbatim() {
        assertEquals(
            listOf(MdBlock.Code("# not a heading\n\n- not a list <b>x</b> **y**")),
            blocks("```kotlin\n# not a heading\n\n- not a list <b>x</b> **y**\n```"),
        )
        assertEquals(listOf(MdBlock.Code("```")), blocks("````\n```\n````"))
        assertEquals(listOf(MdBlock.Code("x")), blocks("~~~py\nx\n~~~"))
        assertEquals(listOf(MdBlock.Code("x\n  y")), blocks("  ```\n  x\n    y\n  ```"))
        assertEquals(listOf(MdBlock.Code("")), blocks("```\n```"))
        assertEquals(listOf(MdBlock.Code("keep   ")), blocks("```\nkeep   \n```"))
    }

    @Test
    fun thematicBreaksAreDividers() {
        assertEquals(List(4) { MdBlock.Divider }, blocks("---\n***\n___\n- - -"))
        assertEquals(listOf(p("a"), MdBlock.Divider), blocks("a\n\n---"))
        assertEquals(listOf(MdBlock.BulletItem(t("a")), MdBlock.Divider), blocks("- a\n---"))
    }

    @Test
    fun crlfAndBareCrReadAsLf() {
        val lf = "# T\n\n- a\n- b\n\npara\nmore"
        assertEquals(blocks(lf), blocks(lf.replace("\n", "\r\n")))
        assertEquals(blocks(lf), blocks(lf.replace("\n", "\r")))
    }

    @Test
    fun trailingWhitespaceIsNotText() {
        assertEquals(listOf(p("Hello world"), MdBlock.BulletItem(t("a"))), blocks("Hello   \nworld\t\n\n- a  \n   \n"))
    }

    @Test
    fun emptyMarkdownIsAMissingField() {
        listOf("", "   ", "\n\n", " \r\n\t").forEach { markdown ->
            assertEquals(
                listOf(ComposeProblemCode.MISSING_FIELD.name),
                refused(markdown).map { it.code },
                "markdown '${markdown.replace("\n", "\\n")}'",
            )
        }
    }

    // --- inline -------------------------------------------------------------------------------

    @Test
    fun everyInlineStyleBecomesASpanOverItsText() {
        assertEquals(
            listOf(
                p(
                    "bold and it and c and t and s",
                    MdSpan(MdSpanStyle.BOLD, 0, 4),
                    MdSpan(MdSpanStyle.ITALIC, 9, 11),
                    MdSpan(MdSpanStyle.INLINE_CODE, 16, 17),
                    MdSpan(MdSpanStyle.LINK, 22, 23, url = "u"),
                    MdSpan(MdSpanStyle.STRIKETHROUGH, 28, 29),
                ),
            ),
            blocks("**bold** and *it* and `c` and [t](u) and ~~s~~"),
        )
        assertEquals(listOf(p("b i", MdSpan(MdSpanStyle.BOLD, 0, 1), MdSpan(MdSpanStyle.ITALIC, 2, 3))), blocks("__b__ _i_"))
    }

    @Test
    fun stylesNest() {
        assertEquals(listOf(p("x", MdSpan(MdSpanStyle.BOLD, 0, 1), MdSpan(MdSpanStyle.ITALIC, 0, 1))), blocks("***x***"))
        assertEquals(listOf(p("a b c", MdSpan(MdSpanStyle.ITALIC, 0, 5), MdSpan(MdSpanStyle.BOLD, 2, 3))), blocks("*a **b** c*"))
        assertEquals(
            listOf(p("t e", MdSpan(MdSpanStyle.LINK, 0, 3, url = "https://x"), MdSpan(MdSpanStyle.ITALIC, 2, 3))),
            blocks("[t *e*](https://x)"),
        )
        assertEquals(listOf(p("a b", MdSpan(MdSpanStyle.ITALIC, 0, 3))), blocks("*a\nb*"))
    }

    @Test
    fun unmatchedDelimitersAndIntrawordUnderscoresAreText() {
        assertEquals(listOf(p("**a")), blocks("**a"))
        assertEquals(listOf(p("a * b * c")), blocks("a * b * c"))
        assertEquals(listOf(p("snake_case_word")), blocks("snake_case_word"))
        assertEquals(listOf(p("~one~")), blocks("~one~"))
        assertEquals(listOf(p("[not a link] and ]")), blocks("[not a link] and ]"))
        assertEquals(listOf(p("a < b and a<b")), blocks("a < b and a<b"))
    }

    @Test
    fun codeSpansAreLiteral() {
        assertEquals(listOf(p("**x** <b>", MdSpan(MdSpanStyle.INLINE_CODE, 0, 9))), blocks("`**x** <b>`"))
        assertEquals(listOf(p("a`b", MdSpan(MdSpanStyle.INLINE_CODE, 0, 3))), blocks("``a`b``"))
        assertEquals(listOf(p("`x", MdSpan(MdSpanStyle.INLINE_CODE, 0, 2))), blocks("`` `x ``"))
        assertEquals(listOf(p("`open")), blocks("`open"))
    }

    @Test
    fun backslashEscapesMakeSyntaxText() {
        assertEquals(listOf(p("*a* <b> [x](y) # \\q")), blocks("\\*a\\* \\<b> \\[x](y) \\# \\q"))
        assertEquals(listOf(MdBlock.Paragraph(t("- not a list"))), blocks("\\- not a list"))
        assertEquals(listOf(MdBlock.Paragraph(t("1. not a list"))), blocks("1\\. not a list"))
    }

    @Test
    fun linkDestinationsKeepBalancedParenthesesAndDropAngleBrackets() {
        assertEquals(
            listOf(p("w", MdSpan(MdSpanStyle.LINK, 0, 1, url = "https://en.wikipedia.org/wiki/A_(b)"))),
            blocks("[w](https://en.wikipedia.org/wiki/A_(b))"),
        )
        assertEquals(listOf(p("w", MdSpan(MdSpanStyle.LINK, 0, 1, url = "https://x"))), blocks("[w](<https://x>)"))
    }

    // --- refusals, each with its offset -------------------------------------------------------

    @Test
    fun rawHtmlIsRefusedWhereItStarts() {
        assertEquals(unsupportedAt(6, 10), problems("Hello <b>x</b>"))
        assertEquals(unsupportedAt(0, 9), problems("<div>\nhi\n</div>"))
        assertEquals(unsupportedAt(2), problems("a <!-- c -->"))
        assertEquals(unsupportedAt(4), problems("see <https://x.y>"))
        assertEquals(unsupportedAt(4), problems("ok\r\n<br/>"))
    }

    @Test
    fun imagesAreRefused() {
        assertEquals(unsupportedAt(0), problems("![alt](a.png)"))
        assertEquals(unsupportedAt(2), problems("- ![alt](a.png)"))
    }

    @Test
    fun tablesAreRefused() {
        assertEquals(unsupportedAt(0, 10), problems("| a | b |\n|---|---|"))
        assertEquals(unsupportedAt(6), problems("a | b\n--|--"))
    }

    @Test
    fun referenceLinksAndFootnotesAreRefused() {
        assertEquals(unsupportedAt(0), problems("[a][ref]"))
        assertEquals(unsupportedAt(0), problems("[a][]"))
        assertEquals(unsupportedAt(0), problems("[ref]: https://x"))
        assertEquals(unsupportedAt(4), problems("text[^1]"))
        assertEquals(unsupportedAt(0), problems("[^1]: note"))
    }

    @Test
    fun linksWithoutAUrlOrTextOrWithATitleAreRefused() {
        assertEquals(unsupportedAt(0), problems("[a]()"))
        assertEquals(unsupportedAt(0), problems("[](https://x)"))
        assertEquals(unsupportedAt(2), problems("a [b](https://x \"title\")"))
    }

    @Test
    fun headingsPastLevelThreeAndSetextHeadingsAreRefused() {
        assertEquals(unsupportedAt(0), problems("#### Deep"))
        assertEquals(unsupportedAt(7), problems("intro\n\n###### Deeper"))
        assertEquals(unsupportedAt(6), problems("Title\n==="))
        assertEquals(unsupportedAt(6), problems("Title\n---"))
    }

    @Test
    fun indentedCodeAndUnclosedFencesAreRefused() {
        assertEquals(unsupportedAt(4), problems("    code"))
        assertEquals(unsupportedAt(8), problems("a\n\n\n    code"))
        assertEquals(unsupportedAt(3), problems("a\n\n```\ncode"))
    }

    @Test
    fun listsNestedTwiceAreTooDeep() {
        assertEquals(listOf(ComposeProblemCode.NESTING_TOO_DEEP.name to 14), problems("- a\n  - b\n    - c"))
        assertEquals(listOf(ComposeProblemCode.NESTING_TOO_DEEP.name to 19), problems("1. a\n   1. b\n      - [ ] c"))
    }

    @Test
    fun blocksInsideListItemsAreRefused() {
        assertEquals(unsupportedAt(7), problems("- a\n\n  more"))
        assertEquals(unsupportedAt(6, 16), problems("- a\n  ```\n  x\n  ```"))
        assertEquals(unsupportedAt(6), problems("- a\n  # h"))
        assertEquals(unsupportedAt(6), problems("- a\n  > q"))
        assertEquals(unsupportedAt(6), problems("- a\n  ---"))
    }

    @Test
    fun blocksInsideQuotesAreRefused() {
        assertEquals(unsupportedAt(2), problems("> # h"))
        assertEquals(unsupportedAt(2), problems("> - x"))
        assertEquals(unsupportedAt(2), problems("> > nested"))
        assertEquals(unsupportedAt(2), problems("> ```"))
    }

    @Test
    fun aNumberedListStartsAtOne() {
        assertEquals(unsupportedAt(0), problems("3. three"))
        assertEquals(unsupportedAt(4), problems("- a\n2. b"))
    }

    @Test
    fun everyProblemIsReportedOnceUpToTheCap() {
        assertEquals(unsupportedAt(2, 6), problems("**<b>x</b>"))
        val many = List(20) { "<b>x</b>" }.joinToString("\n\n")
        assertEquals(CanvasComposeMarkdown.MAX_PROBLEMS, refused(many).size)
    }

    @Test
    fun aRefusalNamesTheConstructAndTheGuide() {
        val problem = refused("Hello <b>x</b>").first()
        assertEquals(ComposeProblemCode.UNSUPPORTED_MARKDOWN.name, problem.code)
        assertEquals(
            "raw HTML or an autolink (write links as [text](url)) at offset 6 is not supported; see ${CanvasToolContract.COMPOSE_GUIDE}",
            problem.message,
        )
    }

    @Test
    fun aLongRunOfUnmatchedDelimitersStaysFast() {
        // Exponential backtracking would never finish; the failed-opener memo keeps this quadratic at worst.
        val markdown = "*a _b ~~c [e ".repeat(300)
        assertEquals(markdown.trimEnd(), (blocks(markdown).single() as MdBlock.Paragraph).content.text)
    }
}
