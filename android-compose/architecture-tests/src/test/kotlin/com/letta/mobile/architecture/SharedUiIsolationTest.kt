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
            "sharedUI/build.gradle.kts must not depend on platform modules: $hits"
        }
    }

    @Test
    fun `sharedUI must depend on sharedLogic`() {
        val gradle = sharedUiBuild.readText()
        check("""project(":sharedLogic")""" in gradle) {
            "sharedUI/build.gradle.kts must depend on :sharedLogic"
        }
    }
}
