package com.letta.mobile.data.controller.extras

import com.letta.mobile.data.controller.extras.identity.CallerIdentity
import java.io.File
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A verified tool caller is what lets a security-sensitive host tool (`secret_request`) act for an
 * agent, so it may be made in exactly two places: the App Server dispatcher and the Meridian
 * live-call binder. These tests fail when another production file starts making one, when the
 * class grows a public constructor or `copy`, or when a changed scope keeps its verification.
 */
class ExternalToolCallerVerificationGuardTest {
    @Test
    fun onlyTheDispatcherAndTheMeridianBinderMakeAVerifiedCaller() {
        val makers = productionSources().filter { (_, code) -> VERIFIED_FACTORY.containsMatchIn(code) }.map { it.first }
        assertEquals(sortedSetOf("ExternalToolDispatcher.kt", "MeridianCallerBinder.kt"), makers.toSortedSet())
    }

    @Test
    fun noProductionFileButTheCallerItselfNamesTheVerifiedIdentity() {
        val namers = productionSources().filter { (_, code) -> VERIFIED_IDENTITY.containsMatchIn(code) }.map { it.first }
        assertEquals(sortedSetOf("ExternalTool.kt"), namers.toSortedSet())
    }

    @Test
    fun theScanSeesTheFilesItGuards() {
        val names = productionSources().map { it.first }.toSet()
        assertTrue(
            setOf("ExternalTool.kt", "ExternalToolDispatcher.kt", "MeridianCallerBinder.kt", "SecretRequestTool.kt").all(names::contains),
            "the production scan found the guarded files",
        )
        assertTrue(VERIFIED_FACTORY.containsMatchIn("ExternalToolCaller.verified(agent)"))
        assertTrue(VERIFIED_FACTORY.containsMatchIn("ExternalToolCaller.Companion.verified (agent)"))
        assertTrue(VERIFIED_IDENTITY.containsMatchIn("import com.letta.mobile.data.controller.extras.identity.CallerIdentity.*"))
    }

    @Test
    fun theCallerHasNoPublicConstructorAndNoCopy() {
        val type = ExternalToolCaller::class.java
        // The companion reaches the private constructor through a synthetic accessor, which Java cannot call.
        val callable = type.constructors.filterNot { it.isSynthetic }
        assertTrue(callable.isEmpty(), "no public constructor: $callable")
        assertFalse(type.methods.any { it.name.startsWith("copy") }, "no copy")
        val verified = ExternalToolCaller.Companion::class.java.declaredMethods.filter { it.name.startsWith("verified") }
        assertTrue(verified.isNotEmpty() && verified.none { Modifier.isPublic(it.modifiers) && !it.name.contains('$') })
    }

    @Test
    fun changingTheScopeOfAVerifiedCallerMakesItClaimed() {
        val verified = ExternalToolCaller.verified("agent-1", "conv-1", "call-1")
        assertTrue(verified.isVerified)
        assertEquals(CallerIdentity.VERIFIED, verified.withArguments(toolCallId = "call-2").identity, "the call id is not identity")
        assertEquals(verified, verified.withArguments())
        assertEquals(CallerIdentity.CLAIMED, verified.withArguments(agentId = "agent-2").identity)
        assertEquals(CallerIdentity.CLAIMED, verified.withArguments(conversationId = "conv-2").identity)
        assertEquals(CallerIdentity.CLAIMED, verified.withArguments(agentId = null).identity)
        val claimed = ExternalToolCaller.claimed("agent-1", "conv-1")
        assertFalse(claimed.isVerified)
        assertFalse(claimed.withArguments(agentId = "agent-1").isVerified, "nothing turns a claimed caller verified")
        assertEquals(ExternalToolCaller.claimed("agent-1", "conv-1"), claimed)
        assertTrue(verified != ExternalToolCaller.claimed("agent-1", "conv-1", "call-1"), "identity is part of equality")
    }

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
        val SKIPPED_DIRECTORIES = setOf("build", ".gradle", "node_modules", ".kotlin")
        val VERIFIED_FACTORY = Regex(
            """ExternalToolCaller\s*\.\s*(Companion\s*\.\s*)?verified\s*\(|ExternalToolCaller\.Companion\.verified""",
        )
        val VERIFIED_IDENTITY = Regex("""CallerIdentity\s*\.\s*VERIFIED\b|CallerIdentity\.\*""")
    }
}
