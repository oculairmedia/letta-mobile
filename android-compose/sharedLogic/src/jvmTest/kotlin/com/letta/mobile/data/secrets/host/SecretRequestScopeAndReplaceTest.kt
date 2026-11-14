package com.letta.mobile.data.secrets.host

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.secrets.journal.InMemorySecretRequestJournal
import com.letta.mobile.data.secrets.request.AgentSecretRequest
import com.letta.mobile.data.secrets.request.RequestingAgent
import com.letta.mobile.data.secrets.request.SecretFailureReason
import com.letta.mobile.data.secrets.request.SecretReceiptStatus
import com.letta.mobile.data.secrets.request.SecretRequestCreated
import com.letta.mobile.data.secrets.request.SecretRequestId
import com.letta.mobile.data.secrets.request.SecretRequestProblem
import com.letta.mobile.data.secrets.request.SecretRequestRecord
import com.letta.mobile.data.secrets.request.SecretRequestTestData
import com.letta.mobile.data.secrets.request.SecretRequestTestData.TARGET
import com.letta.mobile.data.secrets.request.SecretServiceError
import com.letta.mobile.data.secrets.request.VaultItemName
import com.letta.mobile.data.serialization.LettaJson
import com.letta.mobile.plugin.keystore.KeystoreError
import com.letta.mobile.plugin.keystore.SecretValue
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which items an agent may name (per agent by default, a shared root only when the host owner
 * chooses one) and the no-silent-replace rule: only a request the host found replacing an existing
 * value when it was made may replace one.
 */
class SecretRequestScopeAndReplaceTest {
    private val outsideScope =
        SecretServiceResult.Refused(SecretServiceError.INVALID_REQUEST, SecretRequestProblem.ITEM_OUTSIDE_SCOPE)

    private fun perAgentService(provider: TestKeystoreProvider = TestKeystoreProvider()): SecretRequestService {
        val settings = SecretRequestSettings(clock = TestClock(), random = CountingRandom())
        return SecretRequestService(InMemorySecretRequestJournal(), provider, settings)
    }

    private fun asking(item: String): AgentSecretRequest = SecretRequestTestData.request().copy(vaultItemName = VaultItemName(item))

    private suspend fun SecretRequestService.recordOf(id: SecretRequestId): SecretRequestRecord =
        (get(HUMAN, id) as SecretServiceResult.Ok).value

    @Test
    fun theDefaultScopeIsPerAgent() {
        assertEquals(SecretItemScope.PerAgent, SecretRequestPolicy().itemScope)
        assertEquals(SecretItemScope.PerAgent, SecretRequestSettings().policy.itemScope)
        assertEquals(VaultItemName("agents/agent-requester"), SecretItemScope.PerAgent.rootFor(REQUESTER))
    }

    @Test
    fun anAgentMayNameOnlyItemsUnderItsOwnPrefix() = runTest {
        val service = perAgentService()
        val own = listOf("agents/agent-requester", "agents/agent-requester/openai", "agents/agent-requester/team/openai")
        own.forEach { item -> assertTrue(service.request(REQUESTER, asking(item)) is SecretServiceResult.Ok, item) }
        val foreign = listOf("agents/agent-bystander/openai", "agents/agent-requesterx/openai", "team/openai", "agents", "openai")
        foreign.forEach { item -> assertEquals(outsideScope, service.request(REQUESTER, asking(item)), item) }
        assertTrue(service.request(BYSTANDER, asking("agents/agent-bystander/openai")) is SecretServiceResult.Ok)
    }

    @Test
    fun anAgentIdThatIsNotOneSegmentMayNameNoItem() = runTest {
        val service = perAgentService()
        for (id in listOf("a/b", "..", ".", "a b")) {
            assertEquals(null, SecretItemScope.PerAgent.rootFor(RequestingAgent(AgentId(id))), id)
        }
        assertEquals(outsideScope, service.request(RequestingAgent(AgentId("a/b")), asking("agents/a/b/openai")))
    }

