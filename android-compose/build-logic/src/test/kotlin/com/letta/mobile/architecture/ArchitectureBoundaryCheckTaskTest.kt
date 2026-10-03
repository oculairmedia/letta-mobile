package com.letta.mobile.architecture

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End to end through Gradle: a module that reaches :app through the type-safe
 * `projects.app` accessor. A grep for `project(":app")` passes this build; the
 * graph gate must not.
 */
class ArchitectureBoundaryCheckTaskTest {
    @TempDir
    lateinit var projectDir: File

    @Test
    fun `fails on projects-app accessor edge until it is baselined`() {
        write("settings.gradle.kts", SETTINGS)
        write("build.gradle.kts", ROOT_BUILD)
        write("app/build.gradle.kts", "plugins { java }\n")
        write("lib/build.gradle.kts", LIB_BUILD)
        assertFalse(projectDir.resolve("lib/build.gradle.kts").readText().contains("project(\":app\")"))

        val failure = runner().buildAndFail()
        assertTrue(failure.output.contains("into-app :lib -> :app"), failure.output)

        write("architecture-tests/boundary-baseline.txt", "into-app :lib -> :app letta-mobile-fixture.1\n")
        val success = runner().build()
        assertTrue(success.output.contains("Module-boundary gate: PASSED"), success.output)
    }

    private fun runner(): GradleRunner = GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()
        .withArguments("checkArchitectureBoundaries", "--configuration-cache", "--stacktrace")

    private fun write(path: String, content: String) {
        projectDir.resolve(path).apply { parentFile.mkdirs() }.writeText(content)
    }

    private companion object {
        const val SETTINGS = """
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
rootProject.name = "boundary-fixture"
include(":app")
include(":lib")
"""

        const val ROOT_BUILD = """
plugins {
    id("com.letta.mobile.architecture-graph")
}
"""

        const val LIB_BUILD = """
plugins {
    `java-library`
}

dependencies {
    implementation(projects.app)
}
"""
    }
}
