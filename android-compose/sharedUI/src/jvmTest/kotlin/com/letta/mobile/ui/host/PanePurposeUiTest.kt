@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.host

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.ui.host.purpose.PanePurpose
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A pane's purpose decides how it may be dismissed: an information pane closes on a tap outside and on
 * Escape, a form ignores a tap outside but still closes on Escape, and a pane that must be answered
 * closes only through its own buttons.
 */
class PanePurposeUiTest {
    @Test
    fun theRulesPerPurpose() {
        assertTrue(PanePurpose.Info.dismissesOnOutside && PanePurpose.Info.dismissesOnKey)
        assertFalse(PanePurpose.Form.dismissesOnOutside)
        assertTrue(PanePurpose.Form.dismissesOnKey)
        assertFalse(PanePurpose.Required.dismissesOnOutside || PanePurpose.Required.dismissesOnKey)
    }

    private fun ComposeUiTest.escapeCloses(purpose: PanePurpose): Boolean {
        var dismissed by mutableStateOf(false)
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLettaHost provides LettaHost.Pointer) {
                    if (!dismissed) {
                        LettaPane(LettaPaneKind.Dialog, onDismiss = { dismissed = true }, testTag = CONTENT, purpose = purpose) {
                            Text("Body")
                        }
                    }
                }
            }
        }
        onNodeWithTag(CONTENT).performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        return dismissed
    }

    @Test
    fun escapeClosesAnInformationPane() = runComposeUiTest { assertEquals(true, escapeCloses(PanePurpose.Info)) }

    @Test
    fun escapeClosesAForm() = runComposeUiTest { assertEquals(true, escapeCloses(PanePurpose.Form)) }

    @Test
    fun escapeDoesNotCloseAPaneThatMustBeAnswered() = runComposeUiTest { assertEquals(false, escapeCloses(PanePurpose.Required)) }

    @Test
    fun aPaneThatMustBeAnsweredHasNoKeyDismiss() {
        val spec = PaneSpec(onDismiss = {}, purpose = PanePurpose.Required)
        assertEquals(null, spec.keyDismiss())
        assertTrue(PaneSpec(onDismiss = {}, purpose = PanePurpose.Form).keyDismiss() != null)
    }

    private companion object {
        const val CONTENT = "pane-purpose-content"
    }
}
