@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.components.picker

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsProperties
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.LettaPaneTags
import com.letta.mobile.ui.host.LocalPaneWindowClass
import com.letta.mobile.ui.host.PaneWindowClass
import com.letta.mobile.ui.host.ProvideLettaHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** letta-mobile-ckmub.3.6 (hard cut S8, primitive P5): the shared picker. */
class LettaPickerTest {
    private val fruit = PickerSection(
        key = "fruit",
        title = "Fruit",
        items = listOf(item("apple", "Apple"), item("banana", "Banana")),
    )
    private val vegetables = PickerSection(key = "veg", title = "Vegetables", items = listOf(item("carrot", "Carrot")))

    // --- plain rules -------------------------------------------------------------------------

    @Test
    fun theLayoutFiltersSearchableSectionsAndDropsEmptyOnes() {
        val layout = PickerLayout.of(listOf(fruit, vegetables), query = "car")
        assertEquals(listOf("carrot"), layout.rows.map { it.value })
        assertEquals(listOf("section-veg", "row-carrot"), layout.lines.map { it.key })
    }

    @Test
    fun recentHidesWhileSearchingAndUnsearchableSectionsStayWhole() {
        val recent = PickerSection.recent("Recent", listOf(item("r-apple", "Apple")))
        val paths = fruit.copy(key = "paths", searchable = false)
        val layout = PickerLayout.of(listOf(recent, paths, vegetables), query = "carrot")
        assertEquals(listOf("apple", "banana", "carrot"), layout.rows.map { it.value })
        assertEquals(listOf("r-apple", "carrot"), PickerLayout.of(listOf(recent, vegetables), query = "").rows.map { it.value })
    }

    @Test
    fun aFoldedSectionKeepsItsHeaderButNotItsRows() {
        val layout = PickerLayout.of(listOf(fruit.copy(collapsed = true), vegetables), query = "")
        assertEquals(listOf("section-fruit", "section-veg", "row-carrot"), layout.lines.map { it.key })
        assertEquals(2, layout.lineOf(0))
    }

    @Test
    fun keysStartOnTheTopRowForCommandsAndStopAtTheEnds() {
        assertEquals(0, PickerKeys.initial(PickMode.Command, "", 3))
        assertEquals(PickerKeys.NONE, PickerKeys.initial(PickMode.Single, "", 3))
        assertEquals(0, PickerKeys.initial(PickMode.Single, "a", 3))
        assertEquals(0, PickerKeys.move(PickerKeys.NONE, 1, 3))
        assertEquals(2, PickerKeys.move(PickerKeys.NONE, -1, 3))
        assertEquals(2, PickerKeys.move(2, 1, 3))
        assertEquals(0, PickerKeys.move(0, -1, 3))
    }

    @Test
    fun drillInGoesBackToTheRootAndThenReportsItIsThere() {
        val levels = PickerDrillIn("root")
        levels.open("model")
        assertEquals("model", levels.current)
        assertTrue(levels.back())
        assertEquals("root", levels.current)
        assertEquals(false, levels.back())
    }

    // --- composed ----------------------------------------------------------------------------

    @Test
    fun typingInTheSearchFiltersTheSections() = runComposeUiTest {
        show()
        onNodeWithTag(LettaPickerTags.SEARCH).performTextInput("car")
        onNodeWithTag(LettaPickerTags.ROW_PREFIX + "carrot").assertExists()
        onNodeWithTag(LettaPickerTags.ROW_PREFIX + "apple").assertDoesNotExist()
        onNodeWithTag(LettaPickerTags.SECTION_PREFIX + "fruit").assertDoesNotExist()
    }

