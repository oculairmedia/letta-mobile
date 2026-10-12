package com.letta.mobile.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.letta.mobile.data.controller.node.iroh.IrohSecretKeyStore
import com.letta.mobile.data.controller.node.iroh.IrohSecretKeyStores
import com.letta.mobile.util.Telemetry
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Seals/opens the identity bytes; the Android implementation keeps its key in the Keystore. */
interface IdentitySealer {
    fun seal(plain: ByteArray): ByteArray

    fun open(sealed: ByteArray): ByteArray
}

/**
 * This device's persistent Iroh identity, encrypted at rest with a
 * non-exportable Android Keystore key (letta-mobile-dpv7m.2). The NodeId it
 * derives is what a host binds when this device pairs, so it must survive
 * restarts and must never be stored in plaintext.
 *
 * Migration: the identity used to be a plaintext 32-byte file
 * (`iroh-client-identity.key`, d6e8g.9). It is sealed into
 * `iroh-client-identity.enc` on first use and the plaintext copy is deleted —
 * the same bytes, so the NodeId (and every pairing bound to it) is unchanged.
 */
class KeystoreIrohIdentityStore(
    private val dir: File,
    private val sealer: IdentitySealer,
) : IrohSecretKeyStore {
    private val sealedFile = File(dir, SEALED_FILE)
    private val legacyFile = File(dir, LEGACY_FILE)

    override suspend fun loadOrCreate(): ByteArray = withContext(Dispatchers.IO) {
        synchronized(IdentityFileLock) { loadSealed() ?: migrateLegacy() ?: create() }
    }

    private fun loadSealed(): ByteArray? {
        if (!sealedFile.exists()) return null
        return runCatching { sealer.open(sealedFile.readBytes()).also(IrohSecretKeyStores::requireValidKey) }
            .onFailure {
                // The Keystore key is gone (e.g. device restore): the identity cannot be
                // recovered. A new one is minted; the user re-pairs this device.
                Telemetry.event("IrohIdentity", "sealed.unreadable", "class" to it::class.simpleName, level = Telemetry.Level.WARN)
            }
            .getOrNull()
    }

    private fun migrateLegacy(): ByteArray? {
        if (!legacyFile.exists()) return null
        val bytes = runCatching { legacyFile.readBytes().also(IrohSecretKeyStores::requireValidKey) }.getOrNull() ?: return null
        writeSealed(bytes)
        if (!legacyFile.delete()) legacyFile.writeBytes(ByteArray(bytes.size))
        Telemetry.event("IrohIdentity", "legacy.migrated")
        return bytes
    }

    private fun create(): ByteArray = IrohSecretKeyStores.generateKeyBytes().also {
        writeSealed(it)
        Telemetry.event("IrohIdentity", "generated")
    }

    private fun writeSealed(bytes: ByteArray) {
        dir.mkdirs()
        val tmp = File(dir, ".$SEALED_FILE.tmp")
        tmp.writeBytes(sealer.seal(bytes))
        IrohSecretKeyStores.restrictKeyFilePermissions(tmp)
        if (!tmp.renameTo(sealedFile)) {
            sealedFile.delete()
            check(tmp.renameTo(sealedFile)) { "could not persist the device identity" }
        }
    }

    companion object {
        const val SEALED_FILE = "iroh-client-identity.enc"
        const val LEGACY_FILE = "iroh-client-identity.key"

        /** The one identity store every Iroh dial on this device uses (session + pairing). */
        fun forApp(context: Context): KeystoreIrohIdentityStore =
            KeystoreIrohIdentityStore(context.applicationContext.filesDir, AndroidKeystoreSealer())
    }
}

/** Serializes identity file access across store instances in this process. */
private object IdentityFileLock

/** AES-256-GCM with a non-exportable AndroidKeyStore key; output is `iv || ciphertext`. */
class AndroidKeystoreSealer(private val alias: String = ALIAS) : IdentitySealer {
    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        require(sealed.size > IV_BYTES) { "sealed identity is truncated" }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed.copyOfRange(0, IV_BYTES)))
        }
        return cipher.doFinal(sealed.copyOfRange(IV_BYTES, sealed.size))
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_BITS)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val ALIAS = "letta_iroh_identity_v1"
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val KEY_BITS = 256
    }
}
