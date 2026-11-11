package com.letta.mobile.plugin.keystore

import kotlinx.serialization.serializerOrNull
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SecretValue cannot leak by accident: every rule of its KDoc and contract V1 to V6 has a test here. */
class SecretValueTest {
    private val sentinel = "LKS-SENTINEL-secret-value-test"

    private fun value(text: String = sentinel) = SecretValue.ofBytes(text.encodeToByteArray())

    @Test
    fun `toString, string templates and collections print the fixed redaction`() {
        val secret = value()
        assertEquals(SecretValue.REDACTED, secret.toString())
        assertEquals("key=${SecretValue.REDACTED}", "key=$secret")
        assertFalse(sentinel in listOf(secret).toString())
        assertFalse(sentinel in mapOf("k" to secret).toString())
        assertFalse(sentinel in KeystoreResult.Success(secret).toString())
    }

    @Test
    fun `the redaction does not reveal the length`() {
        assertEquals(value("a").toString(), value("a much longer secret value").toString())
    }

    @Test
    fun `it is neither java Serializable nor kotlinx serializable nor a data class`() {
        assertFalse(java.io.Serializable::class.java.isAssignableFrom(SecretValue::class.java))
        assertNull(serializerOrNull(typeOf<SecretValue>()))
        assertTrue(SecretValue::class.java.declaredMethods.none { it.name == "component1" || it.name == "copy" })
    }

    @Test
    fun `equality is identity, content comparison is explicit`() {
        val first = value()
        val second = value()
        assertNotEquals(first, second)
        assertEquals(first, first)
        assertTrue(first.contentEqualsConstantTime(second))
        assertFalse(first.contentEqualsConstantTime(value("$sentinel-other")))
        assertFalse(first.contentEqualsConstantTime(value(sentinel.dropLast(1))))
    }

    @Test
    fun `reveal lends a copy that is zeroed after the block, even when it throws`() {
        val secret = value()
        var lent: ByteArray? = null
        val text = secret.revealSecretBytes { bytes ->
            lent = bytes
            bytes.decodeToString()
        }
        assertEquals(sentinel, text)
        assertTrue(lent!!.all { it == 0.toByte() })

        var lentOnThrow: ByteArray? = null
        assertFailsWith<IllegalArgumentException> {
            secret.revealSecretBytes { bytes ->
                lentOnThrow = bytes
                throw IllegalArgumentException("boom")
            }
        }
        assertTrue(lentOnThrow!!.all { it == 0.toByte() })
        assertEquals(sentinel, secret.revealSecretBytes { it.decodeToString() })
    }

    @Test
    fun `mutating the lent copy or the factory input does not change the value`() {
        val input = sentinel.encodeToByteArray()
        val secret = SecretValue.ofBytes(input)
        input.fill(0)
        secret.revealSecretBytes { it.fill(1) }
        assertEquals(sentinel, secret.revealSecretBytes { it.decodeToString() })
    }

    @Test
    fun `wipe zeroes the value and later reads fail without printing it`() {
        val secret = value()
        secret.wipe()
        assertTrue(secret.isWiped)
        val error = assertFailsWith<IllegalStateException> { secret.revealSecretBytes { it.copyOf() } }
        assertFalse(sentinel in error.message.orEmpty())
        assertFailsWith<IllegalStateException> { secret.contentEqualsConstantTime(value()) }
        secret.wipe()
        assertEquals(SecretValue.REDACTED, secret.toString())
    }

    @Test
    fun `value problems are reported without revealing the value`() {
        assertEquals(ValueProblem.EMPTY, SecretValue.ofBytes(ByteArray(0)).valueProblem)
        assertEquals(ValueProblem.TOO_LARGE, SecretValue.ofBytes(ByteArray(KeystoreSpi.MAX_VALUE_BYTES + 1)).valueProblem)
        assertNull(SecretValue.ofBytes(ByteArray(KeystoreSpi.MAX_VALUE_BYTES)).valueProblem)
        assertNull(value().valueProblem)
    }

    @Test
    fun `a revealed array is equal in content`() {
        assertContentEquals(sentinel.encodeToByteArray(), value().revealSecretBytes { it.copyOf() })
    }
}
