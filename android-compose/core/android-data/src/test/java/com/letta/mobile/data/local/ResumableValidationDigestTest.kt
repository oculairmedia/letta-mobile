package com.letta.mobile.data.local

import org.junit.Assert.*
import org.junit.Test

class ResumableValidationDigestTest {
    @Test fun twentyEightThousandVariableLengthRowsResumeExactLegacyFlatDigest() {
        val expected = java.security.MessageDigest.getInstance("SHA-256")
        var state = ""
        repeat(28000) { i ->
            val encoded = listOf(i.toString(), "0", i.toString(), checksum(byteArrayOf(i.toByte())))
                .joinToString("") { "${it.length}:$it;" }.encodeToByteArray()
            expected.update(encoded)
            val digest = ResumableLedgerSha256.restore(state)
            digest.update(encoded)
            state = digest.checkpoint()
        }
        val actual = ResumableLedgerSha256.restore(state).finish()
        assertEquals(expected.digest().joinToString("") { "%02x".format(it.toInt() and 255) }, actual)
    }

    @Test fun bodyMidpointRestartAndDiscardedUpdateRetainExactDigest() {
        val bytes = ByteArray(1024 * 1024 + 17) { (it % 251).toByte() }
        var state = ""
        var offset = 0
        while (offset < bytes.size) {
            val chunk = bytes.copyOfRange(offset, minOf(offset + 32768, bytes.size))
            // Simulate a cancelled unit: its in-memory digest must not alter the saved state.
            ResumableLedgerSha256.restore(state).update(chunk)
            val next = ResumableLedgerSha256.restore(state)
            next.update(chunk)
            state = next.checkpoint()
            offset += chunk.size
        }
        assertEquals(checksum(bytes), ResumableLedgerSha256.restore(state).finish())
    }

    @Test fun malformedCheckpointFailsClosed() {
        for (state in listOf("broken", "-1:0:0:0:0:0:0:0:0", "1:0:0:0:0:0:0:0:0:")) {
            try { ResumableLedgerSha256.restore(state); fail("Corrupt state accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
