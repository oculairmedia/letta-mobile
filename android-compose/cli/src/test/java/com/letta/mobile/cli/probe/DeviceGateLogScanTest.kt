package com.letta.mobile.cli.probe

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeviceGateLogScanTest {
    private val conv = "conv-abc"

    /** A clean, passing on-device streaming capture (threadtime-style logcat). */
    private fun greenLog(): List<String> = listOf(
        "06-30 12:00:00.100  1234  1250 I Telemetry/AdminChatVM: sendMessage.route via=iroh conversationId=$conv length=5",
        "06-30 12:00:00.300  1234  1260 I Telemetry/IrohTransport: frame.recv channel=Stream type=stream_delta",
        "06-30 12:00:00.310  1234  1260 I Telemetry/IrohGate: gate1.emitBoth frame=AssistantMessage messageId=m1 conversationId=$conv",
        "06-30 12:00:00.320  1234  1260 I Telemetry/IrohGate: gate3.coordinatorMessageDelta resolvedConversationId=$conv messageId=m1 messageType=assistant_message isReplay=false",
        "06-30 12:00:00.330  1234  1260 I Telemetry/IrohGate: gate4.repositoryIngest agentId=a1 conversationId=$conv messageId=m1 messageType=assistant_message",
        "06-30 12:00:00.340  1234  1270 I Telemetry/TimelineSync: uiProjection.snapshot (2ms) conversationId=$conv eventsTotal=10 messageCount=4 isStreaming=true isReconciling=false",
        "06-30 12:00:00.360  1234  1260 I Telemetry/IrohTransport: frame.recv channel=Stream type=stream_delta",
        "06-30 12:00:00.380  1234  1270 I Telemetry/TimelineSync: uiProjection.snapshot (1ms) conversationId=$conv eventsTotal=12 messageCount=5 isStreaming=true isReconciling=false",
        "06-30 12:00:00.500  1234  1250 I Telemetry/AdminChatVM: ws.turnComplete status=completed turnId=t1 runId=r1 stopReason=end_turn lossy=false reason=turnDone",
        "06-30 12:00:00.520  1234  1270 I Telemetry/TimelineSync: uiProjection.snapshot (1ms) conversationId=$conv eventsTotal=13 messageCount=5 isStreaming=false isReconciling=false",
    )

    @Test
    fun `green capture passes all invariants`() {
        val metrics = DeviceGateLogScan.parse(greenLog(), conv, socketSamples = listOf(0, 0, 0), httpPort = 8291)
        val summary = DeviceGateLogScan.summarize(metrics)
        assertTrue(summary.ok, "expected ok, got ${summary.violations}")
        assertEquals(2, metrics.streamFrameRecvCount)
        assertEquals(1, metrics.turnCompleteCount)
        assertEquals(3, metrics.projections.size)
        assertFalse(metrics.projections.last().isStreaming)
    }

    @Test
    fun `brief logcat format with pid parens parses`() {
        val brief = listOf(
            "I/Telemetry/IrohTransport( 1234): frame.recv channel=Stream type=stream_delta",
            "I/Telemetry/IrohGate( 1234): gate1.emitBoth frame=AssistantMessage messageId=m1 conversationId=$conv",
        )
        val metrics = DeviceGateLogScan.parse(brief, conv)
        assertEquals(1, metrics.streamFrameRecvCount)
        assertEquals(1, metrics.gateEmitCount)
    }

    @Test
    fun `missing terminal is flagged`() {
        val log = greenLog().filterNot { it.contains("ws.turnComplete") }
        val summary = DeviceGateLogScan.summarize(DeviceGateLogScan.parse(log, conv))
        assertFalse(summary.ok)
        assertTrue(summary.violations.contains("terminal_count_0"), summary.violations.toString())
    }

    @Test
    fun `two terminals for one send is flagged`() {
        val log = greenLog() + "06-30 12:00:00.900  1234  1250 I Telemetry/AdminChatVM: ws.turnComplete status=completed turnId=t2 runId=r2 stopReason=end_turn lossy=false reason=subscribeDone"
        val summary = DeviceGateLogScan.summarize(DeviceGateLogScan.parse(log, conv))
        assertFalse(summary.ok)
        assertTrue(summary.violations.contains("terminal_count_2"), summary.violations.toString())
    }

    @Test
    fun `no streaming frames is flagged`() {
        val log = greenLog().filterNot { it.contains("frame.recv") }
        val summary = DeviceGateLogScan.summarize(DeviceGateLogScan.parse(log, conv))
        assertFalse(summary.ok)
        assertTrue(summary.violations.contains("no_stream_frames"), summary.violations.toString())
    }

    @Test
    fun `isStreaming that never clears is flagged`() {
        val log = greenLog().dropLast(1) // remove the final isStreaming=false snapshot
        val summary = DeviceGateLogScan.summarize(DeviceGateLogScan.parse(log, conv))
        assertFalse(summary.ok)
        assertTrue(summary.violations.contains("isStreaming_not_cleared"), summary.violations.toString())
    }

    @Test
    fun `update only after reconcile is flagged`() {
        // A reconcile marker arrives before the first live (streaming/growth) projection.
        val log = listOf(
            "06-30 12:00:00.100  1234  1250 I Telemetry/AdminChatVM: sendMessage.route via=iroh conversationId=$conv length=5",
            "06-30 12:00:00.120  1234  1270 I Telemetry/TimelineSync: uiProjection.snapshot conversationId=$conv eventsTotal=10 messageCount=4 isStreaming=false isReconciling=false",
            "06-30 12:00:00.200  1234  1270 I Telemetry/TimelineSync: recentReconcile.contentDeduped conversationId=$conv",
            "06-30 12:00:00.300  1234  1260 I Telemetry/IrohTransport: frame.recv channel=Stream type=stream_delta",
            "06-30 12:00:00.310  1234  1260 I Telemetry/IrohGate: gate1.emitBoth frame=AssistantMessage messageId=m1 conversationId=$conv",
            "06-30 12:00:00.340  1234  1270 I Telemetry/TimelineSync: uiProjection.snapshot conversationId=$conv eventsTotal=12 messageCount=5 isStreaming=true isReconciling=false",
            "06-30 12:00:00.520  1234  1270 I Telemetry/TimelineSync: uiProjection.snapshot conversationId=$conv eventsTotal=13 messageCount=5 isStreaming=false isReconciling=false",
            "06-30 12:00:00.500  1234  1250 I Telemetry/AdminChatVM: ws.turnComplete status=completed turnId=t1 runId=r1 stopReason=end_turn lossy=false reason=turnDone",
        )
        val summary = DeviceGateLogScan.summarize(DeviceGateLogScan.parse(log, conv))
        assertFalse(summary.ok)
        assertTrue(summary.violations.contains("update_only_after_reconcile"), summary.violations.toString())
    }

    @Test
    fun `isReconciling attribute is not mistaken for a reconcile marker`() {
        // The green log's snapshots all carry isReconciling=false; must NOT count as reconcile.
        val metrics = DeviceGateLogScan.parse(greenLog(), conv)
        assertEquals(0, metrics.reconcileMarkerCount)
    }

    @Test
    fun `http socket to backend port is flagged`() {
        val metrics = DeviceGateLogScan.parse(greenLog(), conv, socketSamples = listOf(0, 1, 0), httpPort = 8291)
        val summary = DeviceGateLogScan.summarize(metrics)
        assertFalse(summary.ok)
        assertTrue(summary.violations.any { it.startsWith("no_http_tcp_connects") }, summary.violations.toString())
    }

    @Test
    fun `markers for a different conversation are ignored`() {
        val metrics = DeviceGateLogScan.parse(greenLog(), "conv-other")
        assertFalse(metrics.sendRouted)
        assertEquals(0, metrics.gateEmitCount)
        assertEquals(0, metrics.projections.size)
        // frame.recv carries no conversationId, so it is still counted globally.
        assertEquals(2, metrics.streamFrameRecvCount)
    }
}

