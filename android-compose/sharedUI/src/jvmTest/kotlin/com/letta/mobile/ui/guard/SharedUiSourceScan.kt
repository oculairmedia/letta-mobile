package com.letta.mobile.ui.guard

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

/** Reads sharedUI's production sources for the source-scan guard tests. */
object SharedUiSourceScan {
    private val SOURCE_SETS = listOf("commonMain", "jvmMain", "androidMain", "skikoMain", "wasmJsMain")

    /** File name -> what [find] reported in it, for every production file except [skip] with a hit. */
    fun scan(skip: Set<String> = emptySet(), find: (String) -> List<String>): Map<String, List<String>> {
        val sharedUi = locateSharedUiDir()
        val roots = SOURCE_SETS.map { sharedUi.resolve("src/$it/kotlin") }.filter { Files.isDirectory(it) }
        check(roots.isNotEmpty()) { "sharedUI production sources not found under $sharedUi" }
        val hits = mutableMapOf<String, List<String>>()
        roots.forEach { root ->
            Files.walk(root).use { stream ->
                stream.filter { it.isRegularFile() && it.name.endsWith(".kt") && it.name !in skip }.forEach { file ->
                    val found = find(file.readText())
                    if (found.isNotEmpty()) hits[file.name] = found
                }
            }
        }
        return hits
    }

    private fun locateSharedUiDir(): Path {
        var candidate: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (candidate != null) {
            if (candidate.fileName?.toString() == "sharedUI") return candidate
            val direct = candidate.resolve("sharedUI")
            if (Files.isDirectory(direct)) return direct
            val nested = candidate.resolve("android-compose/sharedUI")
            if (Files.isDirectory(nested)) return nested
            candidate = candidate.parent
        }
        error("Unable to locate sharedUI from ${System.getProperty("user.dir")}")
    }
}
