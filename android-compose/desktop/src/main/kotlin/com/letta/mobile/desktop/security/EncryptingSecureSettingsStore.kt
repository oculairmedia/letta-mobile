package com.letta.mobile.desktop.security

import com.letta.mobile.data.storage.SecureSettingsStore
import java.util.Base64

/**
 * [SecureSettingsStore] decorator that encrypts sensitive keys at rest via a
 * [DesktopSecretVault] (d6e8g.4). Plaintext values found under a sensitive key
 * (the pre-vault settings format) migrate transparently: they are re-written
 * encrypted on first read. Non-sensitive keys pass through untouched, so the
 * settings file stays diffable for everything that is not a secret.
 */
class EncryptingSecureSettingsStore(
    private val delegate: SecureSettingsStore,
    private val vault: DesktopSecretVault,
    private val sensitiveKeys: Set<String> = DEFAULT_SENSITIVE_KEYS,
) : SecureSettingsStore {
    override fun getString(key: String, defaultValue: String?): String? {
        val stored = delegate.getString(key, null) ?: return defaultValue
        if (key !in sensitiveKeys) return stored
        return if (stored.startsWith(PREFIX)) {
            decode(stored)
        } else {
            // Legacy plaintext secret: migrate to encrypted form in place.
            putString(key, stored)
            stored
        }
    }

    override fun putString(key: String, value: String) {
        if (key !in sensitiveKeys) {
            delegate.putString(key, value)
            return
        }
        val blob = vault.protect(value.encodeToByteArray())
        delegate.putString(key, PREFIX + Base64.getEncoder().encodeToString(blob))
    }

    override fun remove(key: String) = delegate.remove(key)

    override fun clear() = delegate.clear()

    private fun decode(stored: String): String {
        val blob = Base64.getDecoder().decode(stored.removePrefix(PREFIX))
        return vault.unprotect(blob).decodeToString()
    }

    companion object {
        /** Marker for vault-encrypted values; never a valid token prefix. */
        const val PREFIX = "enc1:"

        val DEFAULT_SENSITIVE_KEYS = setOf("letta.config.accessToken")
    }
}
