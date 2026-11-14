package com.letta.mobile.data.controller.extras

import com.letta.mobile.data.controller.extras.identity.ToolCallScope
import com.letta.mobile.data.controller.extras.identity.CallerIdentity
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A verified tool caller is what lets a security-sensitive host tool (`secret_request`) act for an
 * agent, so it may be made in exactly two places: the App Server dispatcher and the Meridian
 * live-call binder. These tests fail when another production file starts making one (through
 * [verifiedToolCaller] or the internal constructor), when the class grows a `copy`, or when a
 * changed scope keeps its verification.
 */
class ExternalToolCallerVerificationGuardTest {
    @Test
    fun onlyTheDispatcherAndTheMeridianBinderMakeAVerifiedCaller() {
        val makers = filesMatching(VERIFIED_FACTORY)
        assertEquals(sortedSetOf(DEFINITION, "ExternalToolDispatcher.kt", "MeridianCallerBinder.kt"), makers, "$DEFINITION defines it")
    }

    @Test
    fun noProductionFileButTheDefinitionCallsTheConstructorOrNamesTheVerifiedIdentity() {
        assertEquals(sortedSetOf(DEFINITION), filesMatching(CONSTRUCTOR))
        assertEquals(sortedSetOf(DEFINITION), filesMatching(VERIFIED_IDENTITY))
    }

    @Test
    fun theScanSeesTheFilesItGuardsAndItsPatternsMatch() {
        val names = productionSources().map { it.first }.toSet()
        val guarded = setOf(DEFINITION, "ExternalToolDispatcher.kt", "MeridianCallerBinder.kt", "SecretRequestTool.kt")
        assertTrue(guarded.all(names::contains), "the production scan found the guarded files")
        assertTrue(VERIFIED_FACTORY.containsMatchIn("x = verifiedToolCaller (agent)"))
        assertTrue(CONSTRUCTOR.containsMatchIn("ExternalToolCaller(CallerIdentity.CLAIMED, a, b, c)"))
        assertTrue(CONSTRUCTOR.containsMatchIn("list.map(::ExternalToolCaller)"))
        assertFalse(CONSTRUCTOR.containsMatchIn("class ExternalToolCaller internal constructor("))
        assertTrue(VERIFIED_IDENTITY.containsMatchIn("import com.letta.mobile.data.controller.extras.identity.CallerIdentity.*"))
    }

    @Test
    fun theCallerHasNoCopy() {
        assertFalse(ExternalToolCaller::class.java.methods.any { it.name.startsWith("copy") }, "no copy")
    }

    @Test
    fun changingTheScopeOfAVerifiedCallerMakesItClaimed() {
        val scope = ToolCallScope("agent-1", "conv-1", "call-1")
        val verified = verifiedToolCaller(scope)
        assertTrue(verified.isVerified)
        assertTrue(verified.withArguments(scope.copy(toolCallId = "call-2")).isVerified, "the call id is not identity")
        assertTrue(verified.withArguments(scope).isVerified)
        assertEquals(CallerIdentity.CLAIMED, verified.withArguments(scope.copy(agentId = "agent-2")).identity)
        assertEquals(CallerIdentity.CLAIMED, verified.withArguments(scope.copy(conversationId = "conv-2")).identity)
        assertEquals(CallerIdentity.CLAIMED, verified.withArguments(scope.copy(agentId = null)).identity)
        val claimed = claimedToolCaller(scope)
        assertFalse(claimed.isVerified)
        assertFalse(claimed.withArguments(scope).isVerified, "nothing turns a claimed caller verified")
        assertEquals("agent-1" to "conv-1", claimed.agentId to claimed.conversationId)
    }

    private fun filesMatching(pattern: Regex) =
        productionSources().filter { (_, code) -> pattern.containsMatchIn(code) }.map { it.first }.toSortedSet()

    /** Every Kotlin file of a production source set in the build, as (file name, code without comments). */
    private fun productionSources(): List<Pair<String, String>> {
        val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        return root.walkTopDown()
            .onEnter { it.name !in SKIPPED_DIRECTORIES }
            .filter { it.isFile && it.extension == "kt" && isProduction(it.relativeTo(root).invariantSeparatorsPath) }
            .map { it.name to withoutComments(it.readText()) }
            .toList()
    }

    private fun isProduction(path: String): Boolean {
        val sourceSet = path.substringAfter("/src/", missingDelimiterValue = "").substringBefore('/')
        return sourceSet.isNotEmpty() && !sourceSet.contains("test", ignoreCase = true)
    }

    private fun withoutComments(code: String): String = code.lines()
        .map { it.trim() }
        .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
        .joinToString("\n")

    private companion object {
        const val DEFINITION = "ExternalTool.kt"
        val SKIPPED_DIRECTORIES = setOf("build", ".gradle", "node_modules", ".kotlin")
        val VERIFIED_FACTORY = Regex("""\bverifiedToolCaller\s*\(""")
        val CONSTRUCTOR = Regex("""(?<!class )\bExternalToolCaller\s*\(|::ExternalToolCaller\b""")
        val VERIFIED_IDENTITY = Regex("""CallerIdentity\s*\.\s*VERIFIED\b|CallerIdentity\.\*""")
    }
}
