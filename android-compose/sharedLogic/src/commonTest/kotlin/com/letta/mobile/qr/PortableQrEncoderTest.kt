package com.letta.mobile.qr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

/** The pairing [com.letta.mobile.data.pairing.QrEncoder] port over the common generator. */
class PortableQrEncoderTest {

    @Test
    fun theModulesAreTheLevelMSymbolWithoutAQuietZone() {
        val link = "meridian://pair?qr=letta-qr-v1.x1"
        val modules = PortableQrEncoder.encode(link)
        val matrix = assertIs<QrEncodeResult.Encoded>(QrCodeGenerator.encode(link, QrErrorCorrection.M)).matrix
        assertEquals(matrix.size, modules.size)
        for (row in 0 until matrix.size) for (col in 0 until matrix.size) assertEquals(matrix.isDark(row, col), modules.isDark(row, col))
        assertEquals(true, modules.isDark(0, 0), "the finder starts at the edge: no quiet zone inside the grid")
    }

    @Test
    fun tooLongContentThrowsATypedErrorThatNeverEchoesTheContent() {
        val secret = "s3cr3t-" + "x".repeat(2400)
        val error = assertFailsWith<QrContentTooLongException> { PortableQrEncoder.encode(secret) }
        assertEquals(2334 * 8, error.capacityBits)
        assertEquals(4 + 16 + secret.length * 8, error.requiredBits)
        assertFalse(error.message.orEmpty().contains("s3cr3t"))
    }
}