    @Test
    fun aSharedRootIsExplicitAndMustBeWellFormed() = runTest {
        assertFailsWith<IllegalArgumentException> { SecretItemScope.Shared(VaultItemName("a//b")) }
        assertFailsWith<IllegalArgumentException> { SecretItemScope.Shared(VaultItemName("")) }
        val shared = ServiceHarness()
        assertTrue(shared.service.request(BYSTANDER, SecretRequestTestData.request()) is SecretServiceResult.Ok)
        assertEquals(outsideScope, shared.service.request(REQUESTER, asking("teams/openai")))
    }

    @Test
    fun theHostDecidesAtCreationWhetherARequestReplacesAValueAndTheAgentCannotTell() = runTest {
        val harness = ServiceHarness()
        val fresh = harness.service.request(REQUESTER, SecretRequestTestData.request()) as SecretServiceResult.Ok
        harness.provider.seed(TARGET_REF, "owner-value")
        val over = harness.service.request(REQUESTER, SecretRequestTestData.request()) as SecretServiceResult.Ok

        assertFalse(harness.service.recordOf(fresh.value.requestId).details.replacesExisting)
        assertTrue(harness.service.recordOf(over.value.requestId).details.replacesExisting)
        val wire = listOf(fresh.value, over.value).map { LettaJson.wire.encodeToJsonElement(SecretRequestCreated.serializer(), it) }
        assertEquals(wire[0].toString().length, wire[1].toString().length, "the agent's answers differ only in the id")
        assertTrue(wire.none { "replaces" in it.toString() })
        assertTrue(harness.provider.lent.all(SecretValue::isWiped), "the creation-time read is wiped")
    }

    @Test
    fun aSlotThatAppearsAfterTheRequestWasMadeIsNeverReplaced() = runTest {
        val harness = ServiceHarness()
        val id = harness.pending()
        harness.provider.seed(TARGET_REF, "owner-value")
        val value = secretOf("sentinel-late-slot")

        val answer = harness.service.submit(HUMAN, id, value)

        val changed = SecretServiceResult.Ok(
            com.letta.mobile.data.secrets.request.SecretRequestReceipt(
                SecretReceiptStatus.FAILED, TARGET.item, TARGET.field, SecretFailureReason.TARGET_CHANGED,
            ),
        )
        assertEquals(changed, answer)
        assertEquals("owner-value", harness.provider.storedText(TARGET_REF))
        assertEquals(0, harness.provider.writes)
        assertTrue(value.isWiped)
        assertTrue(harness.provider.lent.all(SecretValue::isWiped))
    }

    @Test
    fun aSlotTheHostCouldNotReadAtCreationIsTreatedAsEmptyAndSoIsNeverReplaced() = runTest {
        val harness = ServiceHarness()
        harness.provider.seed(TARGET_REF, "owner-value")
        harness.provider.failWith = KeystoreError.Locked
        val id = harness.pending()
        harness.provider.failWith = null

        assertFalse(harness.service.recordOf(id).details.replacesExisting)
        val receipt = harness.service.submit(HUMAN, id, secretOf("sentinel-unread")) as SecretServiceResult.Ok
        assertEquals(SecretFailureReason.TARGET_CHANGED, receipt.value.reason)
        assertEquals("owner-value", harness.provider.storedText(TARGET_REF))
    }

    @Test
    fun aRequestMadeOverAnExistingValueReplacesItAndAVanishedOneIsCreated() = runTest {
        val harness = ServiceHarness()
        harness.provider.seed(TARGET_REF, "old-value")
        val replacing = harness.pending()
        val saved = harness.service.submit(HUMAN, replacing, secretOf("new-value")) as SecretServiceResult.Ok
        assertEquals(SecretReceiptStatus.SAVED, saved.value.status)
        assertEquals("new-value", harness.provider.storedText(TARGET_REF))

        val other = ServiceHarness()
        other.provider.seed(TARGET_REF, "old-value")
        val id = other.pending()
        other.provider.remove(TARGET_REF)
        val created = other.service.submit(HUMAN, id, secretOf("created-value")) as SecretServiceResult.Ok
        assertEquals(SecretReceiptStatus.SAVED, created.value.status, "nothing is replaced, so creating is allowed")
        assertEquals("created-value", other.provider.storedText(TARGET_REF))
    }
}
