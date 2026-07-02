package com.letta.mobile.data.controller.node.iroh

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class IrohSecretKeyStoreTest {
    @Test
    fun fileBackedStoreReloadsStableIdentityBytes() = runTest {
        val dir = Files.createTempDirectory("iroh-key-store-stable").toFile()
        val path = java.io.File(dir, "identity.key").absolutePath
        val first = FileIrohSecretKeyStore(path).loadOrCreate()
        val second = FileIrohSecretKeyStore(path).loadOrCreate()

        assertEquals(IrohSecretKeyStores.KEY_BYTES, first.size)
        assertContentEquals(first, second)
    }

    @Test
    fun twoFreshStoresProduceDifferentIdentityBytes() = runTest {
        val dir = Files.createTempDirectory("iroh-key-store-different").toFile()
        val first = FileIrohSecretKeyStore(java.io.File(dir, "one.key").absolutePath).loadOrCreate()
        val second = FileIrohSecretKeyStore(java.io.File(dir, "two.key").absolutePath).loadOrCreate()

        assertFalse(first.contentEquals(second), "fresh stores must not share deterministic identity bytes")
    }

    @Test
    fun ephemeralStoreDoesNotUseTheOldDeterministicDevKey() = runTest {
        val oldDeterministicKey = ByteArray(32) { i -> (i + 1).toByte() }
        val generated = EphemeralIrohSecretKeyStore().loadOrCreate()

        assertEquals(IrohSecretKeyStores.KEY_BYTES, generated.size)
        assertFalse(generated.contentEquals(oldDeterministicKey), "ephemeral key must not be the old hardcoded dev key")
    }
}
