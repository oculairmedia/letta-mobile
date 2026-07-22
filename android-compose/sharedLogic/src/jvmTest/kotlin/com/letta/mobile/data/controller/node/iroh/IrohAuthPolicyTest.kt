package com.letta.mobile.data.controller.node.iroh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IrohAuthPolicyTest {
    private val strongToken = "0123456789abcdef0123456789abcdef"

    @Test
    fun bearerTokenRejectsBlankAndShortCredentials() {
        assertFailsWith<IllegalArgumentException> { IrohAuthPolicy.BearerToken("") }
        assertFailsWith<IllegalArgumentException> { IrohAuthPolicy.BearerToken("   ") }
        assertFailsWith<IllegalArgumentException> { IrohAuthPolicy.BearerToken("short") }
        assertEquals(strongToken, IrohAuthPolicy.BearerToken(strongToken).requiredBearerToken)
    }

    @Test
    fun bearerTokenNeverLeaksTheCredentialThroughToString() {
        val policy = IrohAuthPolicy.BearerToken(strongToken, setOf("peer-1"))
        assertFalse(policy.toString().contains(strongToken))
        assertTrue(policy.toString().contains("<redacted>"))
    }

    @Test
    fun peerAllowlistRequiresAtLeastOnePeer() {
        assertFailsWith<IllegalArgumentException> { IrohAuthPolicy.PeerAllowlist(emptySet()) }
        val policy = IrohAuthPolicy.PeerAllowlist(setOf("a".repeat(64)))
        assertEquals(null, policy.requiredBearerToken)
    }

    @Test
    fun resolveRefusesAnonymousWithoutTheExplicitFlag() {
        val resolution = IrohAuthPolicy.resolve(
            authToken = null,
            allowedPeerIds = emptySet(),
            allowInsecureAnonymous = false,
        )
        assertIs<IrohAuthPolicyResolution.Refused>(resolution)
        assertTrue(resolution.error.contains("--allow-insecure-anonymous-iroh"))
    }

    @Test
    fun resolveRefusesBlankTokenWithoutTheExplicitFlag() {
        assertIs<IrohAuthPolicyResolution.Refused>(
            IrohAuthPolicy.resolve(authToken = "  ", allowedPeerIds = emptySet(), allowInsecureAnonymous = false),
        )
    }

    @Test
    fun resolveRefusesWeakTokensInsteadOfSilentlyAcceptingThem() {
        val resolution = IrohAuthPolicy.resolve(
            authToken = "weak",
            allowedPeerIds = emptySet(),
            allowInsecureAnonymous = false,
        )
        assertIs<IrohAuthPolicyResolution.Refused>(resolution)
        assertTrue(resolution.error.contains("${IrohAuthPolicy.MIN_TOKEN_LENGTH}"))
    }

    @Test
    fun resolvePrefersSecureConfigurationOverTheInsecureFlag() {
        val resolution = IrohAuthPolicy.resolve(
            authToken = strongToken,
            allowedPeerIds = setOf("peer-1"),
            allowInsecureAnonymous = true,
        )
        val secure = assertIs<IrohAuthPolicyResolution.Secure>(resolution)
        val policy = assertIs<IrohAuthPolicy.BearerToken>(secure.policy)
        assertEquals(strongToken, policy.requiredBearerToken)
        assertEquals(setOf("peer-1"), policy.allowedPeerIds)
    }

    @Test
    fun resolveAllowlistOnlyProducesPeerAllowlistPolicy() {
        val resolution = IrohAuthPolicy.resolve(
            authToken = null,
            allowedPeerIds = setOf("peer-1", "peer-2"),
            allowInsecureAnonymous = false,
        )
        val secure = assertIs<IrohAuthPolicyResolution.Secure>(resolution)
        assertIs<IrohAuthPolicy.PeerAllowlist>(secure.policy)
    }

    @Test
    fun resolveInsecureFlagIsAcceptedOnlyWithAWarning() {
        val resolution = IrohAuthPolicy.resolve(
            authToken = null,
            allowedPeerIds = emptySet(),
            allowInsecureAnonymous = true,
        )
        val accepted = assertIs<IrohAuthPolicyResolution.InsecureAccepted>(resolution)
        assertEquals(IrohAuthPolicy.InsecureAnonymousForTestOnly, accepted.policy)
        assertTrue(accepted.warning.contains("UNAUTHENTICATED"))
    }
}
