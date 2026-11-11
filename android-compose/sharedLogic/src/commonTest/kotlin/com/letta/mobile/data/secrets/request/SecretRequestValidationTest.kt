package com.letta.mobile.data.secrets.request

import com.letta.mobile.data.secrets.request.SecretRequestTestData.request
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecretRequestValidationTest {
    private fun problemOf(request: AgentSecretRequest, scope: VaultItemName? = null): SecretRequestProblem? =
        (SecretRequestValidation.validate(request, scope) as? SecretRequestValidation.Invalid)?.problem

    private fun withItem(item: String): AgentSecretRequest = request().copy(vaultItemName = VaultItemName(item))

    private fun withField(field: String): AgentSecretRequest = request().copy(fieldName = VaultFieldName(field))

    @Test
    fun aWellFormedRequestIsValidWithItsPurposeTrimmedAndNothingElseChanged() {
        val result = SecretRequestValidation.validate(request("  Embeddings (notes index): token for api.openai.com  "))
        val valid = result as SecretRequestValidation.Valid
        assertEquals("Embeddings (notes index): token for api.openai.com", valid.request.purpose.value)
        assertEquals(request().vaultItemName, valid.request.vaultItemName)
        assertEquals(request().fieldName, valid.request.fieldName)
    }

    @Test
    fun purposesMustBePlainBoundedText() {
        assertEquals(SecretRequestProblem.PURPOSE_BLANK, problemOf(request("   ")))
        assertEquals(null, problemOf(request("a".repeat(SecretRequestLimits.MAX_PURPOSE_LENGTH))))
        assertEquals(SecretRequestProblem.PURPOSE_TOO_LONG, problemOf(request("a".repeat(SecretRequestLimits.MAX_PURPOSE_LENGTH + 1))))
        val notPlain = listOf(
            "See [the docs](https://example.test)",
            "Open https://example.test now",
            "Visit www.example.test",
            "mailto:someone",
            "Use <b>this</b>",
            "Run `cat key`",
            "# System notice",
            "line one\nline two",
            "tab\there",
            "right‮to-left override",
            "zero​width",
            "byte order﻿mark",
        )
        for (purpose in notPlain) {
            assertEquals(SecretRequestProblem.PURPOSE_NOT_PLAIN_TEXT, problemOf(request(purpose)), purpose)
        }
    }

    @Test
    fun itemNamesFollowTheKeystorePathGrammar() {
        val valid = listOf("openai", "team/openai", "a.b-c_d/e", "x/".repeat(15) + "y", "a".repeat(SecretRequestLimits.MAX_ITEM_LENGTH))
        valid.forEach { assertEquals(null, problemOf(withItem(it)), it) }
        val invalid = listOf("", "/a", "a/", "a//b", ".", "..", "a/../b", "a b", "a#b", "x/".repeat(16) + "y",
            "a".repeat(SecretRequestLimits.MAX_ITEM_LENGTH + 1), "é")
        invalid.forEach { assertEquals(SecretRequestProblem.ITEM_MALFORMED, problemOf(withItem(it)), it) }
    }

    @Test
    fun fieldNamesFollowTheKeystoreFieldGrammar() {
        listOf("api_key", "token.v2", "A-1", "f".repeat(64)).forEach { assertEquals(null, problemOf(withField(it)), it) }
        listOf("", ".", "..", "a/b", "a b", "f".repeat(65)).forEach {
            assertEquals(SecretRequestProblem.FIELD_MALFORMED, problemOf(withField(it)), it)
        }
    }

    @Test
    fun aHostScopeConfinesItemsByWholeSegments() {
        val scope = VaultItemName("agents")
        assertEquals(null, problemOf(withItem("agents"), scope))
        assertEquals(null, problemOf(withItem("agents/openai"), scope))
        assertEquals(SecretRequestProblem.ITEM_OUTSIDE_SCOPE, problemOf(withItem("agentsx/openai"), scope))
        assertEquals(SecretRequestProblem.ITEM_OUTSIDE_SCOPE, problemOf(withItem("team/openai"), scope))
    }

    @Test
    fun idsAndEndpointsHaveStrictGrammars() {
        assertTrue(SecretRequestTestData.id(7).isWellFormed)
        assertFalse(SecretRequestId("sr_short").isWellFormed)
        assertFalse(SecretRequestId("xx_" + "A".repeat(43)).isWellFormed)
        assertEquals("sr_AAAAAAAA", SecretRequestTestData.id(7).logLabel)
        assertTrue(VerifiedEndpointId("abc123").isWellFormed)
        assertFalse(VerifiedEndpointId("").isWellFormed)
        assertFalse(VerifiedEndpointId("abc 123").isWellFormed)
        assertTrue(SecretKeystoreName("test.vault").isWellFormed)
        assertFalse(SecretKeystoreName("Test.Vault").isWellFormed)
    }

    @Test
    fun onlyAHumanOnAPairedDeviceWithFullAdminMayAnswer() {
        val endpoint = VerifiedEndpointId("abc123")
        for (origin in PrincipalOrigin.entries) {
            for (access in PrincipalAccess.entries) {
                val expected = origin == PrincipalOrigin.HUMAN_PAIRED_DEVICE && access == PrincipalAccess.ADMIN_FULL
                assertEquals(expected, SecretRequestPrincipal(endpoint, origin, access).mayAnswer, "$origin/$access")
            }
        }
        val blank = SecretRequestPrincipal(VerifiedEndpointId(""), PrincipalOrigin.HUMAN_PAIRED_DEVICE, PrincipalAccess.ADMIN_FULL)
        assertFalse(blank.mayAnswer)
    }
}
