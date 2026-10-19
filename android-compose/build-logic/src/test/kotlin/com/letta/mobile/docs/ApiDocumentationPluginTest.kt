package com.letta.mobile.docs

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApiDocumentationPluginTest {
    @TempDir
    lateinit var projectDir: File

    @Test
    fun `aggregates module and package overviews with KDoc and revision links`() {
        projectDir.resolve("settings.gradle.kts").writeText("""
            rootProject.name = "api-fixture"
            include(":core:runtime", ":sharedLogic")
        """.trimIndent())
        projectDir.resolve("build.gradle.kts").writeText("""
            plugins { id("com.letta.mobile.api-documentation") }
            repositories { mavenCentral() }
            dependencies {
                add("dokka", project(":core:runtime"))
                add("dokka", project(":sharedLogic"))
            }
        """.trimIndent())
        projectDir.resolve("Module.md").writeText("""
            # Module Letta Mobile API
            Shared contracts connect the product hosts through portable Kotlin domain and rendering APIs.
        """.trimIndent())
        val module = projectDir.resolve("core/runtime").apply { mkdirs() }
        module.resolve("Module.md").writeText("""
            # Module :core:runtime
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
                class MissingContractDoc
                internal class HiddenClock
            """.trimIndent())
        }
        val shared = projectDir.resolve("sharedLogic").apply { mkdirs() }
        shared.resolve("build.gradle.kts").writeText(module.resolve("build.gradle.kts").readText())
        shared.resolve("src/main/kotlin/Extension.kt").apply {
            parentFile.mkdirs()
            writeText("package com.letta.mobile.data.timeline.api; class MissingExtensionDoc")
        }
        shared.resolve("src/main/kotlin/Outside.kt").writeText("package example; class ExistingUngatedDebt")
        val runner = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(
                "dokkaGeneratePublicationHtml", "-PdokkaSourceRef=fixture-sha", "--configuration-cache",
                "--build-cache", "--no-parallel", "--max-workers=2", "-Pkotlin.compiler.execution.strategy=in-process",
            )
        val result = runner.build()
        val repeated = runner.build()
        assertEquals(TaskOutcome.SUCCESS, repeated.task(":core:runtime:dokkaGenerateModuleHtml")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, repeated.task(":sharedLogic:dokkaGenerateModuleHtml")?.outcome)
        assertTrue(repeated.output.contains("Undocumented: example/MissingContractDoc/"))

        assertTrue(result.output.contains("Undocumented: example/MissingContractDoc/"))
        assertFalse(result.output.contains("Undocumented: example/ApiClock/"))
        assertTrue(result.output.contains("Undocumented: com.letta.mobile.data.timeline.api/MissingExtensionDoc/"))
        assertFalse(result.output.contains("Undocumented: example/ExistingUngatedDebt/"))

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
