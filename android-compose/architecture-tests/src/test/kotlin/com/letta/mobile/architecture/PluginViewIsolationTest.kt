package com.letta.mobile.architecture

import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * `:plugin-view` (letta-mobile-s416w.13) hosts live plugin pages for the shells. It is KMP and may
 * depend on `:sharedUI` and `:sharedLogic` only, so a shell binds it without pulling another
 * shell, a feature or an Android-only module into the desktop build.
 */
class PluginViewIsolationTest {
    private val projectRoot: Path = Path.of(requireNotNull(System.getProperty("architecture.projectRoot")))
    private val pluginViewBuild = projectRoot.resolve("android-compose/plugin-view/build.gradle.kts")

    @Test
    fun `plugin-view depends on sharedUI and sharedLogic only`() {
        val gradle = pluginViewBuild.readText()
        val declared = Regex("project\\(\"(:[^\"]+)\"\\)").findAll(gradle).map { it.groupValues[1] }.toSet()
        check(declared == setOf(":sharedUI", ":sharedLogic")) {
            "plugin-view/build.gradle.kts may depend on :sharedUI and :sharedLogic only, found $declared"
        }
        check(!Regex("projects\\.[A-Za-z]").containsMatchIn(gradle)) {
            "plugin-view/build.gradle.kts declares a project dependency through a type-safe accessor; use project(\":x\")"
        }
    }
}
