package com.letta.mobile.data.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProjectWireDecodingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun projectSummaryDecodesMoleculeEngineCatalogShape() {
        val project = json.decodeFromString<ProjectSummary>("""{
            "id":"alpha","identifier":"alpha","name":"Alpha","status":"active",
            "tech_stack":null,"filesystem_path":"/src/alpha","git_url":null,
            "letta_agent_id":null,"beads_prefix":"a",
            "repo":{"provider":null,"remote_url":null,"filesystem_path":"/src/alpha","branch":null,"dirty":null},
            "agents":{"total":0,"active":0,"default_agent_id":null},
            "tracker":{"provider":"beads","status":"available"},
            "beads_remote":{"status":"not_provisioned","owner":null,"url":null},
            "last_scan_at":null,"last_activity_at":null,"updated_at":null,"version":null,"etag":null
        }""")
        assertEquals("alpha", project.identifier)
        assertEquals("/src/alpha", project.filesystemPath)
        assertEquals("beads", project.tracker?.provider)
        assertEquals("not_provisioned", project.beadsRemote?.status)
        assertNull(project.vibeId)
        assertNull(project.hulyId)
        assertNull(project.issueCount)
        assertNull(project.mcpEnabled)
        assertNull(project.lettaFolderId)
    }

    @Test
    fun healthDecodesMoleculeEngineShape() {
        val health = Json.decodeFromString<VibesyncHealthResponse>("""{
            "status":"healthy","service":"vibesync",
            "uptime":{"milliseconds":1200,"seconds":1},
            "orchestration":{"ready":true},
            "memory":{"rss":100,"heapUsed":50,"heapTotal":80}
        }""")
        assertEquals("healthy", health.status)
        assertEquals(Json.parseToJsonElement("""{"ready":true}"""), health.orchestration)
        assertNull(health.sync)
        assertNull(health.database)
        assertNull(health.config)
    }
}
