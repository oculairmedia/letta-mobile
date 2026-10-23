package com.letta.mobile.data.chat.tools.links

import kotlin.test.Test
import kotlin.test.assertEquals

/** letta-mobile-2ccpv: the URLs and paths a tool's output names, so its inset can link them. */
class OutputLinksTest {
    @Test
    fun urlsArePathsLinksAndAPathKeepsItsLine() {
        val text = "see https://example.com/docs. then src/main/Main.kt:42 and C:\\repo\\a.kt"
        val links = OutputLinks.find(text)
        assertEquals(OutputLinks.Match.Url("https://example.com/docs", 4..27), links[0])
        val path = links[1] as OutputLinks.Match.Path
        assertEquals("src/main/Main.kt", path.path)
        assertEquals(42, path.line)
        assertEquals("src/main/Main.kt:42", text.substring(path.range))
        assertEquals("C:\\repo\\a.kt", (links[2] as OutputLinks.Match.Path).path)
    }

    @Test
    fun wordsNumbersAndBareFileNamesAreNotLinks() {
        assertEquals(emptyList(), OutputLinks.find("BUILD SUCCESSFUL in 3.2s, 12 tests, see README.md"))
    }

    @Test
    fun absoluteAndDotRelativePathsAreLinks() {
        val paths = OutputLinks.find("/repo/build.gradle.kts ./gradlew.bat").map { (it as OutputLinks.Match.Path).path }
        assertEquals(listOf("/repo/build.gradle.kts", "./gradlew.bat"), paths)
    }
}
