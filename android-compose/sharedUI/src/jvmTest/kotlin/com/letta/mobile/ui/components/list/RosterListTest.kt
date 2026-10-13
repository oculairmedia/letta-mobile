@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.components.list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.components.LettaListRowSpec
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.LettaInput
import com.letta.mobile.ui.host.ProvideLettaHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** letta-mobile-ckmub.3.10 (hard cut S7, primitive P4): the one sectioned, selectable list. */
class RosterListTest {
    private data class Fruit(val id: String, val name: String, val pinned: Boolean = false)

    private val apple = Fruit("apple", "Apple", pinned = true)
    private val banana = Fruit("banana", "Banana")
    private val cherry = Fruit("cherry", "Cherry")

    // --- plain rules -------------------------------------------------------------------------

    @Test
    fun pinnedFirstSplitsAPinnedGroupAndDropsItWhenEmpty() {
        val sections = RosterSections.pinnedFirst(RosterSection("fruit", listOf(banana, apple, cherry), title = "Fruit"), Fruit::pinned)
        assertEquals(listOf("fruit-pinned", "fruit"), sections.map { it.key })
        assertEquals(listOf(listOf(apple), listOf(banana, cherry)), sections.map { it.items })
        assertEquals(listOf("fruit"), RosterSections.pinnedFirst(RosterSection("fruit", listOf(banana)), Fruit::pinned).map { it.key })
    }

    @Test
    fun theLayoutHasHeadersRowsAndHintsInOrder() {
        val layout = RosterLayout.of(
            listOf(RosterSection("a", listOf(banana), title = "A"), RosterSection("b", emptyList<Fruit>(), title = "B", emptyHint = "None")),
        ) { it.id }
        assertEquals(listOf("section:a", "a:banana", "section:b", "hint:b"), layout.lines.map { it.key })
        assertEquals(listOf("a:banana"), layout.rows.map { it.key })
    }

    @Test
    fun theCursorStopsAtTheEndsAndReorderStaysInItsSection() {
        assertEquals(0, RosterKeys.move(-1, 1, 3))
        assertEquals(2, RosterKeys.move(-1, -1, 3))
        assertEquals(2, RosterKeys.move(2, 1, 3))
        assertEquals(-1, RosterKeys.move(-1, 1, 0))
        val first = RosterLine.Row(banana, "k", "s", indexInSection = 0, sectionSize = 2)
        assertEquals(null, RosterKeys.reorderTarget(first, -1))
        assertEquals(1, RosterKeys.reorderTarget(first, 1))
    }

    @Test
    fun aGridLaysEachSectionsRowsOutInCellsAndKeepsTheReadingOrder() {
        val layout = RosterLayout.of(listOf(RosterSection("fruit", listOf(apple, banana, cherry), title = "Fruit")), columns = 2) { it.id }
        assertEquals(listOf("section:fruit", "cells:fruit:apple", "cells:fruit:cherry"), layout.lines.map { it.key })
        assertEquals(listOf("fruit:apple", "fruit:banana", "fruit:cherry"), layout.signature)
        assertEquals(1, layout.lineOf("fruit:banana"))
        assertEquals(2, layout.lineOf("fruit:cherry"))
    }

    @Test
    fun aMovedLayoutPutsTheRowInItsNewPlaceWithinItsSection() {
        val layout = RosterLayout.of(listOf(RosterSection("a", listOf(apple)), RosterSection("fruit", listOf(banana, cherry, apple)))) { it.id }
        val moved = layout.moved(RosterMove("fruit:banana", 2))
        assertEquals(listOf("a:apple", "fruit:cherry", "fruit:apple", "fruit:banana"), moved.signature)
        assertEquals(listOf(0, 0, 1, 2), moved.rows.map { it.indexInSection })
        assertEquals(layout.signature, layout.moved(RosterMove("fruit:banana", 0)).signature)
        assertEquals(layout.signature, layout.moved(RosterMove("missing", 1)).signature)
    }

