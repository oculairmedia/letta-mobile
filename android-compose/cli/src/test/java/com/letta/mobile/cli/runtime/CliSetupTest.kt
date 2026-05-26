package com.letta.mobile.cli.runtime

import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CliSetupTest {
    @Test
    fun `reads json setup documents`() {
        val file = Files.createTempFile("letta-cli-setup", ".json")
        Files.write(
            file,
            """
            {
              "activeProfile": "dev",
              "profiles": [
                {"name": "dev", "baseUrl": "https://example.test", "defaultAgentId": "agt_1"}
              ],
              "resources": {
                "agents": [
                  {"ref": "primary", "id": "agt_1", "body": {"name": "Primary"}}
                ]
              }
            }
            """.trimIndent().toByteArray(Charsets.UTF_8)
        )

        val document = readCliSetupDocument(file)

        assertEquals("dev", document.activeProfile)
        assertEquals("https://example.test", document.profiles.single().baseUrl)
        assertEquals("primary", document.resources.agents.single().ref)
        assertEquals("Primary", document.resources.agents.single().body.stringField("name"))
    }

    @Test
    fun `reads yaml setup documents through the same model`() {
        val file = Files.createTempFile("letta-cli-setup", ".yaml")
        Files.write(
            file,
            """
            activeProfile: dev
            profiles:
              - name: dev
                baseUrl: https://example.test
                prefs:
                  enableProjects: true
            resources:
              projects:
                - ref: app
                  id: project-a
                  body:
                    name: App
                    filesystem_path: C:/work/app
            """.trimIndent().toByteArray(Charsets.UTF_8)
        )

        val document = readCliSetupDocument(file)

        assertEquals("dev", document.activeProfile)
        assertEquals(true, document.profiles.single().prefs.enableProjects)
        assertEquals("project-a", document.resources.projects.single().id)
        assertEquals("App", document.resources.projects.single().body.stringField("name"))
    }

    @Test
    fun `serializes setup documents with profile defaults`() {
        val output = writeCliSetupDocument(
            CliSetupDocument(
                activeProfile = "dev",
                profiles = listOf(CliProfile(name = "dev", defaultProjectId = "project-a")),
            )
        )

        assertTrue(output.contains("\"activeProfile\": \"dev\""))
        assertTrue(output.contains("\"defaultProjectId\": \"project-a\""))
    }
}
