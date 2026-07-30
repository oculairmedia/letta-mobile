package com.letta.mobile.data.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * letta-mobile-imgdrop: structural fingerprinting of *unclassified* terminal
 * reasons.
 *
 * Motivating incident: an image-bearing turn failed in ~264ms with
 * `status=Failed reasonKind=other exceptionType=<none>` and produced no
 * user-visible error and no persisted message. The category token alone was a
 * dead end, and the raw reason is deliberately never logged (o0atv), so there
 * was no way to identify the failure at all.
 *
 * These tests pin the two guarantees that make the fingerprint safe to ship:
 * it never emits reason content, and the digest is stable enough to correlate
 * repeat occurrences (and to match a candidate string offline).
 */
class AppServerTurnEngineTerminalReasonShapeTest {
    @Test
    fun blankReasonYieldsNoneSentinel() {
        assertEquals("<none>", terminalReasonShape(null))
        assertEquals("<none>", terminalReasonShape(""))
        assertEquals("<none>", terminalReasonShape("   "))
    }

    @Test
    fun shapeNeverLeaksReasonContent() {
        // Every whitespace-delimited token of a secret-bearing reason must be
        // absent from the fingerprint. This is the o0atv guarantee.
        val secret = "upload rejected: bearer sk-live-9f3ab21 for /home/emmanuel/private.png"
        val shape = terminalReasonShape(secret)
        for (token in secret.split(' ')) {
            val cleaned = token.trim(':', ',', '.')
            if (cleaned.length < 4) continue
            assertFalse(
                cleaned.lowercase() in shape.lowercase(),
                "fingerprint leaked reason token '$cleaned': $shape",
            )
        }
        assertFalse("sk-live" in shape)
        assertFalse("emmanuel" in shape)
    }

    @Test
    fun digestIsStableForSameReason() {
        val reason = "something entirely novel went wrong"
        assertEquals(terminalReasonShape(reason), terminalReasonShape(reason))
    }

    @Test
    fun digestDiffersForDifferentReasons() {
        val a = terminalReasonShape("first unknown failure")
        val b = terminalReasonShape("second unknown failure")
        assertTrue(a != b, "distinct reasons collided: a=$a b=$b")
    }

    @Test
    fun lengthIsBucketedNotExact() {
        // A short reason reports its bucket, never its precise length, so the
        // fingerprint cannot be used to size-probe redacted content.
        val shape = terminalReasonShape("no")
        assertTrue(shape.startsWith("len<=32"), shape)
        assertTrue(terminalReasonShape("x".repeat(100)).startsWith("len<=128"))
        assertTrue(terminalReasonShape("x".repeat(400)).startsWith("len<=512"))
        assertTrue(terminalReasonShape("x".repeat(2000)).startsWith("len>512"))
    }

    @Test
    fun charsetFlagsDescribeShapeOnly() {
        assertTrue("json" in terminalReasonShape("""{"error":"nope"}"""))
        assertTrue("url" in terminalReasonShape("failed calling https://api.example.com/v1"))
        assertTrue("digits" in terminalReasonShape("code 500 upstream"))
        assertTrue("nonascii" in terminalReasonShape("upload failed — bad payload"))
        // A plain lowercase sentence with no punctuation has no shape markers.
        assertTrue("plain" in terminalReasonShape("upload failed"))
    }

    @Test
    fun wordCountIsReported() {
        assertTrue("words=3" in terminalReasonShape("three word reason"))
    }

    @Test
    fun mediaFamilyNowClassifiesInsteadOfFallingThrough() {
        // The imgdrop incident's plausible reason shapes must no longer land in
        // "other" — that is the whole point of the added families.
        assertEquals("media_rejected", terminalReasonKind("image part could not be encoded"))
        assertEquals("media_rejected", terminalReasonKind("unknown mime type for attachment"))
        assertEquals("payload_too_large", terminalReasonKind("request payload too large"))
        assertEquals("unsupported_input", terminalReasonKind("input type not supported by handler"))
    }

    @Test
    fun previouslyPinnedFamiliesStillClassifyTheSameWay() {
        // Guard against the new rules stealing matches from aktss's families.
        assertEquals(
            "provider_error",
            terminalReasonKind("provider rejected: api key sk-abc123 invalid"),
        )
        assertEquals("timeout", terminalReasonKind("request timed out after 60000ms"))
        assertEquals("rate_limited", terminalReasonKind("HTTP 429 rate limit exceeded"))
        assertEquals(
            "content_filter",
            terminalReasonKind("Model provider error: Provider finish_reason: content_filter"),
        )
        assertEquals("other", terminalReasonKind("something entirely novel went wrong"))
    }
}
