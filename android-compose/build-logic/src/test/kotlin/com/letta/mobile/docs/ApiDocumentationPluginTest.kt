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
    fun `aggregates module and package overviews with KDoc and revision links`() {
        projectDir.resolve("settings.gradle.kts").writeText("""
            rootProject.name = "api-fixture"
            include(":contract")
        """.trimIndent())
        projectDir.resolve("build.gradle.kts").writeText("""
            plugins { id("com.letta.mobile.api-documentation") }
            repositories { mavenCentral() }
            dependencies { add("dokka", project(":contract")) }
        """.trimIndent())
        projectDir.resolve("Module.md").writeText("""
            # Module Letta Mobile API
            Shared contracts connect the product hosts through portable Kotlin domain and rendering APIs.
        """.trimIndent())
        val module = projectDir.resolve("contract").apply { mkdirs() }
        module.resolve("Module.md").writeText("""
            # Module :contract
            Extension authors implement this contract while the host owns lifecycle and capability enforcement.

            # Package example
            This fixture demonstrates how module and package overviews reach the generated API publication.
        """.trimIndent())
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
        assertTrue(publication.resolve("index.html").readText().contains("Shared contracts connect the product hosts"))
        assertTrue(pages.any { it.readText().contains("Extension authors implement this contract") })
        assertTrue(pages.any { it.readText().contains("This fixture demonstrates how module and package overviews") })
    }
}
