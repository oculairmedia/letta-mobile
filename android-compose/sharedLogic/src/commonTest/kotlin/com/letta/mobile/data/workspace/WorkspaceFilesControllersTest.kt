package com.letta.mobile.data.workspace

import com.letta.mobile.data.composer.MentionKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceFilesControllersTest {
    /** A fake workspace: every path under its cwd, searched by substring like the server. */
    private class FakeWorkspace : WorkspaceFileSource {
        val files = mapOf(
            "/work/repo" to listOf("src/main.kt", "src/util/Paths.kt", "README.md"),
            "/work/other" to listOf("notes.txt"),
        )
        val searches = mutableListOf<Pair<String, String?>>()
        val reads = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        var failSearch: String? = null

        override suspend fun search(query: String, cwd: String?, limit: Int): List<String> {
            searches += query to cwd
            gate?.await()
            failSearch?.let { throw WorkspaceFileException(it) }
            return files[cwd].orEmpty().filter { it.contains(query, ignoreCase = true) }.take(limit)
        }

        override suspend fun read(path: String): WorkspaceFileContent {
            reads += path
            return if (path.endsWith(".png")) WorkspaceFileContent.Binary(path) else WorkspaceFileContent.Text(path, "text of $path")
        }
    }

    private fun TestScope.mentions(source: FakeWorkspace = FakeWorkspace()) =
        FileMentionController(source, backgroundScope) to source

    @Test
    fun anAtMentionSearchesTheWorkingDirectoryAfterTheDebounce() = runTest {
        val (controller, source) = mentions()
        controller.onDraftChanged(MentionDraft("look at @ma", "/work/repo"))
        assertTrue(controller.state.value.loading)
        advanceTimeBy(FileMentionController.DEFAULT_DEBOUNCE.inWholeMilliseconds - 1)
        runCurrent()
        assertTrue(source.searches.isEmpty(), "nothing is sent before the debounce")

        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf<Pair<String, String?>>("ma" to "/work/repo"), source.searches)
        val result = controller.state.value.results.single()
        assertEquals("main.kt", result.label)
        assertEquals("src", result.sublabel)
        assertEquals("src/main.kt", result.insertText)
        assertEquals(MentionKind.File, result.kind)
        assertFalse(controller.state.value.loading)
    }

    @Test
    fun typingFastSendsOnlyTheLastQuery() = runTest {
        val (controller, source) = mentions()
        controller.onDraftChanged(MentionDraft("@s", "/work/repo"))
        controller.onDraftChanged(MentionDraft("@sr", "/work/repo"))
        controller.onDraftChanged(MentionDraft("@src/u", "/work/repo"))
        advanceTimeBy(500)
        runCurrent()
        assertEquals(listOf<Pair<String, String?>>("src/u" to "/work/repo"), source.searches)
        assertEquals(listOf("src/util/Paths.kt"), controller.state.value.results.map { it.insertText })
    }

    @Test
    fun aSlowSearchForAnOldQueryIsDropped() = runTest {
        val source = FakeWorkspace().apply { gate = CompletableDeferred() }
        val (controller, _) = mentions(source)
        controller.onDraftChanged(MentionDraft("@READ", "/work/repo"))
        advanceTimeBy(300)
        runCurrent()
        controller.onDraftChanged(MentionDraft("@READ", "/work/other"))
        source.gate!!.complete(Unit)
        advanceTimeBy(300)
        runCurrent()
        assertEquals("/work/other", controller.state.value.cwd)
        assertTrue(controller.state.value.results.isEmpty(), "results come from the current cwd only")
    }

    @Test
    fun leavingTheMentionClearsTheSuggestions() = runTest {
        val (controller, _) = mentions()
        controller.onDraftChanged(MentionDraft("@ma", "/work/repo"))
        advanceTimeBy(300)
        runCurrent()
        controller.onDraftChanged(MentionDraft("@main.kt and more", "/work/repo"))
        assertNull(controller.state.value.query)
        assertTrue(controller.state.value.results.isEmpty())
    }

    @Test
    fun aFailedSearchSaysWhy() = runTest {
        val (controller, _) = mentions(FakeWorkspace().apply { failSearch = "The App Server did not answer in time." })
        controller.onDraftChanged(MentionDraft("@x", "/work/repo"))
        advanceTimeBy(300)
        runCurrent()
        assertEquals("The App Server did not answer in time.", controller.state.value.error)
    }

    @Test
    fun aRelativeToolPathOpensUnderTheWorkingDirectory() = runTest {
        val source = FakeWorkspace()
        val viewer = WorkspaceFileViewerController(source, backgroundScope)
        viewer.open("src/main.kt", cwd = "/work/repo")
        assertTrue(viewer.state.value.loading)
        runCurrent()
        assertEquals(listOf("/work/repo/src/main.kt"), source.reads)
        assertEquals(WorkspaceFileContent.Text("/work/repo/src/main.kt", "text of /work/repo/src/main.kt"), viewer.state.value.content)

        viewer.open("C:\\repo\\logo.png", cwd = "/work/repo")
        runCurrent()
        assertTrue(viewer.state.value.content is WorkspaceFileContent.Binary)

        viewer.close()
        assertFalse(viewer.state.value.isOpen)
    }

    @Test
    fun aReadForAFileNoLongerOpenIsDropped() {
        val open = WorkspaceFileViewerReducer.opening("/b.kt")
        assertEquals(open, WorkspaceFileViewerReducer.read(open, WorkspaceFileContent.Text("/a.kt", "x")))
        assertEquals(open, WorkspaceFileViewerReducer.failed(open, "/a.kt", "gone"))
    }

    @Test
    fun pathsResolveForPosixAndWindows() {
        assertEquals("/repo/src/a.kt", WorkspacePaths.resolve("./src/a.kt", "/repo/"))
        assertEquals("C:\\repo\\src\\a.kt", WorkspacePaths.resolve("src/a.kt", "C:\\repo"))
        assertEquals("/abs/a.kt", WorkspacePaths.resolve("/abs/a.kt", "/repo"))
        assertEquals("D:/x.txt", WorkspacePaths.resolve("D:/x.txt", "/repo"))
        assertEquals("a.kt", WorkspacePaths.fileName("C:\\repo\\a.kt"))
        assertEquals("src/util", WorkspacePaths.folder("src/util/Paths.kt"))
        assertEquals("kt", WorkspacePaths.languageHint("src/Main.KT"))
        assertNull(WorkspacePaths.languageHint("Makefile"))
    }

    @Test
    fun toolCallsNameTheirFile() {
        assertEquals("/repo/a.kt", ToolFileTargets.pathOf("""{"file_path":"/repo/a.kt","old_string":"x"}"""))
        assertEquals("notes.md", ToolFileTargets.pathOf("""{"path":"notes.md"}"""))
        assertNull(ToolFileTargets.pathOf("""{"command":"ls"}"""))
        assertNull(ToolFileTargets.pathOf("not json"))
    }
}
