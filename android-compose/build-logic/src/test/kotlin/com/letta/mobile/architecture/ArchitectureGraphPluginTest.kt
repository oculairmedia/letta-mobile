package com.letta.mobile.architecture

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals

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

        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(arguments)
            .build()
        assertEquals(first, output.readText())
    }

    private fun fixture(resource: String, destination: String) {
        val target = projectDir.resolve(destination)
        target.parentFile.mkdirs()
        target.writeText(resource(resource))
    }

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResource("/fixtures/$name")) { "Missing fixture $name" }.readText()
}