    // --- composed ----------------------------------------------------------------------------

    @Test
    fun aPointerDragsARowToANewPlaceAndTellsTheHostOnceOnRelease() = runComposeUiTest {
        val moves = mutableListOf<String>()
        setContent {
            ProvideLettaHost(LettaHost.Pointer) {
                Roster(
                    sections = listOf(RosterSection("fruit", listOf(apple, banana, cherry))),
                    actions = RosterActions(reorder = RosterReorder { item, to -> moves += "${item.id}:$to" }),
                )
            }
        }
        val dragged = onNodeWithTag(row("fruit", apple).value)
        val step = dragged.fetchSemanticsNode().size.height / DRAG_STEPS_PER_ROW
        dragged.performMouseInput {
            moveTo(center)
            press()
        }
        repeat(DRAG_STEPS) {
            dragged.performMouseInput { moveBy(Offset(0f, step)) }
            waitForIdle()
            assertEquals(emptyList(), moves)
        }
        dragged.performMouseInput { release() }
        runOnIdle { assertEquals(listOf("apple:2"), moves) }
        // The dropped order stays drawn while the host saves it: apple is now under cherry.
        val apples = onNodeWithTag(row("fruit", apple).value).fetchSemanticsNode().boundsInRoot.top
        val cherries = onNodeWithTag(row("fruit", cherry).value).fetchSemanticsNode().boundsInRoot.top
        assertTrue(apples > cherries)
    }

    @Test
    fun aTouchHoldsARowThenDragsItAndAHoldAloneOpensTheMenu() = runComposeUiTest {
        val moves = mutableListOf<String>()
        setContent {
            ProvideLettaHost(LettaHost.Touch) {
                Roster(
                    sections = listOf(RosterSection("fruit", listOf(apple, banana, cherry))),
                    actions = RosterActions(reorder = RosterReorder { item, to -> moves += "${item.id}:$to" }),
                )
            }
        }
        val dragged = onNodeWithTag(row("fruit", apple).value)
        val step = dragged.fetchSemanticsNode().size.height / DRAG_STEPS_PER_ROW
        // A move before the hold is a scroll, not a drag: nothing reorders.
        dragged.performTouchInput { swipeDown(startY = centerY, endY = centerY + step * DRAG_STEPS, durationMillis = 200) }
        runOnIdle { assertEquals(emptyList(), moves) }

        dragged.performTouchInput { down(center) }
        mainClock.advanceTimeBy(HOLD_MILLIS)
        repeat(DRAG_STEPS) {
            dragged.performTouchInput { moveBy(Offset(0f, step)) }
            waitForIdle()
        }
        dragged.performTouchInput { up() }
        runOnIdle { assertEquals(listOf("apple:2"), moves) }

        val held = onNodeWithTag(row("fruit", banana).value)
        held.performTouchInput { down(center) }
        mainClock.advanceTimeBy(HOLD_MILLIS)
        held.performTouchInput { up() }
        onNodeWithText("Move down").assertExists()
        runOnIdle { assertEquals(listOf("apple:2"), moves) }
    }

    @Test
    fun aGridRosterDrawsItsCellsAndSelectsAClickedOne() = runComposeUiTest {
        val picked = mutableListOf<String>()
        setContent {
            Roster(
                sections = listOf(RosterSection("fruit", listOf(apple, banana, cherry), title = "Fruit")),
                selection = RosterSelection(onSelect = { picked += it.id }),
                options = RosterOptions(columns = 2),
            )
        }
        val apples = onNodeWithTag(row("fruit", apple).value).fetchSemanticsNode().boundsInRoot
        val bananas = onNodeWithTag(row("fruit", banana).value).fetchSemanticsNode().boundsInRoot
        assertEquals(apples.top, bananas.top)
        assertTrue(bananas.left > apples.left)
        onNodeWithText("Cherry").performClick()
        assertEquals(listOf("cherry"), picked)
    }

