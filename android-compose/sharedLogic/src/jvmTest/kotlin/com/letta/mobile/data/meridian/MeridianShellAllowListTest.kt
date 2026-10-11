package com.letta.mobile.data.meridian

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * letta-mobile-jna0o.7: the client's meridian approval allow-list against the shared vectors
 * (scripts/deploy/meridian-allowlist-vectors.json), which the letta-code permission hook is held to
 * as well, so the host and the app approve exactly the same commands.
 */
class MeridianShellAllowListTest {
    private val vectors = Json.parseToJsonElement(File(VECTORS).readText()).jsonObject

    private fun list(name: String) = (vectors[name] as JsonArray).map { it.jsonPrimitive.content }

    @Test
    fun everyAllowVectorIsAllowed() {
        val refused = list("allow").filterNot(MeridianShellAllowList::allowsCommand)
        assertTrue(refused.isEmpty(), "refused but should allow: $refused")
    }

    @Test
    fun everyRefuseVectorIsRefused() {
        val allowed = list("refuse").filter(MeridianShellAllowList::allowsCommand)
        assertTrue(allowed.isEmpty(), "allowed but should refuse: $allowed")
    }

    @Test
    fun onlyShellToolsWithACommandQualify() {
        val args = buildJsonObject { put("command", "meridian canvas list") }.toString()

        assertTrue(MeridianShellAllowList.allows("Bash", args))
        assertTrue(MeridianShellAllowList.allows("shell_command", Json.encodeToString(String.serializer(), args)))
        assertFalse(MeridianShellAllowList.allows("Write", args))
        assertFalse(MeridianShellAllowList.allows("Bash", null))
        assertFalse(MeridianShellAllowList.allows("Bash", "not json"))
    }

    private companion object {
        /** From the sharedLogic module directory, where Gradle runs its tests. */
        const val VECTORS = "../../scripts/deploy/meridian-allowlist-vectors.json"
    }
}

