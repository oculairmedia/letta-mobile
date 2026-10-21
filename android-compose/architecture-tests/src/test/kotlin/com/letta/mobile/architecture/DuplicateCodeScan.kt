package com.letta.mobile.architecture

import net.sourceforge.pmd.cpd.CPDConfiguration
import net.sourceforge.pmd.cpd.CpdAnalysis
import net.sourceforge.pmd.lang.LanguageRegistry
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile

/**
 * Cross-module duplicate Kotlin (letta-mobile-ewtaq), found by PMD CPD over every production source
 * set. A clone whose copies sit in two different Gradle modules is the reimplementation the owner
 * rule forbids: the code belongs once in sharedLogic/sharedUI. The result is keyed by file pair
 * (`<fileA>|<fileB>`, repo-relative, sorted) and counts the lines duplicated between the two, which
 * stays stable while unrelated code moves around the files.
 */
internal object DuplicateCodeScan {
    /** CPD's default: about ten lines of Kotlin. Lowering it is fine; raising it hides clones. */
    const val MINIMUM_TOKENS = 100

    private val excludedModules = setOf("android-compose/architecture-tests", "android-compose/build-logic")

    fun productionSources(repositoryRoot: Path): List<Path> {
        val compose = repositoryRoot.resolve("android-compose")
        return Files.walk(compose).use { paths ->
            paths.filter { it.isRegularFile() && it.extension == "kt" }
                .filter { JsonInstanceScan.isProductionSource(repositoryRoot.relativize(it).invariantSeparatorsPathString) }
                .filter { path -> excludedModules.none { repositoryRoot.relativize(path).invariantSeparatorsPathString.startsWith("$it/") } }
                .sorted()
                .toList()
        }
    }

    /** The Gradle module directory holding [file]: its nearest ancestor with a build script. */
    fun moduleOf(repositoryRoot: Path, file: Path): String {
        var dir: Path? = file.parent
        while (dir != null && dir.startsWith(repositoryRoot)) {
            if (dir.resolve("build.gradle.kts").exists()) return repositoryRoot.relativize(dir).invariantSeparatorsPathString
            dir = dir.parent
        }
        return ""
    }

    /** `<fileA>|<fileB>` -> lines duplicated between two files in different modules. */
    fun crossModuleDuplicates(repositoryRoot: Path, sources: List<Path>, minimumTokens: Int = MINIMUM_TOKENS): Map<String, Int> {
        val config = CPDConfiguration().apply {
            minimumTileSize = minimumTokens
            setOnlyRecognizeLanguage(LanguageRegistry.CPD.getLanguageById("kotlin"))
            inputPathList = sources
            isSkipDuplicates = false
        }
        val pairs = sortedMapOf<String, Int>()
        CpdAnalysis.create(config).use { analysis ->
            analysis.performAnalysis { report ->
                for (match in report.matches) {
                    val marks = match.markSet.map { mark ->
                        val location = mark.location
                        val path = Path.of(location.fileId.absolutePath)
                        Triple(repositoryRoot.relativize(path).invariantSeparatorsPathString, moduleOf(repositoryRoot, path), location.endLine - location.startLine + 1)
                    }
                    for (i in marks.indices) {
                        for (j in i + 1 until marks.size) {
                            val (a, moduleA, linesA) = marks[i]
                            val (b, moduleB, linesB) = marks[j]
                            if (moduleA == moduleB) continue
                            val key = listOf(a, b).sorted().joinToString("|")
                            pairs[key] = (pairs[key] ?: 0) + minOf(linesA, linesB)
                        }
                    }
                }
            }
        }
        return pairs
    }
}
