package com.letta.mobile.desktop.security

import com.letta.mobile.data.storage.SecureSettingsStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DesktopSecureStorageTest {
    private class InMemoryStore : SecureSettingsStore {
        val values = mutableMapOf<String, String>()

        override fun getString(key: String, defaultValue: String?): String? = values[key] ?: defaultValue

        override fun putString(key: String, value: String) {
            values[key] = value
        }

        override fun remove(key: String) {
            values.remove(key)
        }

        override fun clear() = values.clear()
    }

    private fun tempVault(): KeyFileAesGcmSecretVault {
        val dir = Files.createTempDirectory("vault-test")
        return KeyFileAesGcmSecretVault(dir.resolve(".vault.key"))
    }

    @Test
    fun vaultRoundTripsAndProducesNonDeterministicCiphertext() {
        val vault = tempVault()
        val secret = "letta-token-0123456789abcdef".encodeToByteArray()
        val a = vault.protect(secret)
        val b = vault.protect(secret)
        assertContentEquals(secret, vault.unprotect(a))
        assertContentEquals(secret, vault.unprotect(b))
        assertFalse(a.contentEquals(b), "IVs must differ per encryption")
        assertFalse(a.decodeToString().contains("letta-token"))
    }

    @Test
    fun tamperedVaultBlobsFailAuthenticationInsteadOfDecodingGarbage() {
        val vault = tempVault()
        val blob = vault.protect("secret-material".encodeToByteArray())
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 1).toByte()
        assertFailsWith<Exception> { vault.unprotect(blob) }
    }

    @Test
    fun sensitiveKeysAreEncryptedAtRestAndReadBack() {
        val delegate = InMemoryStore()
        val store = EncryptingSecureSettingsStore(delegate, tempVault())
        val token = "super-secret-access-token-value"

        store.putString("letta.config.accessToken", token)

        val atRest = delegate.values.getValue("letta.config.accessToken")
        assertTrue(atRest.startsWith(EncryptingSecureSettingsStore.PREFIX))
        assertFalse(atRest.contains(token), "token must not be plaintext at rest")
        assertEquals(token, store.getString("letta.config.accessToken", null))
    }

    @Test
    fun legacyPlaintextTokenMigratesToEncryptedOnFirstRead() {
        val delegate = InMemoryStore()
        delegate.values["letta.config.accessToken"] = "legacy-plaintext-token"
        val store = EncryptingSecureSettingsStore(delegate, tempVault())

        assertEquals("legacy-plaintext-token", store.getString("letta.config.accessToken", null))

        val migrated = delegate.values.getValue("letta.config.accessToken")
        assertTrue(migrated.startsWith(EncryptingSecureSettingsStore.PREFIX))
        assertFalse(migrated.contains("legacy-plaintext-token"))
        assertEquals("legacy-plaintext-token", store.getString("letta.config.accessToken", null))
    }

    @Test
    fun nonSensitiveKeysPassThroughUntouched() {
        val delegate = InMemoryStore()
        val store = EncryptingSecureSettingsStore(delegate, tempVault())
        store.putString("letta.config.serverUrl", "iroh://abc@10.0.0.1:4501")
        assertEquals("iroh://abc@10.0.0.1:4501", delegate.values.getValue("letta.config.serverUrl"))
    }

    @Test
    fun identityIsStableAcrossLoadsAndStoresNoPlaintextKey() {
        val dir = Files.createTempDirectory("identity-test")
        val vault = tempVault()
        val store = DesktopIrohIdentityStore(dir, vault)

        val first = store.loadOrCreate()
        val second = store.loadOrCreate()
        assertContentEquals(first, second, "NodeId key must be stable across loads")
        assertEquals(32, first.size)

        val onDisk = Files.readAllBytes(dir.resolve(DesktopIrohIdentityStore.FILE_NAME))
        assertFalse(onDisk.contentEquals(first), "identity key must not be plaintext on disk")

        val reloaded = DesktopIrohIdentityStore(dir, vault).loadOrCreate()
        assertContentEquals(first, reloaded, "identity must survive process restart")
    }

    @Test
    fun resetMintsANewIdentity() {
        val dir = Files.createTempDirectory("identity-reset-test")
        val store = DesktopIrohIdentityStore(dir, tempVault())
        val before = store.loadOrCreate()
        store.reset()
        val after = store.loadOrCreate()
        assertNotEquals(before.toList(), after.toList(), "reset must mint a fresh identity")
    }
}
