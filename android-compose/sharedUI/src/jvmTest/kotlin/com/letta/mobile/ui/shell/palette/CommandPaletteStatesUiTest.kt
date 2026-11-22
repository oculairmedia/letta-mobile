@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.palette

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.search.MessageSearchCapabilities
import com.letta.mobile.data.search.MessageSearchHit
import com.letta.mobile.data.search.MessageSearchQuery
import com.letta.mobile.data.search.MessageSearchSource
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.LettaInput
import com.letta.mobile.ui.host.ProvideLettaHost
import com.letta.mobile.ui.shell.header.UnifiedSearchDestination
import com.letta.mobile.ui.shell.header.UnifiedSearchSources
import com.letta.mobile.ui.theme.SharedMaterialTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred

/**
 * The palette says what it is doing: "Searching messages…" while message text is still being searched
 * (the other results show meanwhile), what was not found, and, on a pointer host, the keys it answers
 * to. Renders the palette in both themes on a phone (touch) and a desktop (pointer) to
 * build/palette-snapshots.
 */
class CommandPaletteStatesUiTest {
    private val sources = UnifiedSearchSources(
        agents = listOf(Agent(id = AgentId("a1"), name = "Ada")),
        destinations = listOf(UnifiedSearchDestination("Settings", "Settings"), UnifiedSearchDestination("Memory", "Memory")),
    )

    /** A message search that answers only once [answer] completes. */
    private class SlowSearch : MessageSearchSource {
        val answer = CompletableDeferred<Unit>()
        override val capabilities = MessageSearchCapabilities.LOCAL

        override suspend fun search(query: MessageSearchQuery): List<MessageSearchHit> {
            answer.await()
            return emptyList()
        }
    }

    @Test
    fun theEmptyStatesSayWhatToTypeAndWhatWasNotFound() {
        assertEquals("Search for anything", paletteEmpty("", searchingMessages = false).title)
        assertEquals("Searching messages…", paletteEmpty("ada", searchingMessages = true).title)
        assertEquals("No matches for “zzz”", paletteEmpty(" zzz ", searchingMessages = false).title)
    }

    private fun ComposeUiTest.searchingShown(): Boolean =
        onAllNodesWithTag(CommandPaletteTags.SEARCHING).fetchSemanticsNodes().isNotEmpty()

    @Composable
    private fun Palette(host: LettaHost, dark: Boolean = false, source: UnifiedSearchSources = sources) =
        ProvideLettaHost(host) { SharedMaterialTheme(darkTheme = dark) { CommandPalettePane(source, onOpen = {}, onDismiss = {}) } }

    @Test
    fun itSaysItIsSearchingMessagesUntilTheyAnswer() = runComposeUiTest {
        val search = SlowSearch()
        setContent { Palette(LettaHost.Pointer, source = sources.copy(messageSearch = search)) }
        onNodeWithTag(CommandPaletteTags.SEARCH).performTextInput("ada")
        waitUntil(timeoutMillis = WAIT_MILLIS) { searchingShown() }
        // The other results show meanwhile.
        onNodeWithText("Ada").assertExists()

        search.answer.complete(Unit)
        waitUntil(timeoutMillis = WAIT_MILLIS) { !searchingShown() }
    }

    @Test
    fun aPointerPaletteNamesItsKeysAndATouchOneDoesNot() {
        runComposeUiTest {
            setContent { Palette(LettaHost.Pointer) }
            onNodeWithTag(CommandPaletteTags.KEYS).assertExists()
            onNodeWithText("Enter").assertExists()
        }
        runComposeUiTest {
            setContent { Palette(LettaHost(input = LettaInput.Touch)) }
            onNodeWithTag(CommandPaletteTags.KEYS).assertDoesNotExist()
        }
    }

    private fun snapshot(name: String, dark: Boolean, host: LettaHost, width: Int, typed: String) =
        runDesktopComposeUiTest(width = width, height = WINDOW_HEIGHT) {
            setContent { Palette(host, dark) }
            if (typed.isNotEmpty()) onNodeWithTag(CommandPaletteTags.SEARCH).performTextInput(typed)
            waitForIdle()
            val out = File("build/palette-snapshots").apply { mkdirs() }.resolve("$name-${if (dark) "dark" else "light"}.png")
            // The pane draws in a window of its own: the last root.
            ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", out)
            assertTrue(out.length() > 0)
        }

    @Test
    fun thePaletteOnAPhoneAndADesktop() {
        listOf(false, true).forEach { dark ->
            snapshot("touch-360dp", dark, LettaHost(input = LettaInput.Touch), width = 360, typed = "")
            snapshot("pointer-760dp", dark, LettaHost.Pointer, width = 760, typed = "")
            snapshot("pointer-1200dp-nomatch", dark, LettaHost.Pointer, width = 1200, typed = "zzz")
            snapshot("pointer-700dp", dark, LettaHost.Pointer, width = 700, typed = "se")
        }
    }

    private companion object {
        const val WAIT_MILLIS = 5_000L
        const val WINDOW_HEIGHT = 640
    }
}