    @Test
    fun drawsSectionsHeadersSelectionAndEmptyHints() = runComposeUiTest {
        setContent {
            Roster(
                sections = listOf(
                    RosterSection("fruit", listOf(apple, banana), title = "Fruit"),
                    RosterSection("veg", emptyList(), title = "Vegetables", emptyHint = "No vegetables"),
                ),
                selection = RosterSelection(isSelected = { it == banana }),
            )
        }
        onNodeWithText("FRUIT").assertExists()
        onNodeWithText("VEGETABLES").assertExists()
        onNodeWithText("No vegetables").assertExists()
        onNodeWithTag(row("fruit", banana).value).assertIsSelected()
        onNodeWithTag(row("fruit", apple).value).assertIsNotSelected()
    }

    @Test
    fun aClickSelectsAndAnEmptyListShowsItsEmptyStateOrLoading() = runComposeUiTest {
        val picked = mutableListOf<String>()
        var loading by mutableStateOf(true)
        var items by mutableStateOf(emptyList<Fruit>())
        setContent {
            Roster(
                sections = listOf(RosterSection("fruit", items)),
                selection = RosterSelection(onSelect = { picked += it.id }),
                options = RosterOptions(loading = loading),
                empty = { Text("Nothing yet") },
            )
        }
        onNodeWithTag(RosterListTags.LIST.loading).assertExists()
        loading = false
        waitForIdle()
        onNodeWithText("Nothing yet").assertExists()
        items = listOf(cherry)
        waitForIdle()
        onNodeWithText("Cherry").performClick()
        assertEquals(listOf("cherry"), picked)
    }

