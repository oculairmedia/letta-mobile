@file:OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)

package com.letta.mobile.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.settings.SettingsRow
import com.letta.mobile.ui.host.OverlayHost
import com.letta.mobile.ui.host.OverlayHostRoot
import com.letta.mobile.ui.host.PASTE_BLOCKED_HINT
import java.awt.datatransfer.StringSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A Settings field's Paste button over each clipboard outcome. */
class SettingsPasteButtonTest {
    @Test
    fun readableTextFillsTheFieldTrimmed() = runComposeUiTest {
        val field = pasteField { ClipEntry(StringSelection("  iroh://node@host:4433  ")) }

        onNodeWithTag(PASTE).performClick()

        waitUntil { field.value.value == "iroh://node@host:4433" }
        assertNull(field.host.snackbar)
    }

    @Test
    fun aBlockedReadFocusesTheFieldAndSaysHowToPaste() = runComposeUiTest {
        val field = pasteField { throw IllegalStateException("clipboard read refused") }

        onNodeWithTag(PASTE).performClick()

        waitUntil { field.host.snackbar != null }
        assertEquals(PASTE_BLOCKED_HINT, field.host.snackbar?.overlay?.message)
        onNodeWithText(PASTE_BLOCKED_HINT).assertExists()
        onNodeWithTag(SettingsSectionTags.ROW_PREFIX + KEY).assertIsFocused()
        assertEquals(DRAFT, field.value.value)
    }

    @Test
    fun anEmptyClipboardLeavesTheFieldAsItIs() = runComposeUiTest {
        val field = pasteField { null }

        onNodeWithTag(PASTE).performClick()
        waitForIdle()

        assertEquals(DRAFT, field.value.value)
        assertNull(field.host.snackbar)
        onNodeWithTag(SettingsSectionTags.ROW_PREFIX + KEY).assertIsNotFocused()
    }

    /** A Host address row with a Paste button over a clipboard whose read is [read]. */
    private fun ComposeUiTest.pasteField(read: () -> ClipEntry?): PasteField {
        val field = PasteField(mutableStateOf(DRAFT), OverlayHost())
        val clipboard = object : Clipboard {
            override suspend fun getClipEntry(): ClipEntry? = read()

            override suspend fun setClipEntry(clipEntry: ClipEntry?) = Unit
        }
        setContent {
            Frame {
                CompositionLocalProvider(LocalClipboard provides clipboard) {
                    OverlayHostRoot(field.host) {
                        SettingsSection(SettingsHeader("backend", "Backend")) {
                            TextFieldRow(SettingsRow.Text(KEY, "Host address"), field.value.value, paste = true) { field.value.value = it }
                        }
                    }
                }
            }
        }
        return field
    }

    private class PasteField(val value: MutableState<String>, val host: OverlayHost)

    private companion object {
        const val KEY = "server-url"
        const val DRAFT = "draft"
        const val PASTE = SettingsSectionTags.PASTE_PREFIX + KEY
    }
}
