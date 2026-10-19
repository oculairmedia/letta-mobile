package com.letta.mobile.architecture

import com.lemonappdev.konsist.api.Konsist
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import java.nio.file.Path
import org.junit.jupiter.api.Test

/** Protects the explicit driver-provider seam; distribution assembly may select process executors. */
class PluginExecutorIsolationTest {
    private val projectRoot = Path.of(requireNotNull(System.getProperty("architecture.projectRoot")))

    @Test
    fun `core plugin host does not import concrete process executors`() {
        val hostSources = listOf("commonMain", "jvmMain").map { sourceSet ->
            projectRoot.resolve("android-compose/sharedLogic/src/$sourceSet/kotlin/com/letta/mobile/data/plugin/host")
        }
        check(hostSources.all { it.toFile().isDirectory }) { "Missing host source roots: $hostSources" }
        val files = Konsist.scopeFromExternalDirectories(hostSources.map(Path::toString)).files.filter { file ->
            val name = file.packagee?.name.orEmpty()
            (name == HOST || name.startsWith("$HOST.")) && name != PROCESS && !name.startsWith("$PROCESS.")
        }
        check(hostSources.all { root -> files.any { Path.of(it.path).startsWith(root) } }) {
            "The executor boundary must scan core host sources under both $hostSources"
        }
        val violations = files.flatMap { file ->
            file.imports.filter { it.name == PROCESS || it.name.startsWith("$PROCESS.") }
                .map { "${file.path}: core host must not import concrete executor ${it.name}" }
        }
        check(violations.isEmpty()) { violations.joinToString("\n") }
    }

    @Test
    fun `core host bytecode does not depend on concrete executors or process configuration`() {
        val classesDir = projectRoot.resolve("android-compose/sharedLogic/build/classes/kotlin/jvm/main")
        check(classesDir.toFile().isDirectory) { "Compile :sharedLogic:jvmMainClasses before this check" }
        val classes = ClassFileImporter()
            .withImportOption { location ->
                location.toString().replace('\\', '/').contains("/com/letta/mobile/data/plugin/host/")
            }
            .importPaths(listOf(classesDir))
        val required = setOf("$HOST.PluginDriverHost", "$HOST.PluginHostConfig", "$PROCESS.ProcessPluginDriver")
        check(classes.map { it.name }.toSet().containsAll(required)) {
            "Missing compiled plugin host/process classes; the executor boundary would check nothing"
        }

        noClasses().that().resideInAPackage("$HOST..")
            .and().resideOutsideOfPackage("$PROCESS..")
            .should().dependOnClassesThat().resideInAPackage("$PROCESS..")
            .because("only distribution assembly selects a concrete driver provider")
            .allowEmptyShould(false)
            .check(classes)

        for (concrete in listOf("ProcessDriverOptions", "ProcessPluginDriver")) {
            noClasses().that().haveFullyQualifiedName("$HOST.PluginDriverHost")
                .or().haveFullyQualifiedName("$HOST.PluginHostConfig")
                .should().dependOnClassesThat().haveSimpleName(concrete)
                .because("host lifecycle/configuration depend on the provider inventory, not an executor")
                .allowEmptyShould(false)
                .check(classes)
        }
    }

    private companion object {
        const val HOST = "com.letta.mobile.data.plugin.host"
        const val PROCESS = "$HOST.process"
    }
}