    @Test
    fun arrowsMoveTheHighlightAndEnterPicksIt() = runComposeUiTest {
        val picked = show()
        onNodeWithTag(LettaPickerTags.SEARCH).requestFocus()
        onNodeWithTag(LettaPickerTags.SEARCH).performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionUp)
            pressKey(Key.Enter)
        }
        runOnIdle { assertEquals(listOf("banana"), picked) }
    }

    @Test
    fun swappingInRowsOfTheSameCountClearsTheHighlight() = runComposeUiTest {
        // letta-mobile-by4vm.47: the highlight was keyed on the row count, so a swap to another
        // section of equal size kept pointing at a row the user never highlighted.
        val drinks = PickerSection(key = "drinks", title = "Drinks", items = listOf(item("tea", "Tea"), item("coffee", "Coffee")))
        var sections by mutableStateOf(listOf(fruit))
        val picked = mutableListOf<String>()
        setContent {
            Frame {
                LettaPickerContent(
                    spec = LettaPickerSpec(sections),
                    callbacks = PickerCallbacks(onQuery = {}, onPick = { picked += it.value }),
                )
            }
        }
        onNodeWithTag(LettaPickerTags.SEARCH).requestFocus()
        onNodeWithTag(LettaPickerTags.SEARCH).performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
        }
        sections = listOf(drinks)
        onNodeWithTag(LettaPickerTags.ROW_PREFIX + "coffee").assertExists()
        onNodeWithTag(LettaPickerTags.SEARCH).performKeyInput { pressKey(Key.Enter) }
        runOnIdle { assertEquals(emptyList<String>(), picked) }
        onNodeWithTag(LettaPickerTags.SEARCH).performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.Enter)
        }
        runOnIdle { assertEquals(listOf("tea"), picked) }
    }

    @Test
    fun aClickPicksAndTheSelectedRowIsMarked() = runComposeUiTest {
        val picked = show(selected = setOf("apple"))
        onNodeWithTag(LettaPickerTags.ROW_PREFIX + "apple").assertIsSelected()
        onNodeWithText("Carrot").performClick()
        runOnIdle { assertEquals(listOf("carrot"), picked) }
    }

    @Test
    fun multiSelectTogglesRowsWithoutClosing() = runComposeUiTest {
        setContent {
            Frame {
                var chosen by remember { mutableStateOf(setOf<String>()) }
                LettaPickerContent(
                    spec = LettaPickerSpec(listOf(fruit, vegetables), mode = PickMode.Multi, selected = chosen, search = null),
                    callbacks = PickerCallbacks(onQuery = {}, onPick = { chosen = if (it.key in chosen) chosen - it.key else chosen + it.key }),
                )
            }
        }
        onNodeWithTag(LettaPickerTags.ROW_PREFIX + "apple").performClick()
        onNodeWithTag(LettaPickerTags.ROW_PREFIX + "carrot").performClick()
        onNodeWithTag(LettaPickerTags.ROW_PREFIX + "apple").performClick()
        onNodeWithTag(LettaPickerTags.ROW_PREFIX + "apple").assert(toggle(ToggleableState.Off))
        onNodeWithTag(LettaPickerTags.ROW_PREFIX + "carrot").assert(toggle(ToggleableState.On))
    }

    @Test
    fun aDrillInRowOpensTheSecondLevelAndBackReturns() = runComposeUiTest {
        setContent {
            Frame {
                val levels = rememberPickerDrillIn("root")
                if (levels.current == "root") {
                    LettaPickerContent(
                        spec = LettaPickerSpec(listOf(PickerSection("root", null, listOf(PickerItem("model", "model", "Model", drillIn = true))))),
                        callbacks = PickerCallbacks(onQuery = {}, onPick = { levels.open(it.value) }),
                    )
                } else {
                    LettaPickerContent(
                        spec = LettaPickerSpec(listOf(fruit), level = PickerLevel("Model", onBack = { levels.back() })),
                        callbacks = PickerCallbacks(onQuery = {}, onPick = {}),
                    )
                }
            }
        }
        onNodeWithTag(LettaPickerTags.ROW_PREFIX + "model").performClick()
        onNodeWithTag(LettaPickerTags.ROW_PREFIX + "apple").assertExists()
        onNodeWithTag(LettaPickerTags.BACK).performClick()
        onNodeWithTag(LettaPickerTags.ROW_PREFIX + "model").assertExists()
        // Escape at the second level goes back too.
        onNodeWithTag(LettaPickerTags.ROW_PREFIX + "model").performClick()
        onNodeWithTag(LettaPickerTags.SEARCH).requestFocus()
        onNodeWithTag(LettaPickerTags.SEARCH).performKeyInput { pressKey(Key.Escape) }
        onNodeWithTag(LettaPickerTags.ROW_PREFIX + "model").assertExists()
    }

    @Test
    fun loadingEmptyAndFailedStatesShow() = runComposeUiTest {
        var spec by mutableStateOf(LettaPickerSpec<String>(emptyList(), load = PickerLoad.Loading, empty = PickerEmpty("Loading things")))
        setContent { Frame { LettaPickerContent(spec, PickerCallbacks(onQuery = {}, onPick = {})) } }
        onNodeWithTag(LettaPickerTags.LOADING).assertExists()
        onNodeWithText("Loading things").assertExists()
        spec = LettaPickerSpec(listOf(fruit), load = PickerLoad.Failed("Couldn't load"))
        onNodeWithTag(LettaPickerTags.ERROR).assertExists()
        onNodeWithTag(LettaPickerTags.ROW_PREFIX + "apple").assertExists()
        spec = LettaPickerSpec(listOf(fruit), query = "zzz")
        onNodeWithText(LettaPickerStrings.noMatch("zzz")).assertExists()
    }

    @Test
    fun thePickerOpensInADialogOnPointerAndASheetOnTouch() = runComposeUiTest {
        var host by mutableStateOf(LettaHost.Pointer)
        setContent {
            // A phone-sized window: the touch host's modal is its sheet.
            CompositionLocalProvider(LocalPaneWindowClass provides PaneWindowClass.Compact) {
                ProvideLettaHost(host) {
                    MaterialTheme {
                        LettaPicker(LettaPickerSpec(listOf(fruit)), PickerCallbacks(onQuery = {}, onPick = {}))
                    }
                }
            }
        }
        onNodeWithTag(LettaPaneTags.DIALOG).assertExists()
        onNodeWithTag(LettaPickerTags.ROW_PREFIX + "apple").assertExists()
        host = LettaHost.Touch
        onNodeWithTag(LettaPaneTags.SHEET).assertExists()
        onNodeWithTag(LettaPaneTags.DIALOG).assertDoesNotExist()
    }

    private fun ComposeUiTest.show(selected: Set<String> = emptySet()): List<String> {
        val picked = mutableListOf<String>()
        setContent {
            Frame {
                var query by remember { mutableStateOf("") }
                LettaPickerContent(
                    spec = LettaPickerSpec(listOf(fruit, vegetables), query = query, selected = selected),
                    callbacks = PickerCallbacks(onQuery = { query = it }, onPick = { picked += it.value }),
                )
            }
        }
        return picked
    }

    @Composable
    private fun Frame(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
        MaterialTheme { Box(Modifier.size(480.dp, 640.dp)) { Column(content = content) } }
    }

    private fun toggle(state: ToggleableState) = SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, state)

    private companion object {
        fun item(value: String, title: String) = PickerItem(key = value, value = value, title = title)
    }
}