class AppUidSocketScanTest {
    // 8291 = 0x2063; app uid 10234
    private val header = "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode"
    private fun row(remHex: String, uid: Int) =
        "   0: 0100007F:A3F2 $remHex 01 00000000:00000000 00:00000000 00000000  $uid        0 111 1 0 20 4 30 10 -1"

    @Test
    fun `counts rows to target port owned by app uid`() {
        val lines = listOf(
            row("0100007F:2063", 10234), // app -> 8291  : COUNTS
            row("0100007F:2063", 0),     // root -> 8291 : wrong uid
            row("0100007F:270F", 10234), // app -> 9999  : wrong port
        )
        assertEquals(1, AppUidSocketScan.countConnectionsToPort(lines, uid = 10234, port = 8291))
    }

    @Test
    fun `zero when app makes no connection to the port`() {
        val lines = listOf(row("0100007F:270F", 10234))
        assertEquals(0, AppUidSocketScan.countConnectionsToPort(lines, uid = 10234, port = 8291))
    }

    @Test
    fun `header and malformed rows are ignored`() {
        val lines = listOf(header, "garbage", "", row("0100007F:2063", 10234))
        assertEquals(1, AppUidSocketScan.countConnectionsToPort(lines, uid = 10234, port = 8291))
    }

    @Test
    fun `splits concatenated dumps into per-sample groups`() {
        val lines = listOf(
            header,
            row("0100007F:2063", 10234),
            header,
            row("0100007F:270F", 10234),
            row("0100007F:2063", 10234),
        )
        val samples = AppUidSocketScan.splitSamples(lines)
        assertEquals(2, samples.size)
        assertEquals(1, samples[0].size)
        assertEquals(2, samples[1].size)
    }
}
