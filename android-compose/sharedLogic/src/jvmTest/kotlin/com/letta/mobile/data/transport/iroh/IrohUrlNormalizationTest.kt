package com.letta.mobile.data.transport.iroh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [IrohChannelTransport.normalizeIrohAddress] must accept every corrupted-
 * config form [IrohChannelTransport.isIrohUrl] accepts, so classification and
 * normalization can never disagree (finding 5, letta-mobile PR #831).
 */
class IrohUrlNormalizationTest {

    @Test
    fun stripsIrohScheme() {
        assertEquals(
            "abc@10.0.0.1:4501",
            IrohChannelTransport.normalizeIrohAddress("iroh://abc@10.0.0.1:4501"),
        )
    }

    @Test
    fun stripsCorruptedHttpsPrefix() {
        assertEquals(
            "abc@h:1",
            IrohChannelTransport.normalizeIrohAddress("https://iroh://abc@h:1"),
        )
        assertEquals(
            "abc",
            IrohChannelTransport.normalizeIrohAddress("http://iroh://abc"),
        )
    }

    @Test
    fun trimsWhitespace() {
        assertEquals("abc", IrohChannelTransport.normalizeIrohAddress("  iroh://abc "))
    }

    @Test
    fun normalizationParityWithClassification() {
        val acceptedByClassification = listOf(
            "iroh://abc@10.0.0.1:4501",
            "https://iroh://abc@h:1",
            "http://iroh://abc",
            "  iroh://abc ",
        )
        acceptedByClassification.forEach { url ->
            assertTrue(IrohChannelTransport.isIrohUrl(url), "isIrohUrl must accept: $url")
            val normalized = IrohChannelTransport.normalizeIrohAddress(url)
            assertTrue(normalized.isNotBlank(), "normalizeIrohAddress must not blank out: $url")
            assertFalse(normalized.contains("iroh://"), "normalized address must not retain the scheme: $normalized")
        }
    }
}
