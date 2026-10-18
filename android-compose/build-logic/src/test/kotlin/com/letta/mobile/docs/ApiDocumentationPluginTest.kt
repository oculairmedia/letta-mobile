package com.letta.mobile.docs

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApiDocumentationPluginTest {
    @TempDir
    lateinit var projectDir: File

    @Test
    fun `aggregates KDoc with revision links and excludes internal declarations`() {
        projectDir.resolve("settings.gradle.kts").writeText("""
            rootProject.name = "api-fixture"
            include(":contract")
        """.trimIndent())
        projectDir.resolve("build.gradle.kts").writeText("""
            plugins { id("com.letta.mobile.api-documentation") }
            repositories { mavenCentral() }
            dependencies { add("dokka", project(":contract")) }
        """.trimIndent())
        val module = projectDir.resolve("contract").apply { mkdirs() }
        module.resolve("build.gradle.kts").writeText("""
            plugins {
                id("org.jetbrains.kotlin.jvm")
                id("com.letta.mobile.api-documentation")
            }
            repositories { mavenCentral() }
        """.trimIndent())
        module.resolve("src/main/kotlin/example/ApiClock.kt").apply {
            parentFile.mkdirs()
            writeText("""
                package example
                /** A clock carried by a shared contract. */
                class ApiClock
                internal class HiddenClock
            """.trimIndent())
        }
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("dokkaGeneratePublicationHtml", "-PdokkaSourceRef=fixture-sha", "--configuration-cache")
            .build()

        val publication = projectDir.resolve("build/dokka/html")
        assertTrue(publication.resolve("index.html").isFile)
        val pages = publication.walkTopDown().filter { it.extension == "html" }.toList()
        val clock = pages.single { it.path.replace('\\', '/').endsWith("/-api-clock/index.html") }.readText()
        assertTrue(clock.contains("A clock carried by a shared contract."))
        assertTrue(clock.contains("/tree/fixture-sha/"))
        assertFalse(pages.any { it.path.contains("hidden-clock") })
    }
}
