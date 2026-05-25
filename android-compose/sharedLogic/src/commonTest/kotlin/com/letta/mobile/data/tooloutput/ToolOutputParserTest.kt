package com.letta.mobile.data.tooloutput

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolOutputParserTest {

    @Test
    fun `parses valid JSON object`() {
        val document = ToolOutputParser.parse("""{"ok":true,"count":2}""")
        val block = document.blocks.single() as ToolOutputBlock.Json

        assertEquals(JsonRootKind.Object, block.rootKind)
        assertTrue(block.pretty.contains("\"ok\": true"))
        assertTrue(block.pretty.contains("\"count\": 2"))
    }

    @Test
    fun `parses valid JSON array`() {
        val document = ToolOutputParser.parse("""[{"name":"a"},{"name":"b"}]""")
        val block = document.blocks.single() as ToolOutputBlock.Json

        assertEquals(JsonRootKind.Array, block.rootKind)
        assertTrue(block.pretty.contains("\"name\": \"a\""))
    }

    @Test
    fun `strips blank whitespace from JSON result fields only`() {
        val document = ToolOutputParser.parse(
            """{"results":"\n\nalpha\n\n   \n  beta  \n\n","metadata":"\n\nkept\n\n"}"""
        )
        val block = document.blocks.single() as ToolOutputBlock.Json

        assertTrue(block.pretty.contains(""""results": "alpha\n  beta""""))
        assertFalse(block.pretty.contains(""""results": "\n"""))
        assertTrue(block.pretty.contains(""""metadata": "\n\nkept\n\n""""))
    }

    @Test
    fun `strips nested JSON result strings`() {
        val document = ToolOutputParser.parse(
            """{"payload":{"result":["\nfirst\n\n","second\n\n\nthird"]}}"""
        )
        val block = document.blocks.single() as ToolOutputBlock.Json

        assertTrue(block.pretty.contains(""""first""""))
        assertTrue(block.pretty.contains(""""second\nthird""""))
        assertFalse(block.pretty.contains(""""first\n\n""""))
    }

    @Test
    fun `falls back for invalid JSON`() {
        val document = ToolOutputParser.parse("""{"ok": true""")

        assertTrue(document.blocks.single() is ToolOutputBlock.PlainText)
    }

    @Test
    fun `parses unified diff with two files`() {
        val diff = """
            diff --git a/a.txt b/a.txt
            --- a/a.txt
            +++ b/a.txt
            @@ -1 +1 @@
            -old
            +new
            diff --git a/b.txt b/b.txt
            --- a/b.txt
            +++ b/b.txt
            @@ -1 +1 @@
             same
            +added
        """.trimIndent()

        val block = ToolOutputParser.parse(diff).blocks.single() as ToolOutputBlock.Diff

        assertEquals(2, block.files.size)
        assertEquals("a.txt", block.files.first().newPath)
        assertTrue(block.files.first().lines.any { it.type == DiffLineType.Added && it.text == "+new" })
    }

    @Test
    fun `parses JVM stack trace`() {
        val trace = """
            java.lang.IllegalStateException: bad state
                at com.letta.mobile.Foo.bar(Foo.kt:42)
                at com.letta.mobile.Main.main(Main.kt:7)
        """.trimIndent()

        val block = ToolOutputParser.parse(trace).blocks.single() as ToolOutputBlock.StackTrace

        assertEquals("jvm", block.languageHint)
        assertEquals("java.lang.IllegalStateException: bad state", block.headline)
        assertEquals("Foo.kt", block.frames.first().file)
        assertEquals(42, block.frames.first().line ?: -1)
    }

    @Test
    fun `parses Python traceback`() {
        val trace = """
            Traceback (most recent call last):
              File "main.py", line 10, in <module>
                run()
              File "worker.py", line 3, in run
                raise ValueError("bad")
            ValueError: bad
        """.trimIndent()

        val block = ToolOutputParser.parse(trace).blocks.single() as ToolOutputBlock.StackTrace

        assertEquals("python", block.languageHint)
        assertEquals("ValueError: bad", block.headline)
        assertEquals(2, block.frames.size)
        assertEquals("main.py", block.frames.first().file)
    }

    @Test
    fun `strips ANSI colored output`() {
        val document = ToolOutputParser.parse("\u001B[31mERROR\u001B[0m done")
        val block = document.blocks.single() as ToolOutputBlock.AnsiLog

        assertEquals("ERROR done", block.stripped)
    }

    @Test
    fun `parses shell transcript`() {
        val document = ToolOutputParser.parse("\$ git status\nclean")
        val block = document.blocks.single() as ToolOutputBlock.ShellTranscript

        assertEquals(listOf("$ git status"), block.commandLines)
        assertEquals("clean", block.output)
    }

    @Test
    fun `parses shell transcript commands and output in one pass order`() {
        val transcript = """
            ${'$'} git status
            clean
            > rg needle
            found
            PS> Get-ChildItem
            done
        """.trimIndent()

        val block = ToolOutputParser.parse(transcript).blocks.single() as ToolOutputBlock.ShellTranscript

        assertEquals(listOf("$ git status", "> rg needle", "PS> Get-ChildItem"), block.commandLines)
        assertEquals("clean\nfound\ndone", block.output)
    }

    @Test
    fun `normalizes Windows line endings before classifying output`() {
        val document = ToolOutputParser.parse("${'$'} git status\r\nclean\r\n")
        val block = document.blocks.single() as ToolOutputBlock.ShellTranscript

        assertEquals(listOf("$ git status"), block.commandLines)
        assertEquals("clean", block.output)
        assertFalse(block.raw.contains('\r'))
    }

    @Test
    fun `parses markdown table`() {
        val table = """
            | name | status |
            | --- | --- |
            | api | ok |
        """.trimIndent()

        val block = ToolOutputParser.parse(table).blocks.single() as ToolOutputBlock.Table

        assertEquals(listOf("name", "status"), block.rows.first())
        assertEquals(listOf("api", "ok"), block.rows.last())
    }

    @Test
    fun `does not classify prose with pipes as a table`() {
        val document = ToolOutputParser.parse("Choose yes | no | maybe and continue.")

        assertTrue(document.blocks.single() is ToolOutputBlock.PlainText)
    }

    @Test
    fun `marks large output as truncated for structured analysis`() {
        val raw = "x".repeat(ToolOutputParser.MaxAnalyzedChars + 10)
        val document = ToolOutputParser.parse(raw)

        assertTrue(document.isTruncated)
        assertEquals(10, document.omittedCharCount)
        assertEquals(raw, document.raw)
        assertFalse(document.blocks.isEmpty())
    }
}
