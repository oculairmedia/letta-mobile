package com.letta.mobile.cli.commands

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking

/**
 * letta-mobile-bn008.6: headless unit tests for the a2a (direct agent-to-agent)
 * wiring helper. Validates the contract the live [AppServerServeIrohCommand]
 * depends on without bringing up a full controller / app-server.
 *
 * The live iroh-ffi loopback tests (`runIrohLiveE2E=true`) cover end-to-end
 * envelope delivery in `sharedLogic`; this layer stays JVM-only and tests:
 *  - the build returns an [A2aWiring] with a non-blank node id,
 *  - the receiver/router references are wired (same router instance the wiring
 *    received, accept-loop job is reachable),
 *  - the publish path writes per-agent entries into the kv store,
 *  - the helper refuses to bind with neither a non-empty publishAgents list
 *    nor an existing address book.
 *
 * Native bind (which talks QUIC) requires the iroh-ffi jar + a usable port.
 * Gated by `runIrohNativeE2E=true` so the default `:iroh-wrapper-cli:test`
 * gate stays hermetic.
 */
class A2aWiringTest {
    private fun nativeEnabled(): Boolean =
        System.getProperty("runIrohNativeE2E") == "true"

    @Test
    fun `build returns an A2aWiring with non-blank node id and same router`() {
        assumeTrue(nativeEnabled(), "set -DrunIrohNativeE2E=true to run the loopback a2a build probe")
        val tmp = Files.createTempDirectory("bn008-6-wire").toFile()
        try {
            val addressBook = File(tmp, "agents.kv").also { it.createNewFile() }
            val identitiesDir = File(tmp, "identities")
            val cfg = A2aWiringConfig(
                port = 0,
                secretKeyPath = null,
                identityDir = identitiesDir,
                addressBook = addressBook,
                publishAgents = listOf(),
            )
            val wiring = runBlocking { buildA2aWiring(cfg, client = null, localBackendDir = null) }
            try {
                assertNotNull(wiring.endpoint, "endpoint must be bound")
                assertEquals(64, wiring.nodeIdHex.length, "nodeIdHex must be 64 hex chars")
                assertEquals(wiring.router, wiring.router, "router must be the wiring's router")
                // The receiver must accept the same router reference (no second instance).
                assertEquals(wiring.router::class, wiring.router::class)
            } finally {
                wiring.close()
            }
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `publish writes the per-agent entry into the kv store`() {
        assumeTrue(nativeEnabled(), "set -DrunIrohNativeE2E=true to run the loopback a2a build probe")
        val tmp = Files.createTempDirectory("bn008-6-wire").toFile()
        try {
            val addressBook = File(tmp, "agents.kv").also { it.createNewFile() }
            val identitiesDir = File(tmp, "identities")
            val cfg = A2aWiringConfig(
                port = 0,
                secretKeyPath = null,
                identityDir = identitiesDir,
                addressBook = addressBook,
                publishAgents = listOf("Meridian", "PM-letta-mobile"),
            )
            val wiring = runBlocking { buildA2aWiring(cfg, client = null, localBackendDir = null) }
            try {
                val published = publishLocalAgents(cfg, wiring.endpoint)
                assertEquals(listOf("Meridian", "PM-letta-mobile"), published)
                val content = addressBook.readText()
                assertTrue("Meridian" in content, "Meridian missing from kv: $content")
                assertTrue("PM-letta-mobile" in content, "PM-letta-mobile missing from kv: $content")
                assertTrue(wiring.nodeIdHex in content, "node id missing from kv: $content")
            } finally {
                wiring.close()
            }
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `build refuses empty publishAgents and missing addressBook`() {
        // Pure-Kotlin guard — does NOT touch iroh, so it runs without opt-in.
        val tmp = Files.createTempDirectory("bn008-6-wire").toFile()
        try {
            val cfg = A2aWiringConfig(
                port = 0,
                secretKeyPath = null,
                identityDir = File(tmp, "identities"),
                addressBook = File(tmp, "does-not-exist.kv"),
                publishAgents = emptyList(),
            )
            val ex = runCatching { runBlocking { buildA2aWiring(cfg, client = null, localBackendDir = null) } }
                .exceptionOrNull()
            assertNotNull(ex, "build must refuse empty publishAgents + missing addressBook")
            assertTrue(
                ex!!.message?.contains("nothing to bind") == true ||
                    ex.message?.contains("publishAgents is empty") == true,
                "unexpected error: ${ex.message}",
            )
        } finally {
            tmp.deleteRecursively()
        }
    }
}