package com.letta.mobile.data.secrets

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentVaultControllerTest {
    private class FakeSecretsSource : AgentSecretsSource {
        val stored = linkedMapOf("OPENAI_API_KEY" to "sk-one", "GITHUB_TOKEN" to "ghp-two")
        val applied = mutableListOf<AgentSecretChanges>()
        var failApply: String? = null

        override suspend fun list(agentId: String): List<AgentSecret> =
            stored.map { (key, value) -> AgentSecret(key, SecretValue(value)) }.sortedBy { it.key }

        override suspend fun apply(agentId: String, changes: AgentSecretChanges): List<String> {
            failApply?.let { throw AgentSecretsException(it) }
            applied += changes
            changes.set.forEach { (key, value) -> stored[key] = value.reveal() }
            changes.unset.forEach(stored::remove)
            return stored.keys.sorted()
        }
    }

    private fun TestScope.vault(source: FakeSecretsSource = FakeSecretsSource()): Pair<AgentVaultController, FakeSecretsSource> {
        val controller = AgentVaultController(source, backgroundScope)
        controller.selectAgent("agent-1")
        runCurrent()
        return controller to source
    }

    @Test
    fun valuesListMaskedUntilRevealed() = runTest {
        val (controller, _) = vault()
        val state = controller.state.value
        assertEquals(AgentVaultLoad.Loaded, state.load)
        assertEquals(listOf("GITHUB_TOKEN", "OPENAI_API_KEY"), state.secrets.map { it.key })
        assertEquals(SecretValue.MASK, state.displayValue("OPENAI_API_KEY"))

        controller.toggleReveal("OPENAI_API_KEY")
        assertEquals("sk-one", controller.state.value.displayValue("OPENAI_API_KEY"))
        assertEquals(SecretValue.MASK, controller.state.value.displayValue("GITHUB_TOKEN"))

        controller.toggleReveal("OPENAI_API_KEY")
        assertEquals(SecretValue.MASK, controller.state.value.displayValue("OPENAI_API_KEY"))
    }

    @Test
    fun hideAllAndAgentSwitchesMaskEverythingAgain() = runTest {
        val (controller, _) = vault()
        controller.toggleReveal("OPENAI_API_KEY")
        controller.hideAll()
        assertTrue(controller.state.value.revealed.isEmpty())

        controller.toggleReveal("GITHUB_TOKEN")
        controller.selectAgent("agent-2")
        assertTrue(controller.state.value.revealed.isEmpty())
        assertEquals("agent-2", controller.state.value.agentId)
    }

    @Test
    fun addingASecretAppliesOneSetAndReloads() = runTest {
        val (controller, source) = vault()
        controller.startAdding()
        controller.updateDraftKey("weather_api_key")
        controller.updateDraftValue("w-123")
        assertTrue(controller.state.value.canSaveDraft)
        controller.saveDraft()
        runCurrent()

        val changes = source.applied.single()
        assertEquals(setOf("WEATHER_API_KEY"), changes.set.keys)
        assertEquals("w-123", changes.set.getValue("WEATHER_API_KEY").reveal())
        assertTrue(changes.unset.isEmpty())
        assertNull(controller.state.value.draft)
        assertTrue(controller.state.value.secrets.any { it.key == "WEATHER_API_KEY" })
    }

    @Test
    fun theDraftExplainsWhatIsMissing() = runTest {
        val (controller, source) = vault()
        controller.startAdding()
        assertEquals("Name the secret.", controller.state.value.draftProblem)
        controller.updateDraftKey("9lives")
        assertEquals("Use letters, digits and underscores, not starting with a digit.", controller.state.value.draftProblem)
        controller.updateDraftKey("openai_api_key")
        assertEquals("OPENAI_API_KEY already exists; edit it instead.", controller.state.value.draftProblem)
        controller.updateDraftKey("NEW_ONE")
        assertEquals("Enter a value.", controller.state.value.draftProblem)
        controller.saveDraft()
        runCurrent()
        assertTrue(source.applied.isEmpty())
    }

    @Test
    fun replacingAValueStartsBlankAndKeepsTheKey() = runTest {
        val (controller, source) = vault()
        controller.startEditing("GITHUB_TOKEN")
        val draft = controller.state.value.draft!!
        assertFalse(draft.isNew)
        assertTrue(draft.value.isBlank, "the old value is never copied into the editor")
        controller.updateDraftKey("SOMETHING_ELSE")
        assertEquals("GITHUB_TOKEN", controller.state.value.draft!!.key)

        controller.updateDraftValue("ghp-new")
        controller.saveDraft()
        runCurrent()
        assertEquals("ghp-new", source.stored["GITHUB_TOKEN"])
    }

    @Test
    fun deletingIsConfirmedThenUnset() = runTest {
        val (controller, source) = vault()
        controller.requestDelete("GITHUB_TOKEN")
        assertEquals("GITHUB_TOKEN", controller.state.value.pendingDelete)
        controller.cancelDelete()
        assertNull(controller.state.value.pendingDelete)

        controller.requestDelete("GITHUB_TOKEN")
        controller.confirmDelete()
        runCurrent()
        assertEquals(setOf("GITHUB_TOKEN"), source.applied.single().unset)
        assertEquals(listOf("OPENAI_API_KEY"), controller.state.value.secrets.map { it.key })
    }

    @Test
    fun aFailedApplyShowsTheReasonAndKeepsTheDraft() = runTest {
        val (controller, _) = vault(FakeSecretsSource().apply { failApply = "core rejected the update" })
        controller.startAdding()
        controller.updateDraftKey("K")
        controller.updateDraftValue("v")
        controller.saveDraft()
        runCurrent()
        val state = controller.state.value
        assertEquals("core rejected the update", state.error)
        assertFalse(state.saving)
        assertEquals("K", state.draft?.key)
        controller.dismissError()
        assertNull(controller.state.value.error)
    }

    @Test
    fun closingDropsEveryValueFromMemory() = runTest {
        val (controller, _) = vault()
        controller.toggleReveal("OPENAI_API_KEY")
        controller.close()
        val state = controller.state.value
        assertTrue(state.secrets.isEmpty())
        assertNull(state.agentId)
        assertIs<AgentVaultLoad.Idle>(state.load)
    }
}
