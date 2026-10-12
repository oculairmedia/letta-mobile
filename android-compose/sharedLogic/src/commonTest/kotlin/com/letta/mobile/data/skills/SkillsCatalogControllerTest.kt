package com.letta.mobile.data.skills

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The skills half of the shared Skills page (letta-mobile-zxk2n.32). */
@OptIn(ExperimentalCoroutinesApi::class)
class SkillsCatalogControllerTest {
    private val github = Skill(name = "github", description = "GitHub issues", tags = listOf("code"))
    private val notion = Skill(name = "notion", description = "Notion pages")

    @Test
    fun reloadPublishesTheRegistryAndTheAgentsInstalledSet() = runTest {
        val api = FakeSkillsApi(registry = listOf(github, notion), installed = mutableMapOf("agent-1" to mutableSetOf("notion")))
        val controller = SkillsCatalogController(api, this, agentId = "agent-1")
        controller.reload()
        advanceUntilIdle()
        val state = controller.state.value
        assertEquals(listOf("github", "notion"), state.skills.map { it.name })
        assertEquals(setOf("notion"), state.installedSkillNames)
        assertFalse(state.isLoading)
        assertTrue(state.canManage)
    }

    @Test
    fun installAndRemoveReloadTheInstalledSet() = runTest {
        val api = FakeSkillsApi(registry = listOf(github, notion), installed = mutableMapOf("agent-1" to mutableSetOf()))
        val controller = SkillsCatalogController(api, this, agentId = "agent-1")
        controller.install("github")
        advanceUntilIdle()
        assertEquals(setOf("github"), controller.state.value.installedSkillNames)
        controller.uninstall("github")
        advanceUntilIdle()
        assertEquals(emptySet(), controller.state.value.installedSkillNames)
    }

    @Test
    fun withoutAnAgentTheCatalogIsReadOnly() = runTest {
        val api = FakeSkillsApi(registry = listOf(github), installed = mutableMapOf())
        val controller = SkillsCatalogController(api, this)
        controller.reload()
        controller.install("github")
        advanceUntilIdle()
        assertFalse(controller.state.value.canManage)
        assertEquals(0, api.mutations)
    }

    @Test
    fun aFailedLoadSaysSo() = runTest {
        val controller = SkillsCatalogController(FakeSkillsApi(failWith = "boom"), this, agentId = "agent-1")
        controller.reload()
        advanceUntilIdle()
        assertEquals("boom", controller.state.value.errorMessage)
        assertFalse(controller.state.value.isLoading)
    }

    @Test
    fun noApiMeansUnavailable() {
        val state = SkillsCatalogController(api = null, scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job()), agentId = "agent-1").state.value
        assertFalse(state.available)
        assertFalse(state.canManage)
        assertNull(state.errorMessage)
    }

    @Test
    fun theFilterKeepsUnlistedInstalledSkillsAndSearchesNameDescriptionAndTags() {
        val installed = setOf("notion", "local-only")
        assertEquals(listOf("github", "notion", "local-only"), SkillsPageFilter.skills(listOf(github, notion), installed, "").map { it.name })
        assertEquals(listOf("notion", "local-only"), SkillsPageFilter.skills(listOf(github, notion), installed, "", assignedOnly = true).map { it.name })
        assertEquals(listOf("github"), SkillsPageFilter.skills(listOf(github, notion), installed, "CODE").map { it.name })
        assertEquals(listOf("notion"), SkillsPageFilter.skills(listOf(github, notion), installed, "pages").map { it.name })
    }

    private class FakeSkillsApi(
        private val registry: List<Skill> = emptyList(),
        private val installed: MutableMap<String, MutableSet<String>> = mutableMapOf(),
        private val failWith: String? = null,
    ) : SkillsApi {
        var mutations = 0

        override suspend fun listSkills(): List<Skill> {
            failWith?.let { throw IllegalStateException(it) }
            return registry
        }

        override suspend fun listAgentSkills(agentId: String): List<Skill> =
            installed[agentId].orEmpty().map { Skill(name = it) }

        override suspend fun installSkill(agentId: String, skillName: String) {
            mutations++
            installed.getOrPut(agentId) { mutableSetOf() }.add(skillName)
        }

        override suspend fun uninstallSkill(agentId: String, skillName: String) {
            mutations++
            installed[agentId]?.remove(skillName)
        }
    }
}
