package com.letta.mobile.architecture

import net.sourceforge.pmd.cpd.CPDConfiguration
import net.sourceforge.pmd.cpd.CpdAnalysis
import net.sourceforge.pmd.cpd.Match
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

    /** One copy of a clone: its file, its module and how many lines it spans. */
    private data class Copy(val file: String, val module: String, val lines: Int)

    private fun copiesOf(repositoryRoot: Path, match: Match): List<Copy> = match.markSet.map { mark ->
        val location = mark.location
        val path = Path.of(location.fileId.absolutePath)
        Copy(repositoryRoot.relativize(path).invariantSeparatorsPathString, moduleOf(repositoryRoot, path), location.endLine - location.startLine + 1)
    }

    /** Every pair of a clone's copies that sit in different modules, as (`<fileA>|<fileB>`, lines). */
    private fun crossModulePairs(copies: List<Copy>): List<Pair<String, Int>> = copies.flatMapIndexed { i, first ->
        copies.drop(i + 1)
            .filter { second -> second.module != first.module }
            .map { second -> listOf(first.file, second.file).sorted().joinToString("|") to minOf(first.lines, second.lines) }
    }

    /** `<fileA>|<fileB>` -> lines duplicated between two files in different modules. */
    fun crossModuleDuplicates(repositoryRoot: Path, sources: List<Path>, minimumTokens: Int = MINIMUM_TOKENS): Map<String, Int> {
        val config = CPDConfiguration().apply {
            minimumTileSize = minimumTokens
            setOnlyRecognizeLanguage(LanguageRegistry.CPD.getLanguageById("kotlin"))
            inputPathList = sources
        }
        val pairs = mutableListOf<Pair<String, Int>>()
        CpdAnalysis.create(config).use { analysis ->
            analysis.performAnalysis { report -> report.matches.forEach { pairs += crossModulePairs(copiesOf(repositoryRoot, it)) } }
        }
        return pairs.groupingBy { it.first }.fold(0) { total, pair -> total + pair.second }.toSortedMap()
    }
}
