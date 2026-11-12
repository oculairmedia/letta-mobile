@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.secrets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.secrets.client.SecretClientOutcome
import com.letta.mobile.data.secrets.client.SecretFormController
import com.letta.mobile.data.secrets.client.SecretFormState
import com.letta.mobile.data.secrets.client.SecretInputPresence
import com.letta.mobile.data.secrets.request.SecretReceiptStatus
import com.letta.mobile.data.secrets.request.SecretRequestRecord
import com.letta.mobile.ui.secrets.SecretRequestUiFixtures.SENTINEL
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The secure form over a fake client: the field holds masks only, the value reaches the client
 * once and is wiped, nothing saveable or in the semantics tree holds it, and decline, expiry and
 * retry behave as the state machine says.
 */
class SecretRequestFormUiTest {
    private var controller: SecretFormController? = null

    @Composable
    private fun Form(client: UiFakeSecretRequestClient, record: SecretRequestRecord) {
        val scope = rememberCoroutineScope()
        val form = remember { SecretFormController(record, client, scope, SecretRequestUiFixtures.clock).also { controller = it } }
        MaterialTheme {
            Box(Modifier.width(FORM_WIDTH)) {
                SecretRequestForm(form, SecretRequestUiFixtures.AGENT_NAME, onClose = {})
            }
        }
    }

    private fun form(
        client: UiFakeSecretRequestClient,
        record: SecretRequestRecord = SecretRequestUiFixtures.record(),
        block: ComposeUiTest.() -> Unit,
    ) =
        runDesktopComposeUiTest {
            setContent { Form(client, record) }
            waitForIdle()
            block()
        }

    private fun ComposeUiTest.fieldText(): String =
        onNodeWithTag(SecretRequestFormTags.FIELD).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    private fun ComposeUiTest.semanticsText(): String = onAllNodes(SemanticsMatcher("every node") { true }, useUnmergedTree = true)
        .fetchSemanticsNodes().joinToString("\n") { it.config.toString() }

