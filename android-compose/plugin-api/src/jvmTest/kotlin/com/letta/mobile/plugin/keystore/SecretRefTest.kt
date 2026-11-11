package com.letta.mobile.plugin.keystore

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Contract R1 to R4, the scope rule and the error templates. */
class SecretRefTest {
    private val provider = KeystoreProviderId("example.memory")

    private fun ref(path: String, field: String? = null) = SecretRef(provider, SecretPath(path), field?.let(::SecretField))

    @Test
    fun `refs are equal exactly when every component is equal as a string`() {
        assertEquals(ref("team/api", "token"), ref("team/api", "token"))
        assertNotEquals(ref("team/api", "token"), ref("team/api"))
        assertNotEquals(ref("team/api"), ref("Team/api"))
        assertNotEquals(ref("team/api"), ref("team/api/"))
        assertNotEquals(ref("team/api"), SecretRef(KeystoreProviderId("other.vault"), SecretPath("team/api")))
    }

    @Test
    fun `the text form names provider, path and field`() {
        assertEquals("example.memory:team/api#token", ref("team/api", "token").toString())
        assertEquals("example.memory:team/api", ref("team/api").toString())
        assertEquals(ref("team/api"), ref("team/api", "token").item)
    }

    @Test
    fun `well-formed refs follow the grammar`() {
        assertTrue(ref("a").isWellFormed)
        assertTrue(ref("team/api.v2/open_ai-key", "token.1").isWellFormed)
        assertTrue(ref(List(KeystoreSpi.MAX_PATH_SEGMENTS) { "s" }.joinToString("/")).isWellFormed)
    }

    @Test
    fun `malformed refs are refused, never normalized`() {
        val malformed = listOf(
            ref(""), ref("/a"), ref("a/"), ref("a//b"), ref("a/../b"), ref("."), ref("a b"),
            ref("é"), ref("a", ""), ref("a", ".."), ref("a", "f/g"),
            ref("x".repeat(KeystoreSpi.MAX_PATH_LENGTH + 1)),
            ref(List(KeystoreSpi.MAX_PATH_SEGMENTS + 1) { "s" }.joinToString("/")),
            ref("a", "f".repeat(KeystoreSpi.MAX_FIELD_LENGTH + 1)),
            SecretRef(KeystoreProviderId("Upper.Case"), SecretPath("a")),
            SecretRef(KeystoreProviderId("a..b"), SecretPath("a")),
        )
        malformed.forEach { bad ->
            assertFalse(bad.isWellFormed, "$bad")
            assertEquals(RefProblem.MALFORMED, bad.problemFor(provider), "$bad")
        }
    }

    @Test
    fun `a ref naming another provider is a mismatch`() {
        val other = SecretRef(KeystoreProviderId("other.vault"), SecretPath("a"))
        assertEquals(RefProblem.PROVIDER_MISMATCH, other.problemFor(provider))
        assertNull(ref("a").problemFor(provider))
    }

    @Test
    fun `a scope covers whole segments only`() {
        val scope = SecretScope(provider, SecretPath("team/api"))
        assertTrue(scope.contains(ref("team/api")))
        assertTrue(scope.contains(ref("team/api/openai", "token")))
        assertFalse(scope.contains(ref("team/apis")))
        assertFalse(scope.contains(ref("team")))
        assertTrue(SecretScope(provider).contains(ref("anything/at/all")))
        assertFalse(SecretScope(KeystoreProviderId("other.vault")).contains(ref("a")))
        assertEquals("example.memory:team/api/", scope.toString())
        assertEquals(RefProblem.MALFORMED, SecretScope(provider, SecretPath("a//b")).problemFor(provider))
        assertNull(scope.problemFor(provider))
    }

    @Test
    fun `error messages are fixed templates of names and codes`() {
        assertEquals("No secret at example.memory:team/api", KeystoreError.NotFound(ref("team/api")).message)
        assertEquals("KeystoreError(locked: The keystore provider is locked)", KeystoreError.Locked.toString())
        assertEquals(
            "KeystoreError(unavailable: The keystore provider is unavailable)",
            KeystoreError.Unavailable(ProviderDiagnostic("connection refused")).toString(),
        )
        assertEquals(KeystoreErrorCode.UNSUPPORTED, KeystoreError.Unsupported(KeystoreOperation.PUT).code)
    }

    @Test
    fun `every error code spells its wire name as its lowercase name`() {
        KeystoreErrorCode.entries.forEach { code ->
            val wire = kotlinx.serialization.json.Json.encodeToString(KeystoreErrorCode.serializer(), code)
            assertEquals("\"${code.name.lowercase()}\"", wire)
        }
    }

    @Test
    fun `results expose their value or error`() {
        val missing = KeystoreError.NotFound(ref("a"))
        assertEquals(Unit, KeystoreResult.Done.valueOrNull())
        assertNull(KeystoreResult.Done.errorOrNull())
        assertEquals(missing, KeystoreResult.Failure(missing).errorOrNull())
        assertNull(KeystoreResult.Failure(missing).valueOrNull())
    }
}
