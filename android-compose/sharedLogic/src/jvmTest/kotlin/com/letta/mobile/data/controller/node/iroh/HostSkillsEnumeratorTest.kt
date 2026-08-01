package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * letta-mobile-7dm1q / lgns8.21.2 acceptance: the host-side skill-root enumerator
 * that finally hydrates [NativeSkillsCatalog].
 *
 * The fixture layout mirrors the live root (sampled read-only, 2026-08-01):
 * one directory per skill, each with a `SKILL.md` that MAY open with YAML
 * frontmatter — several real skills have none.
 */
class HostSkillsEnumeratorTest {
    private val roots = mutableListOf<File>()

    @AfterTest
    fun cleanUp() = roots.forEach { it.deleteRecursively() }

    private fun root(): File =
        Files.createTempDirectory("skills-root").toFile().also { roots += it }

    private fun skill(root: File, name: String, body: String) {
        File(root, name).mkdirs()
        File(File(root, name), "SKILL.md").writeText(body)
    }

    @Test
    fun readsNameAndDescriptionFromYamlFrontmatter() {
        val root = root()
        skill(
            root,
            "agent-messaging",
            """
            ---
            name: agent-messaging
            description: Send a direct message to another Letta agent.
            ---

            # agent-messaging
            """.trimIndent(),
        )
        val entry = assertNotNull(HostSkillsEnumerator.enumerate(root)).single().jsonObject
        assertEquals("agent-messaging", entry.getValue("name").jsonPrimitive.content)
        assertEquals(
            "Send a direct message to another Letta agent.",
            entry.getValue("description").jsonPrimitive.content,
        )
        assertEquals("host_enumeration", entry.getValue("source").jsonPrimitive.content)
        assertTrue(entry.getValue("skill_path").jsonPrimitive.content.endsWith("agent-messaging"))
    }

    @Test
    fun fallsBackToTheDirectoryNameAndOpeningProseWhenThereIsNoFrontmatter() {
        // Real case: /opt/skills/comfyui opens straight on a heading.
        val root = root()
        skill(
            root,
            "comfyui",
            """
            # ComfyUI

            Use this skill to run ComfyUI locally.
            """.trimIndent(),
        )
        val entry = assertNotNull(HostSkillsEnumerator.enumerate(root)).single().jsonObject
        assertEquals("comfyui", entry.getValue("name").jsonPrimitive.content)
        assertEquals(
            "Use this skill to run ComfyUI locally.",
            entry.getValue("description").jsonPrimitive.content,
        )
    }

    @Test
    fun skipsDirectoriesWithoutASkillManifest() {
        val root = root()
        skill(root, "real", "# real\n\nprose\n")
        File(root, "not-a-skill").mkdirs()
        File(root, "scripts").mkdirs()
        val entries = assertNotNull(HostSkillsEnumerator.enumerate(root))
        assertEquals(listOf("real"), entries.map { it.jsonObject.getValue("name").jsonPrimitive.content })
    }

    @Test
    fun orderIsDeterministicSoTheCatalogIsStableAcrossRestarts() {
        val root = root()
        listOf("zeta", "alpha", "mid").forEach { skill(root, it, "# $it\n") }
        val first = assertNotNull(HostSkillsEnumerator.enumerate(root))
            .map { it.jsonObject.getValue("name").jsonPrimitive.content }
        val second = assertNotNull(HostSkillsEnumerator.enumerate(root))
            .map { it.jsonObject.getValue("name").jsonPrimitive.content }
        assertEquals(listOf("alpha", "mid", "zeta"), first)
        assertEquals(first, second)
    }

    @Test
    fun aMissingRootYieldsNullSoTheCatalogStaysUnhydratedRatherThanClaimingAnEmptyOne() {
        assertNull(HostSkillsEnumerator.enumerate(File("/nonexistent/7dm1q-skills")))
        // The distinction matters: null must NOT be turned into hydrated=true.
        val catalog = NativeSkillsCatalog()
        HostSkillsEnumerator.enumerate(File("/nonexistent/7dm1q-skills"))?.let(catalog::hydrateFromHost)
        assertTrue(!catalog.isHydrated())
    }

    @Test
    fun anEmptyButPresentRootIsAnAuthoritativeEmptyCatalog() {
        val entries = assertNotNull(HostSkillsEnumerator.enumerate(root()))
        assertEquals(0, entries.size)
    }

    @Test
    fun hydratesTheNativeCatalogSoSkillListStopsReportingCapabilityUnavailable() {
        val root = root()
        skill(root, "one", "---\nname: one\ndescription: first\n---\n")
        skill(root, "two", "# two\n\nsecond\n")
        val catalog = NativeSkillsCatalog()
        // Pre-hydration is exactly the live symptom this closes.
        assertTrue(!catalog.isHydrated(), "unhydrated is the pre-7dm1q state")

        catalog.hydrateFromHost(assertNotNull(HostSkillsEnumerator.enumerate(root)))

        assertTrue(catalog.isHydrated())
        assertEquals(SkillCatalogOrigin.HostEnumeration, catalog.origin())
        assertEquals(2, catalog.snapshot().size)
    }

    @Test
    fun reEnumeratingAfterARestartPreservesTheCatalogBecauseTheRootIsOnDisk() {
        val root = root()
        skill(root, "persisted", "---\nname: persisted\ndescription: survives\n---\n")

        val beforeRestart = NativeSkillsCatalog().apply {
            hydrateFromHost(assertNotNull(HostSkillsEnumerator.enumerate(root)))
        }
        // A "restart" is a brand-new catalog object with no in-memory carry-over.
        val afterRestart = NativeSkillsCatalog().apply {
            hydrateFromHost(assertNotNull(HostSkillsEnumerator.enumerate(root)))
        }
        assertTrue(afterRestart.isHydrated(), "cold start must re-discover without any wire enumeration")
        assertEquals(beforeRestart.snapshot(), afterRestart.snapshot())
    }

    @Test
    fun resolveSkillsDirPrefersTheExplicitOptionThenTheEnvVarThenTheDefault() {
        assertEquals(
            "/explicit",
            HostSkillsEnumerator.resolveSkillsDir("/explicit") { "/from-env" },
        )
        assertEquals(
            "/from-env",
            HostSkillsEnumerator.resolveSkillsDir(null) { key ->
                if (key == HostSkillsEnumerator.SKILLS_DIR_ENV) "/from-env" else null
            },
        )
        assertTrue(
            HostSkillsEnumerator.resolveSkillsDir(null) { null }.endsWith("/.letta/skills"),
            "the documented default is letta-code's own ~/.letta/skills",
        )
    }
}
