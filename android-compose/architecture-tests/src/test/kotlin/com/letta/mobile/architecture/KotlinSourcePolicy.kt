package com.letta.mobile.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.container.KoScope
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString

internal object KotlinSourcePolicy {
    private val forbiddenCommonMainImports = listOf(
        "android.",
        "java.",
        "javax.",
    )

    private val generatedPathSegments = listOf(
        "/build/",
        "/generated/",
        "/ksp/",
    )

    fun productionScope(projectRoot: Path): KoScope {
        val roots = projectRoot.resolve("android-compose")
            .toFile()
            .walkTopDown()
            .filter { file ->
                file.isDirectory &&
                    file.name.endsWith("Main") &&
                    file.parentFile?.name == "src" &&
                    !isGenerated(file.toPath())
            }
            .map { it.absolutePath }
            .toList()

        return Konsist.scopeFromExternalDirectories(roots)
            .slice { !isGenerated(Path.of(it.path)) }
    }

    fun violations(scope: KoScope): List<String> = buildList {
        scope.files.forEach { file ->
            if (file.packagee?.name?.startsWith("com.letta.mobile") != true) {
                add("${file.path}: production Kotlin package must start with com.letta.mobile")
            }
            if (isCommonMain(file)) {
                file.imports
                    .filter { import -> forbiddenCommonMainImports.any(import.name::startsWith) }
                    .forEach { import -> add("${file.path}: commonMain imports ${import.name}") }
            }
        }
    }

    private fun isCommonMain(file: KoFileDeclaration): Boolean {
        val path = file.path.replace('\\', '/')
        return path.contains("/commonMain/") || file.sourceSetName == "commonMain"
    }

    private fun isGenerated(path: Path): Boolean {
        val normalized = "/${path.toAbsolutePath().normalize().invariantSeparatorsPathString}/"
        return generatedPathSegments.any(normalized::contains)
    }
}
