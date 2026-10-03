package com.letta.mobile.architecture

import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

class SharedUiIsolationTest {
    private val projectRoot: Path = Path.of(requireNotNull(System.getProperty("architecture.projectRoot")))
    private val sharedUiBuild = projectRoot.resolve("android-compose/sharedUI/build.gradle.kts")

    @Test
    fun `sharedUI must not depend on platform app or Android feature modules`() {
        val gradle = sharedUiBuild.readText()
        val forbiddenProjectDeps = listOf(
            ":app",
            ":core:android-data",
            ":core:data",
            ":core:domain",
            ":designsystem",
            ":feature-chat",
            ":feature-editagent",
            ":desktop",
            ":web",
            // :plugin-view hosts live plugin pages on top of this module; the reverse edge would be a cycle.
            ":plugin-view",
        )
        // Matches project(":x") and the projects.x type-safe accessor.
        val hits = GradleProjectDependencyScan.hits(gradle, forbiddenProjectDeps)
        check(hits.isEmpty()) {
            "sharedUI/build.gradle.kts must not depend on platform modules: $hits"
        }
    }

    @Test
    fun `sharedUI must depend on sharedLogic`() {
        val gradle = sharedUiBuild.readText()
        check(GradleProjectDependencyScan.hits(gradle, listOf(":sharedLogic")).isNotEmpty()) {
            "sharedUI/build.gradle.kts must depend on :sharedLogic"
        }
    }
}
