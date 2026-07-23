package com.letta.mobile.desktop.security

import com.letta.mobile.data.controller.node.iroh.IrohSecretKeyStores
import java.nio.file.Path
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts desktop secrets at rest using OS facilities (d6e8g.4).
 *
 * Windows uses DPAPI (per-user CryptProtectData via the already-shipped
 * jna-platform binding) — the OS ties the ciphertext to the logged-in user.
 * Other platforms fall back to AES-256-GCM with a random key held in a
 * permission-restricted (0600) keyfile in the desktop state directory,
 * reusing the hardened atomic-write/permission logic of
 * [IrohSecretKeyStores]. Neither path ever logs plaintext or key material.
 */
interface DesktopSecretVault {
    fun protect(plaintext: ByteArray): ByteArray

    fun unprotect(ciphertext: ByteArray): ByteArray
}

object DesktopSecretVaults {
    fun forCurrentOs(stateDirectory: Path): DesktopSecretVault =
        if (System.getProperty("os.name").orEmpty().contains("win", ignoreCase = true)) {
            DpapiSecretVault()
        } else {
            KeyFileAesGcmSecretVault(stateDirectory.resolve(".desktop-vault.key"))
        }
}

/** Windows DPAPI vault: ciphertext is bound to the current OS user account. */
class DpapiSecretVault : DesktopSecretVault {
    override fun protect(plaintext: ByteArray): ByteArray =
        com.sun.jna.platform.win32.Crypt32Util.cryptProtectData(plaintext)

    override fun unprotect(ciphertext: ByteArray): ByteArray =
        com.sun.jna.platform.win32.Crypt32Util.cryptUnprotectData(ciphertext)
}

/**
 * POSIX fallback vault: AES-256-GCM with a keyfile restricted to `rw-------`.
 * The random IV prefixes each ciphertext; a fixed AAD binds blobs to this
 * vault format so foreign ciphertexts fail authentication instead of
 * decrypting to garbage.
 */
class KeyFileAesGcmSecretVault(
    private val keyPath: Path,
    private val random: SecureRandom = SecureRandom(),
) : DesktopSecretVault {
    private val key: SecretKeySpec by lazy {
        SecretKeySpec(IrohSecretKeyStores.loadOrCreateFile(keyPath.toString()), "AES")
    }

    override fun protect(plaintext: ByteArray): ByteArray {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(AAD)
        return iv + cipher.doFinal(plaintext)
    }

    override fun unprotect(ciphertext: ByteArray): ByteArray {
        require(ciphertext.size > IV_BYTES) { "Vault blob is too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(TAG_BITS, ciphertext.copyOfRange(0, IV_BYTES)),
        )
        cipher.updateAAD(AAD)
        return cipher.doFinal(ciphertext.copyOfRange(IV_BYTES, ciphertext.size))
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        val AAD = "letta-desktop-vault-v1".encodeToByteArray()
    }
}
