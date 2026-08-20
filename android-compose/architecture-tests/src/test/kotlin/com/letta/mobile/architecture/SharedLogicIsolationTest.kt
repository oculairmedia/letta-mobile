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
        )
        val hits = forbiddenProjectDeps.filter { it in gradle }
        check(hits.isEmpty()) {
            "sharedLogic/build.gradle.kts must not depend on platform modules: $hits"
        }
    }
}
