package com.letta.mobile.web.data

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The identity's hex form matches iroh-identity.js toHex/fromHex both ways. */
class IdentityHexTest {
    private val key = ByteArray(32) { (it * 37 - 128).toByte() }

    @Test
    fun encodesAsLowercaseHexLikeTheJsSide() {
        assertEquals("00ff7f80", IdentityHex.encode(byteArrayOf(0, -1, 127, -128)))
    }

    @Test
    fun aKeySurvivesTheRoundTrip() {
        assertContentEquals(key, IdentityHex.decode(IdentityHex.encode(key)))
    }

    @Test
    fun decodingAcceptsEitherCase() {
        assertContentEquals(byteArrayOf(-85, -51), IdentityHex.decode("ABcd"))
    }

    @Test
    fun anythingButWholeHexPairsIsRefused() {
        assertFailsWith<IllegalArgumentException> { IdentityHex.decode("abc") }
        assertFailsWith<IllegalArgumentException> { IdentityHex.decode("zz") }
    }
}
