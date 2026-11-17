package com.letta.mobile.architecture

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.readText

/**
 * Finds production Kotlin files that construct their own kotlinx [Json] configuration
 * (letta-mobile-by4vm.20). Only `LettaJson.kt` may do that; everything else is either migrated to
 * a `LettaJson` instance or frozen in `json-instances-baseline.txt`.
 */
internal object JsonInstanceScan {
    const val CANONICAL_FILE =
        "android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/serialization/LettaJson.kt"

    /** `Json {`, `Json(from = x) {` and the fully qualified `kotlinx.serialization.json.Json {`. */
    private val construction = Regex("""(?<!class )(?:(?<![.\w])|(?<=serialization\.json\.))Json(\([^)]*\))?\s*\{""")
    private val commentPrefixes = listOf("//", "*", "/*")
    private val excludedSegments = listOf("/build/", "/generated/", "/ksp/")
    private val prunedDirectories = setOf("build", "generated", "ksp", ".gradle", ".kotlin", "node_modules")

    fun constructsJson(source: String): Boolean = source.lineSequence()
        .map(String::trim)
        .filterNot { line -> commentPrefixes.any(line::startsWith) }
        .any(construction::containsMatchIn)

    /** Test source sets are `src/<name>` where the name contains "test" (`commonTest`, `testFixtures`, ...). */
    fun isProductionSource(repoRelativePath: String): Boolean {
        if (excludedSegments.any { it in repoRelativePath }) return false
        val sourceSet = repoRelativePath.substringAfter("/src/", missingDelimiterValue = "")
            .substringBefore('/')
        return sourceSet.isNotEmpty() && !sourceSet.contains("test", ignoreCase = true)
    }

    /**
     * Every `.kt` file under `android-compose`, never descending into build output. Gradle tasks
     * running beside the test rewrite those trees, so walking them fails with
     * NoSuchFileException when a file vanishes mid-walk.
     */
    fun kotlinSources(repositoryRoot: Path): List<Path> {
        val found = mutableListOf<Path>()
        Files.walkFileTree(
            repositoryRoot.resolve("android-compose"),
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                    if (dir.fileName?.toString() in prunedDirectories) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile && file.extension == "kt") found.add(file)
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return found
    }

    /** Repo-relative paths (`android-compose/...`) of production files with an ad-hoc [Json]. */
    fun scan(repositoryRoot: Path): List<String> = kotlinSources(repositoryRoot)
        .map { repositoryRoot.relativize(it).invariantSeparatorsPathString }
        .filter { isProductionSource(it) && it != CANONICAL_FILE }
        .filter { constructsJson(repositoryRoot.resolve(it).readText()) }
        .sorted()
}