    @Test
    fun theKeyboardMovesTheCursorOpensAndReorders() = runComposeUiTest {
        val events = mutableListOf<String>()
        setContent {
            Roster(
                sections = listOf(RosterSection("fruit", listOf(apple, banana, cherry))),
                selection = RosterSelection(onSelect = { events += "open:${it.id}" }),
                actions = RosterActions(reorder = RosterReorder { item, to -> events += "move:${item.id}:$to" }),
            )
        }
        val list = onNodeWithTag(RosterListTags.LIST.value)
        list.requestFocus()
        list.performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
            pressKey(Key.Enter)
            withKeyDown(Key.AltLeft) { pressKey(Key.DirectionUp) }
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
            pressKey(Key.Enter)
        }
        assertEquals(listOf("open:banana", "move:banana:0", "open:cherry"), events)
    }

    @Test
    fun aPointerHostShowsHoverActionsAndTheMenuOnTheHostsRowMenu() = runComposeUiTest {
        val events = mutableListOf<String>()
        setContent {
            ProvideLettaHost(menuAsButtons(LettaInput.Pointer)) {
                Roster(
                    sections = listOf(RosterSection("fruit", listOf(banana))),
                    actions = RosterActions(
                        menu = { item, _ -> listOf(com.letta.mobile.ui.components.LettaMenuItem("Peel") { events += "peel:${item.id}" }) },
                        quick = { item -> listOf(RosterAction("Eat", onClick = { events += "eat:${item.id}" })) },
                        swipe = { item -> RosterAction("Toss", onClick = { events += "toss:${item.id}" }) },
                    ),
                )
            }
        }
        val rowTag = row("fruit", banana)
        // Pointer: the quick action is a hover button, not a menu entry, and no swipe.
        onNodeWithText("menu:Eat").assertDoesNotExist()
        onNodeWithTag(rowTag.value).performMouseInput { moveTo(center) }
        onNodeWithTag(rowTag.quick("Eat")).performClick()
        onNodeWithText("menu:Peel").performClick()
        assertEquals(listOf("eat:banana", "peel:banana"), events)
    }

    @Test
    fun aTouchHostSwipesAndPutsQuickActionsInTheMenu() = runComposeUiTest {
        val events = mutableListOf<String>()
        setContent {
            ProvideLettaHost(menuAsButtons(LettaInput.Touch)) {
                Roster(
                    sections = listOf(RosterSection("fruit", listOf(banana))),
                    actions = RosterActions(
                        quick = { item -> listOf(RosterAction("Eat", onClick = { events += "eat:${item.id}" })) },
                        swipe = { item -> RosterAction("Toss", onClick = { events += "toss:${item.id}" }) },
                    ),
                )
            }
        }
        // Across the row itself (the stand-in menu's buttons sit under it).
        onNodeWithTag(row("fruit", banana).value).performTouchInput {
            swipe(start = Offset(width - 2f, ROW_Y), end = Offset(2f, ROW_Y))
        }
        onNodeWithText("menu:Eat").performClick()
        assertEquals(listOf("toss:banana", "eat:banana"), events)
    }

    @Test
    fun renameOpensFromTheMenuOrF2AndSavesATrimmedNewTitle() = runComposeUiTest {
        val renames = mutableListOf<Pair<String, String>>()
        setContent {
            ProvideLettaHost(menuAsButtons(LettaInput.Pointer)) {
                Roster(
                    sections = listOf(RosterSection("fruit", listOf(apple, banana))),
                    actions = RosterActions(rename = RosterRename(titleOf = Fruit::name, onRename = { item, title -> renames += item.id to title })),
                )
            }
        }
        onNodeWithText("Banana").assertExists()
        // The menu entry the roster adds for a rename (the first row's).
        onAllNodesWithText("menu:Rename")[0].performClick()
        onNodeWithTag(RosterListTags.RENAME_FIELD).performTextReplacement("  Green apple ")
        onNodeWithTag(RosterListTags.RENAME_FIELD).performKeyInput { pressKey(Key.Enter) }
        runOnIdle { assertEquals(listOf("apple" to "Green apple"), renames) }
        onNodeWithTag(RosterListTags.RENAME_FIELD).assertDoesNotExist()

        val list = onNodeWithTag(RosterListTags.LIST.value)
        list.requestFocus()
        list.performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
            pressKey(Key.F2)
        }
        onNodeWithTag(RosterListTags.RENAME_FIELD).performKeyInput { pressKey(Key.Escape) }
        runOnIdle { assertEquals(1, renames.size) }
        onNodeWithText("Banana").assertExists()
    }

    private fun row(section: String, fruit: Fruit) = RosterListTags.LIST.row(RosterLayout.rowKey(section, fruit.id))

    /** A stand-in host: the row menu's entries as plain buttons under the row. */
    private fun menuAsButtons(input: LettaInput) = LettaHost(
        input = input,
        rowMenu = { items, content ->
            Column {
                content()
                items.forEach { Text("menu:${it.label}", Modifier.clickable(onClick = it.onClick)) }
            }
        },
    )

    @Composable
    private fun Roster(
        sections: List<RosterSection<Fruit>>,
        selection: RosterSelection<Fruit> = RosterSelection(),
        actions: RosterActions<Fruit> = RosterActions(),
        options: RosterOptions = RosterOptions(),
        empty: (@Composable () -> Unit)? = null,
    ) {
        MaterialTheme {
            RosterList(
                RosterSpec(sections, Fruit::id, selection, actions, options, empty),
                modifier = Modifier.width(320.dp).height(480.dp),
            ) { fruit, row -> RosterListRow(LettaListRowSpec(title = fruit.name), row) }
        }
    }
}

private const val ROW_Y = 10f

/** A drag moves a quarter of a row per step, four rows in all: past both neighbours of the first row. */
private const val DRAG_STEPS_PER_ROW = 4f
private const val DRAG_STEPS = 16

/** Longer than the long-press timeout, so the touch holds the row. */
private const val HOLD_MILLIS = 800L
