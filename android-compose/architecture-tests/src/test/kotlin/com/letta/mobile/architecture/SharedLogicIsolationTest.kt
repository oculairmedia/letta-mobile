package com.letta.mobile.architecture

import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

class SharedLogicIsolationTest {
    private val projectRoot: Path = Path.of(requireNotNull(System.getProperty("architecture.projectRoot")))
    private val sharedLogicBuild = projectRoot.resolve("android-compose/sharedLogic/build.gradle.kts")

    @Test
    fun `sharedLogic must not depend on platform app or Android feature modules`() {
        val gradle = sharedLogicBuild.readText()
        val forbiddenProjectDeps = listOf(
            """project(":app")""",
            """project(":core:android-data")""",
            """project(":core:data")""",
            """project(":core:domain")""",
            """project(":designsystem")""",
            """project(":feature-chat")""",
            """project(":feature-editagent")""",
            """project(":desktop")""",
            """project(":web")""",
            // sharedUI depends on sharedLogic; reverse edge would be a cycle
            """project(":sharedUI")""",
        )
        val hits = forbiddenProjectDeps.filter { it in gradle }
        check(hits.isEmpty()) {
            "sharedLogic/build.gradle.kts must not depend on platform modules: $hits"
        }
    }

    @Test
    fun `sharedLogic must not apply Compose UI plugins or toolkit deps`() {
        val gradle = sharedLogicBuild.readText()
        val forbidden = listOf(
            """id("org.jetbrains.compose")""",
            """id("org.jetbrains.kotlin.plugin.compose")""",
            "org.jetbrains.compose.foundation",
            "org.jetbrains.compose.material3",
            "org.jetbrains.compose.ui:ui",
            "org.jetbrains.compose.animation",
            "coil-compose",
            "multiplatform-markdown-renderer",
            "icons-lucide",
        )
        val hits = forbidden.filter { it in gradle }
        check(hits.isEmpty()) {
            "sharedLogic must stay Compose-runtime-only after Phase 3c; found: $hits"
        }
        check("""org.jetbrains.compose.runtime:runtime""" in gradle) {
            "sharedLogic must keep compose.runtime for @Immutable/@Stable / MutableState"
        }
    }
}
