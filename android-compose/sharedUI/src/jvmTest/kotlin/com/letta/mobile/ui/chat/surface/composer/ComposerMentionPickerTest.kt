@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.composer.MentionKind
import com.letta.mobile.data.composer.Mentionable
import com.letta.mobile.ui.components.picker.LettaPickerTags
import kotlin.test.Test
import kotlin.test.assertEquals

/** letta-mobile-ckmub.3.8 (S8b): the `@` suggestions are the shared picker's body, not their own list. */
class ComposerMentionPickerTest {
    private fun mention(id: String, kind: MentionKind) = Mentionable(id = id, label = "label-$id", sublabel = null, kind = kind)

    @Test
    fun eachKindIsASectionCappedAtSixRows() {
        val files = (1..9).map { mention("f$it", MentionKind.File) }
        val sections = mentionSections(listOf(MentionKind.File to files, MentionKind.Agent to listOf(mention("a1", MentionKind.Agent))))

        assertEquals(listOf("Files", "Agents"), sections.map { it.title })
        assertEquals(MENTIONS_PER_SECTION, sections[0].items.size)
        assertEquals("File-f1", sections[0].items.first().key)
    }

    @Test
    fun choosingARowHandsBackTheMentionWithoutASearchField() = runComposeUiTest {
        val chosen = mutableListOf<String>()
        val groups = listOf(MentionKind.Memory to listOf(mention("m1", MentionKind.Memory), mention("m2", MentionKind.Memory)))
        setContent { MaterialTheme { ComposerMentionPicker(groups, onSelect = { chosen += it.id }) } }

        onNodeWithTag(LettaPickerTags.SEARCH).assertDoesNotExist()
        onNodeWithText("label-m2").performClick()
        waitForIdle()
        assertEquals(listOf("m2"), chosen)
    }
}
