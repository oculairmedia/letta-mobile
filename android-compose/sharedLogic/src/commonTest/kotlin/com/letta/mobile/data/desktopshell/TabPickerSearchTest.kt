package com.letta.mobile.data.desktopshell

import kotlin.test.Test
import kotlin.test.assertEquals

class TabPickerSearchTest {
    private val items = listOf(
        TabPickerItem("c1", "Emmanuel clarifies: canvas mechanics", "Meridian", TabPickerItem.Kind.CONVERSATION),
        TabPickerItem("c2", "paginated response notes", "Meridian", TabPickerItem.Kind.CONVERSATION),
        TabPickerItem("k1", "Canvas (Meridian)", "Canvas", TabPickerItem.Kind.CANVAS),
        TabPickerItem("k2", "Canvas 4", "Canvas", TabPickerItem.Kind.CANVAS),
    )

    @Test
    fun blankQueryKeepsEveryItemInOrder() {
        assertEquals(items, TabPickerSearch.filter(items, "   "))
    }

    @Test
    fun everyWordMustMatchTitleOrSubtitleIgnoringCase() {
        assertEquals(listOf("c1", "k1"), TabPickerSearch.filter(items, "canvas mer").map { it.id })
        assertEquals(listOf("c1", "k1", "k2"), TabPickerSearch.filter(items, "CANVAS").map { it.id })
        assertEquals(listOf("c1", "c2", "k1"), TabPickerSearch.filter(items, "meridian").map { it.id })
        assertEquals(emptyList(), TabPickerSearch.filter(items, "nothing here"))
    }
}