    private fun ComposeUiTest.message(): String =
        onNodeWithTag(SecretRequestFormTags.MESSAGE).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }

    private fun ComposeUiTest.type(text: String) {
        onNodeWithTag(SecretRequestFormTags.FIELD).performTextInput(text)
        waitForIdle()
    }

    @Test
    fun theFieldHoldsMasksOnlyAndTheValueReachesTheClientOnceAndIsWiped() {
        val client = UiFakeSecretRequestClient(SecretRequestUiFixtures.record())
        form(client) {
            onNodeWithTag(SecretRequestFormTags.SUBMIT).assertIsNotEnabled()
            type(SENTINEL)
            assertEquals(SECRET_MASK_CHAR.toString().repeat(SENTINEL.length), fieldText())
            assertFalse(semanticsText().contains(SENTINEL), "the semantics tree holds the value")
            onNodeWithTag(SecretRequestFormTags.SUBMIT).assertIsEnabled().performClick()
            waitUntil(timeoutMillis = TIMEOUT_MS) { controller?.state?.value is SecretFormState.Done }
            assertEquals(listOf(SENTINEL), client.received)
            assertTrue(client.inputs.single().isWiped)
            assertEquals("Saved to vault item shop/gelato, field api_key.", message())
            assertFalse(semanticsText().contains(SENTINEL))
        }
    }

    @Test
    fun submitIsDisabledWhileSubmittingAndADoubleClickSendsOnce() {
        val client = UiFakeSecretRequestClient(SecretRequestUiFixtures.record())
        val hold = CompletableDeferred<SecretClientOutcome>()
        client.answer = { hold.await() }
        form(client) {
            type(SENTINEL)
            onNodeWithTag(SecretRequestFormTags.SUBMIT).performClick()
            waitForIdle()
            onNodeWithTag(SecretRequestFormTags.SUBMIT).assertIsNotEnabled()
            onNodeWithTag(SecretRequestFormTags.DECLINE).assertIsNotEnabled()
            assertEquals("", fieldText(), "the field is cleared once the value is handed over")
            controller!!.submit()
            hold.complete(SecretClientOutcome.Unreachable)
            waitUntil(timeoutMillis = TIMEOUT_MS) { controller?.state?.value is SecretFormState.Failed }
            assertEquals(1, client.inputs.size)
            assertTrue(client.inputs.single().isWiped)
        }
    }

    @Test
    fun aRetryableFailureOffersRetryWithAnEmptyFieldThenSaves() {
        val client = UiFakeSecretRequestClient(SecretRequestUiFixtures.record())
        client.answer = { SecretClientOutcome.Unreachable }
        form(client) {
            type(SENTINEL)
            onNodeWithTag(SecretRequestFormTags.SUBMIT).performClick()
            waitUntil(timeoutMillis = TIMEOUT_MS) { controller?.state?.value is SecretFormState.Failed }
            assertTrue(message().contains("did not answer"))
            client.answer = { SecretClientOutcome.Settled(SecretRequestUiFixtures.receipt(SecretReceiptStatus.SAVED)) }
            onNodeWithTag(SecretRequestFormTags.RETRY).performClick()
            waitForIdle()
            assertEquals("", fieldText())
            onNodeWithTag(SecretRequestFormTags.SUBMIT).assertIsNotEnabled()
            type("second-try")
            onNodeWithTag(SecretRequestFormTags.SUBMIT).performClick()
            waitUntil(timeoutMillis = TIMEOUT_MS) { controller?.state?.value is SecretFormState.Done }
            assertEquals(listOf(SENTINEL, "second-try"), client.received)
            assertTrue(client.inputs.all { it.isWiped })
        }
    }

    @Test
    fun declineWipesTheValueAndTellsTheHost() {
        val client = UiFakeSecretRequestClient(SecretRequestUiFixtures.record())
        form(client) {
            type(SENTINEL)
            onNodeWithTag(SecretRequestFormTags.DECLINE).performClick()
            waitUntil(timeoutMillis = TIMEOUT_MS) { client.declined.isNotEmpty() }
            assertEquals(SecretFormState.Declined, controller?.state?.value)
            assertEquals(0, controller!!.reveal { it.length })
            assertTrue(client.received.isEmpty())
            onNodeWithTag(SecretRequestFormTags.CLOSE).assertIsEnabled()
        }
    }

    @Test
    fun anExpiredRequestOpensExpiredAndCannotBeSubmitted() {
        val stale = SecretRequestUiFixtures.record(expiresAtMs = SecretRequestUiFixtures.NOW.toEpochMilliseconds())
        val client = UiFakeSecretRequestClient(stale)
        form(client, stale) {
            assertEquals(SecretFormState.Expired, controller?.state?.value)
            assertTrue(message().contains("expired"))
            onNodeWithTag(SecretRequestFormTags.FIELD).assertDoesNotExist()
            assertTrue(client.inputs.isEmpty())
        }
    }

    @Test
    fun theRevealToggleIsLabelledAndHidingLeavesMasks() {
        val client = UiFakeSecretRequestClient(SecretRequestUiFixtures.record())
        form(client) {
            type(SENTINEL)
            assertEquals(listOf("Show the value"), findDescription(SecretRequestFormTags.REVEAL))
            onNodeWithTag(SecretRequestFormTags.REVEAL).performClick()
            waitForIdle()
            assertEquals(listOf("Hide the value"), findDescription(SecretRequestFormTags.REVEAL))
            onNodeWithTag(SecretRequestFormTags.REVEAL).performClick()
            waitForIdle()
            assertEquals(SECRET_MASK_CHAR.toString().repeat(SENTINEL.length), fieldText())
        }
    }

    @Test
    fun nothingSaveableHoldsTheValueAndARestoredFormStartsEmpty() {
        val client = UiFakeSecretRequestClient(SecretRequestUiFixtures.record())
        runDesktopComposeUiTest {
            // StateRestorationTester is not implemented on desktop, so save and restore by hand: save the
            // registry, drop the form, and compose it again over a registry restored from what was saved.
            var restored by mutableStateOf<Map<String, List<Any?>>?>(null)
            var generation by mutableStateOf(0)
            var registry: SaveableStateRegistry? = null
            setContent {
                key(generation) {
                    val current = remember { SaveableStateRegistry(restored) { true } }
                    registry = current
                    CompositionLocalProvider(LocalSaveableStateRegistry provides current) { Form(client, SecretRequestUiFixtures.record()) }
                }
            }
            waitForIdle()
            type(SENTINEL)
            val saved = registry!!.performSave()
            assertFalse(saved.toString().contains(SENTINEL), "saved state holds the value")
            val before = controller!!
            restored = saved
            generation++
            waitForIdle()
            assertEquals("", fieldText(), "the field is not restored")
            assertEquals(0, before.reveal { it.length }, "leaving composition zeroed the buffer")
            assertEquals(SecretFormState.Editing(SecretInputPresence.EMPTY), before.state.value)
            onNodeWithTag(SecretRequestFormTags.SUBMIT).assertIsNotEnabled()
        }
    }

    @Test
    fun leavingCompositionZeroesTheBuffer() {
        val client = UiFakeSecretRequestClient(SecretRequestUiFixtures.record())
        runDesktopComposeUiTest {
            var shown by mutableStateOf(true)
            setContent { if (shown) Form(client, SecretRequestUiFixtures.record()) }
            waitForIdle()
            type(SENTINEL)
            assertEquals(SENTINEL.length, controller!!.reveal { it.length })
            shown = false
            waitForIdle()
            assertEquals(0, controller!!.reveal { it.length })
        }
    }

    private fun ComposeUiTest.findDescription(tag: String): List<String> =
        onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()

    private companion object {
        val FORM_WIDTH = 480.dp
        const val TIMEOUT_MS = 5_000L
    }
}
