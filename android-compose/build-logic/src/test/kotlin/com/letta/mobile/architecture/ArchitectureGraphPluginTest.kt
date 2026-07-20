package com.letta.mobile.architecture

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ArchitectureGraphPluginTest {
    @TempDir
    lateinit var projectDir: File

    @Test
    fun `exports stable declared graph without resolving configurations`() {
        fixture("settings.gradle.kts", "settings.gradle.kts")
        fixture("build.gradle.kts", "build.gradle.kts")
        fixture("lib.gradle.kts", "lib/build.gradle.kts")

        val arguments = listOf("exportArchitectureGraph", "--configuration-cache", "--stacktrace")
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(arguments)
            .build()

        val output = projectDir.resolve("build/reports/architecture/graph.jsonl")
        val first = output.readText()
        assertEquals(first.lines().filter(String::isNotEmpty).sorted(), first.lines().filter(String::isNotEmpty))
        assertEquals(resource("expected.jsonl"), first)
        assertFalse(first.contains("org.example:ignored"))
        assertContractFiles(first)

        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(arguments)
            .build()
        assertEquals(first, output.readText())
        assertContractFiles(first)
    }

    private fun assertContractFiles(graph: String) {
        val records = graph.lineSequence().filter(String::isNotEmpty).toList()
        val contractDirectory = projectDir.resolve("build/reports/architecture/contract")
        mapOf(
            "modules.jsonl" to setOf("module"),
            "source_sets.jsonl" to setOf("sourceSet"),
            "variants.jsonl" to setOf("variant"),
            "project_edges.jsonl" to setOf("projectEdge"),
            "external_edges.jsonl" to setOf("externalDependency"),
        ).forEach { (fileName, types) ->
            val expected = records.filter { record -> types.any { type -> record.contains("\"type\":\"$type\"") } }
            val contractFile = contractDirectory.resolve(fileName)
            assertEquals(expected, contractFile.readLines(), fileName)
        }
    }

    private fun fixture(resource: String, destination: String) {
        val target = projectDir.resolve(destination)
        target.parentFile.mkdirs()
        target.writeText(resource(resource))
    }

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResource("/fixtures/$name")) { "Missing fixture $name" }.readText()
}
