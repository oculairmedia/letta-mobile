package com.letta.mobile.data.transport.appserver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AppServerDeviceCommandProtocolTest {

    @Test
    fun decodesExecuteCommandAndResponse() {
        val cmd = AppServerCommand.ExecuteCommand(
            requestId = "cmd-1",
            commandId = "compact",
            args = "--force",
        )
        val json = AppServerProtocol.encodeCommand(cmd)
        assertTrue(json.contains("execute_command"))
        assertTrue(json.contains("compact"))

        val received = AppServerProtocol.decodeFrame(
            rawJson = """{"type":"execute_command_response","request_id":"cmd-1","success":true,"output":"Compacted"}""",
            channel = AppServerChannel.Control,
        )
        val frame = assertIs<AppServerInboundFrame.ExecuteCommandResponse>(received.frame)
        assertEquals("cmd-1", frame.requestId)
        assertTrue(frame.success)
        assertEquals("Compacted", frame.output)
    }

    @Test
    fun decodesMonitorStopAndResponse() {
        val cmd = AppServerCommand.MonitorStop(
            requestId = "mon-1",
            processId = "proc-42",
        )
        val json = AppServerProtocol.encodeCommand(cmd)
        assertTrue(json.contains("monitor_stop"))
        assertTrue(json.contains("proc-42"))

        val received = AppServerProtocol.decodeFrame(
            rawJson = """{"type":"monitor_stop_response","request_id":"mon-1","success":true}""",
            channel = AppServerChannel.Control,
        )
        val frame = assertIs<AppServerInboundFrame.MonitorStopResponse>(received.frame)
        assertEquals("mon-1", frame.requestId)
        assertTrue(frame.success)
    }

    @Test
    fun decodesUpdateToolsetAndResponse() {
        val cmd = AppServerCommand.UpdateToolset(
            toolsetPreference = "codex",
            requestId = "tool-1",
        )
        val json = AppServerProtocol.encodeCommand(cmd)
        assertTrue(json.contains("update_toolset"))
        assertTrue(json.contains("codex"))

        val received = AppServerProtocol.decodeFrame(
            rawJson = """{"type":"update_toolset_response","request_id":"tool-1","success":true,"current_toolset":"codex"}""",
            channel = AppServerChannel.Control,
        )
        val frame = assertIs<AppServerInboundFrame.UpdateToolsetResponse>(received.frame)
        assertEquals("tool-1", frame.requestId)
        assertTrue(frame.success)
        assertEquals("codex", frame.currentToolset)
    }

    @Test
    fun decodesCronPauseResumeAndCronsUpdated() {
        val pauseCmd = AppServerCommand.CronPause(
            requestId = "cp-1",
            taskId = "task-10",
        )
        val pauseJson = AppServerProtocol.encodeCommand(pauseCmd)
        assertTrue(pauseJson.contains("cron_pause"))
        assertTrue(pauseJson.contains("task-10"))

        val resumeCmd = AppServerCommand.CronResume(
            requestId = "cr-1",
            taskId = "task-10",
            scheduledFor = "2026-10-10T00:00:00Z",
        )
        val resumeJson = AppServerProtocol.encodeCommand(resumeCmd)
        assertTrue(resumeJson.contains("cron_resume"))

        val pauseResp = AppServerProtocol.decodeFrame(
            rawJson = """{"type":"cron_pause_response","request_id":"cp-1","success":true}""",
            channel = AppServerChannel.Control,
        )
        assertIs<AppServerInboundFrame.CronPauseResponse>(pauseResp.frame)

        val resumeResp = AppServerProtocol.decodeFrame(
            rawJson = """{"type":"cron_resume_response","request_id":"cr-1","success":true}""",
            channel = AppServerChannel.Control,
        )
        assertIs<AppServerInboundFrame.CronResumeResponse>(resumeResp.frame)

        val updated = AppServerProtocol.decodeFrame(
            rawJson = """{"type":"crons_updated","reason":"paused","agent_id":"agent-1","tasks_active":3}""",
            channel = AppServerChannel.Stream,
        )
        val updatedFrame = assertIs<AppServerInboundFrame.CronsUpdated>(updated.frame)
        assertEquals("paused", updatedFrame.reason)
        assertEquals("agent-1", updatedFrame.agentId)
        assertEquals(3L, updatedFrame.tasksActive)
    }
}
