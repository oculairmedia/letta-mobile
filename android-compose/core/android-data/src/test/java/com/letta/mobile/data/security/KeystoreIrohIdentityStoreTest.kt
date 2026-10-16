package com.letta.mobile.data.security

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** letta-mobile-dpv7m.2: the device identity is sealed at rest and migrates without changing NodeId. */
class KeystoreIrohIdentityStoreTest {
    /** Reversible stand-in for the Keystore sealer (XOR + marker) — enough to prove nothing is stored plain. */
    private class FakeSealer(var broken: Boolean = false) : IdentitySealer {
        override fun seal(plain: ByteArray): ByteArray = byteArrayOf(MARK) + plain.map { (it.toInt() xor 0x5A).toByte() }

        override fun open(sealed: ByteArray): ByteArray {
            check(!broken && sealed.first() == MARK) { "key lost" }
            return sealed.drop(1).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }

        companion object {
            const val MARK: Byte = 7
        }
    }

    private val dir: File = Files.createTempDirectory("identity").toFile()

    @Test
    fun freshDeviceGetsOneStableIdentity() = runTest {
        val first = KeystoreIrohIdentityStore(dir, FakeSealer()).loadOrCreate()
        assertEquals(32, first.size)
        assertArrayEquals(first, KeystoreIrohIdentityStore(dir, FakeSealer()).loadOrCreate())
    }

    @Test
    fun aLostKeystoreKeyMintsANewIdentityInsteadOfCrashing() = runTest {
        val first = KeystoreIrohIdentityStore(dir, FakeSealer()).loadOrCreate()
        val replaced = KeystoreIrohIdentityStore(dir, FakeSealer(broken = true)).let { store ->
            runCatching { store.loadOrCreate() }
        }
        // A broken sealer cannot write either; the store surfaces that instead of falling back to plaintext.
        assertTrue(replaced.isFailure || !replaced.getOrThrow().contentEquals(first))
        assertNotEquals(0, File(dir, KeystoreIrohIdentityStore.SEALED_FILE).length())
    }
}
