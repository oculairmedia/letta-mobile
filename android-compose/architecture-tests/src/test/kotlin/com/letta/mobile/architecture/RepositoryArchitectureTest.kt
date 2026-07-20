package com.letta.mobile.architecture

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.library.Architectures.layeredArchitecture
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import org.junit.jupiter.api.Test
import java.nio.file.Path

class RepositoryArchitectureTest {
    private val projectRoot: Path = Path.of(requireNotNull(System.getProperty("architecture.projectRoot")))

    @Test
    fun `production Kotlin follows source and KMP conventions`() {
        val violations = KotlinSourcePolicy.violations(KotlinSourcePolicy.productionScope(projectRoot))
        check(violations.isEmpty()) { violations.joinToString(separator = "\n") }
    }

    @Test
    fun `JVM core bytecode follows layer and cycle rules`() {
        val coreClasses = importCoreClasses(projectRoot)
        val violations = repositoryBytecodeRules()
            .flatMap { rule -> rule.evaluate(coreClasses).failureReport.details }

        check(violations.isEmpty()) { violations.joinToString(separator = "\n") }
    }
}

internal fun repositoryBytecodeRules(): List<ArchRule> = listOf(
    slices()
        .matching("com.letta.mobile.(**)..")
        .should().beFreeOfCycles()
        .allowEmptyShould(true),
    layeredArchitecture()
        .consideringOnlyDependenciesInLayers()
        .layer("Model").definedBy("com.letta.mobile.data.model..")
        .layer("RepositoryApi").definedBy("com.letta.mobile.data.repository.api..")
        .whereLayer("Model").mayNotAccessAnyLayer()
        .whereLayer("RepositoryApi").mayOnlyAccessLayers("Model")
        .allowEmptyShould(true),
)

private fun importCoreClasses(projectRoot: Path): JavaClasses {
    val classDirectories = sequenceOf("core/ids", "core/domain")
        .map { projectRoot.resolve("android-compose/$it/build/classes") }
        .filter { it.toFile().exists() }
        .toList()

    check(classDirectories.isNotEmpty()) {
        "Compile :core:ids:jvmMainClasses and :core:domain:classes before running architectureTest"
    }

    return ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .withImportOption(ExcludeGeneratedClasses)
        .importPaths(classDirectories)
}

private object ExcludeGeneratedClasses : ImportOption {
    override fun includes(location: com.tngtech.archunit.core.importer.Location): Boolean {
        val path = location.toString().replace('\\', '/')
        return listOf("/build/generated/", "/generated/", "/ksp/").none(path::contains)
    }
}
