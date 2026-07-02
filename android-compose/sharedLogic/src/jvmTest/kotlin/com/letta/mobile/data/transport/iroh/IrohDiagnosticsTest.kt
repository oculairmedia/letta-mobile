package com.letta.mobile.data.transport.iroh

import kotlin.test.Test
import kotlin.test.assertEquals

class IrohDiagnosticsTest {
    @Test
    fun endpointBytesNormalizeToLowercaseHex() {
        assertEquals(
            "00010f10ff",
            IrohDiagnostics.endpointIdHex(byteArrayOf(0x00, 0x01, 0x0f, 0x10, -1)),
        )
    }

    @Test
    fun selectedPathKindPrefersSelectedPathAndCountsDirectAndRelay() {
        val summary = IrohDiagnostics.summarizePaths(
            listOf(
                path(id = "relay-1", selected = false, isRelay = true, isIp = false, rttMs = 80, lostPackets = 2),
                path(id = "direct-1", selected = true, isRelay = false, isIp = true, rttMs = 12, lostPackets = 3),
            ),
        )

        assertEquals("direct", summary.selectedKind)
        assertEquals("direct-1", summary.selectedPathId)
        assertEquals(2, summary.pathCount)
        assertEquals(1, summary.relayPathCount)
        assertEquals(1, summary.directPathCount)
        assertEquals(12L, summary.selectedRttMs)
        assertEquals(5L, summary.lostPackets)
    }

    @Test
    fun pathKindFallsBackToRelayDirectOrUnknownFromRemoteAddress() {
        assertEquals("relay", IrohDiagnostics.pathKind("https://relay.example"))
        assertEquals("relay", IrohDiagnostics.pathKind("derp://region"))
        assertEquals("direct", IrohDiagnostics.pathKind("192.0.2.10:443"))
        assertEquals("unknown", IrohDiagnostics.pathKind("opaque"))
    }

    @Test
    fun closeReasonsRedactSecretsTokensAndKeyPaths() {
        assertEquals("normal shutdown", IrohDiagnostics.closeReason("normal shutdown"))
        assertEquals("<redacted>", IrohDiagnostics.closeReason("invalid bearer token abc"))
        assertEquals("<redacted>", IrohDiagnostics.closeReason("secret key path /home/user/.iroh/key"))
    }

    @Test
    fun frameAttributesExcludeBodySnippets() {
        assertEquals(
            listOf("type" to "stream_delta", "bytes" to 42),
            IrohDiagnostics.redactedFrameAttributes("stream_delta", 42),
        )
    }

    private fun path(
        id: String,
        selected: Boolean,
        isRelay: Boolean,
        isIp: Boolean,
        rttMs: Long,
        lostPackets: Long,
    ): computer.iroh.PathSnapshot {
        val stats = computer.iroh.PathStatsRecord(
            rttMs.toULong(),
            10u,
            100u,
            11u,
            110u,
            0u,
            0u,
            lostPackets.toULong(),
            (lostPackets * 100).toULong(),
            1280u,
        )
        return computer.iroh.PathSnapshot(
            id,
            selected,
            if (isRelay) "relay://example" else "127.0.0.1:443",
            isIp,
            isRelay,
            rttMs.toULong(),
            stats,
        )
    }
}
