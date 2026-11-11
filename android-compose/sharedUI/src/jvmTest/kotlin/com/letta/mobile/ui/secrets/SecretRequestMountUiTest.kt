@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.secrets

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.letta.mobile.data.secrets.client.SecretClientOutcome
import com.letta.mobile.data.secrets.request.SecretReceiptStatus
import com.letta.mobile.ui.secrets.SecretRequestUiFixtures.SENTINEL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The card mounts only while a request is pending, opens the secure form, and the form outlives
 * its request's pending state (it shows the receipt) until closed. A request answered elsewhere
 * while the person is typing expires the form.
 */
class SecretRequestMountUiTest {
    private fun mount(client: UiFakeSecretRequestClient?, block: ComposeUiTest.() -> Unit) = runDesktopComposeUiTest {
        val host = client?.let { SecretRequestHost(it, agentName = { SecretRequestUiFixtures.AGENT_NAME }, clock = SecretRequestUiFixtures.clock) }
        setContent {
            MaterialTheme {
                ProvideSecretRequestHost(host) { SecretRequestMount { modifier -> SecretRequestModule(modifier) } }
            }
        }
        waitForIdle()
        block()
    }

    private fun ComposeUiTest.count(tag: String): Int = onAllNodesWithTag(tag).fetchSemanticsNodes().size

    @Test
    fun nothingMountsWithoutAHostOrWithoutAPendingRequest() {
        mount(null) { assertEquals(0, count(SecretRequestCardTags.CARD)) }
        mount(UiFakeSecretRequestClient()) { assertEquals(0, count(SecretRequestCardTags.CARD)) }
    }

    @Test
    fun theCardShowsWhoAsksForWhatAndWhenItExpiresAndLeavesWhenTheRequestDoes() {
        val client = UiFakeSecretRequestClient(SecretRequestUiFixtures.record(1), SecretRequestUiFixtures.record(2))
        mount(client) {
            assertEquals(1, count(SecretRequestCardTags.CARD))
            onNode(hasText("Ada is asking for a secret")).assertExists()
            onNode(hasText("Vault item shop/gelato, field api_key, in test.vault")).assertExists()
            onNode(hasText("Agent-authored reason")).assertExists()
            onNode(hasText(SecretRequestUiFixtures.PURPOSE)).assertExists()
            onNode(hasText("Expires in 15 minutes")).assertExists()
            onNode(hasText("1 more request is waiting")).assertExists()
            client.settle(SecretRequestUiFixtures.id(1))
            client.settle(SecretRequestUiFixtures.id(2))
            waitUntil(timeoutMillis = TIMEOUT_MS) { count(SecretRequestCardTags.CARD) == 0 }
        }
    }

    @Test
    fun theCardOpensTheFormWhichSubmitsOnceAndShowsTheReceiptAfterTheRequestSettles() {
        val client = UiFakeSecretRequestClient(SecretRequestUiFixtures.record())
        client.answer = { id ->
            client.settle(id)
            SecretClientOutcome.Settled(SecretRequestUiFixtures.receipt(SecretReceiptStatus.SAVED))
        }
        mount(client) {
            onNodeWithTag(SecretRequestCardTags.OPEN).performClick()
            waitUntil(timeoutMillis = TIMEOUT_MS) { count(SecretRequestFormTags.FIELD) == 1 }
            onNodeWithTag(SecretRequestFormTags.FIELD).performTextInput(SENTINEL)
            onNodeWithTag(SecretRequestFormTags.SUBMIT).performClick()
            waitUntil(timeoutMillis = TIMEOUT_MS) { count(SecretRequestFormTags.MESSAGE) == 1 }
            assertEquals(0, count(SecretRequestCardTags.CARD), "the card left with the pending request")
            onNode(hasText("Saved to vault item shop/gelato, field api_key.")).assertExists()
            assertEquals(listOf(SENTINEL), client.received)
            assertTrue(client.inputs.single().isWiped)
            onNodeWithTag(SecretRequestFormTags.CLOSE).performClick()
            waitUntil(timeoutMillis = TIMEOUT_MS) { count(SecretRequestFormTags.FORM) == 0 }
        }
    }

    @Test
    fun aRequestAnsweredElsewhereWhileTypingExpiresTheForm() {
        val client = UiFakeSecretRequestClient(SecretRequestUiFixtures.record())
        mount(client) {
            onNodeWithTag(SecretRequestCardTags.OPEN).performClick()
            waitUntil(timeoutMillis = TIMEOUT_MS) { count(SecretRequestFormTags.FIELD) == 1 }
            onNodeWithTag(SecretRequestFormTags.FIELD).performTextInput(SENTINEL)
            client.settle(SecretRequestUiFixtures.id(1))
            waitUntil(timeoutMillis = TIMEOUT_MS) { count(SecretRequestFormTags.MESSAGE) == 1 }
            onNode(hasText("This request expired or was already answered. Nothing was sent.")).assertExists()
            assertTrue(client.received.isEmpty())
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
