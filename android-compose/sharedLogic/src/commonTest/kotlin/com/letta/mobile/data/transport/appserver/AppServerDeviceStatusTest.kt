package com.letta.mobile.data.transport.appserver

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [AppServerDeviceStatusSnapshot] decoding (letta-mobile-bzvro.19).
 */
class AppServerDeviceStatusTest {

    @Test
    fun decodesEmptyAndMalformedDeviceStatusTolerantly() {
        val empty = JsonObject(emptyMap()).toDeviceStatusSnapshot()
        assertNull(empty.currentWorkingDirectory)
        assertNull(empty.currentPermissionMode)
        assertNull(empty.cwdRevision)
        assertNull(empty.gitContext)
        assertNull(empty.lettaCodeVersion)
        assertTrue(empty.supportedCommands.isEmpty())
        assertNull(empty.currentToolset)
        assertNull(empty.toolsetPreference)
        assertTrue(empty.availableToolsets.isEmpty())
        assertTrue(empty.backgroundProcesses.isEmpty())
        assertTrue(empty.pendingControlRequests.isEmpty())
        assertTrue(empty.experiments.isEmpty())
        assertNull(empty.memoryDirectory)
        assertFalse(empty.isProcessing)
        assertNull(empty.isOnline)
    }

    @Test
    fun decodesFullGolden032DeviceStatus() {
        val json = buildJsonObject {
            put("current_connection_id", "app-server")
            put("connection_name", "privdockge")
            put("is_online", true)
            put("is_processing", false)
            put("current_permission_mode", "standard")
            put("current_working_directory", "/tmp")
            put("letta_code_version", "0.32.3")
            put("current_toolset_preference", "auto")
            put("memory_directory", "/home/meridian/.letta/lc-local-backend/memfs/probe/memory")
            put("cwd_revision", 15)
            putJsonArray("supported_commands") {
                add(kotlinx.serialization.json.JsonPrimitive("clear"))
                add(kotlinx.serialization.json.JsonPrimitive("compact"))
                add(kotlinx.serialization.json.JsonPrimitive("monitor_stop"))
            }
            putJsonArray("available_toolsets") {
                add(
                    buildJsonObject {
                        put("id", "auto")
                        put("display_name", "Auto")
                        put("description", "Auto-select based on the model")
                        put("is_featured", true)
                    },
                )
                add(
                    buildJsonObject {
                        put("id", "letta")
                        put("display_name", "Letta")
                        put("description", "Experimental unified toolset")
                        put("is_featured", false)
                    },
                )
            }
            putJsonArray("experiments") {
                add(
                    buildJsonObject {
                        put("id", "artifacts")
                        put("label", "artifacts")
                        put("description", "Expose artifacts")
                        put("envVar", "LETTA_ARTIFACTS")
                        put("enabled", false)
                        put("source", "default")
                    },
                )
            }
        }

        val snapshot = json.toDeviceStatusSnapshot()
        assertEquals("/tmp", snapshot.currentWorkingDirectory)
        assertEquals(AppServerPermissionMode.Standard, snapshot.currentPermissionMode)
        assertEquals(15L, snapshot.cwdRevision)
        assertEquals("0.32.3", snapshot.lettaCodeVersion)
        assertEquals(listOf("clear", "compact", "monitor_stop"), snapshot.supportedCommands)
        assertEquals("auto", snapshot.toolsetPreference)
        assertEquals("/home/meridian/.letta/lc-local-backend/memfs/probe/memory", snapshot.memoryDirectory)
        assertFalse(snapshot.isProcessing)
        assertEquals(true, snapshot.isOnline)
        assertEquals(2, snapshot.availableToolsets.size)
        assertEquals("auto", snapshot.availableToolsets[0].id)
        assertEquals("Auto", snapshot.availableToolsets[0].displayName)
        assertTrue(snapshot.availableToolsets[0].isFeatured)
        assertEquals(1, snapshot.experiments.size)
        assertEquals("artifacts", snapshot.experiments[0].id)
        assertFalse(snapshot.experiments[0].enabled)
    }

    @Test
    fun decodesGitContextAndBackgroundProcesses() {
        val json = buildJsonObject {
            putJsonObject("git_context") {
                put("branch", "feat/my-branch")
                putJsonArray("recent_branches") {
                    add(kotlinx.serialization.json.JsonPrimitive("main"))
                    add(kotlinx.serialization.json.JsonPrimitive("develop"))
                }
            }
            putJsonArray("background_processes") {
                add(
                    buildJsonObject {
                        put("id", "proc-42")
                        put("type", "bash")
                        put("description", "gradle build")
                        put("started_at", "2026-10-08T10:00:00Z")
                        put("age_seconds", 120)
                    },
                )
            }
            putJsonArray("pending_control_requests") {
                add(
                    buildJsonObject {
                        put("request_id", "req-123")
                        putJsonObject("request") {
                            put("action", "approve")
                        }
                        put("agent_id", "agent-1")
                        put("conversation_id", "conv-1")
                    },
                )
            }
        }

        val snapshot = json.toDeviceStatusSnapshot()
        assertNotNull(snapshot.gitContext)
        assertEquals("feat/my-branch", snapshot.gitContext?.branch)
        assertEquals(listOf("main", "develop"), snapshot.gitContext?.recentBranches)

        assertEquals(1, snapshot.backgroundProcesses.size)
        val proc = snapshot.backgroundProcesses[0]
        assertEquals("proc-42", proc.id)
        assertEquals("bash", proc.type)
        assertEquals("gradle build", proc.description)
        assertEquals(120L, proc.ageSeconds)

        assertEquals(1, snapshot.pendingControlRequests.size)
        val req = snapshot.pendingControlRequests[0]
        assertEquals("req-123", req.requestId)
        assertEquals("agent-1", req.agentId)
        assertEquals("conv-1", req.conversationId)
    }
}
