package com.letta.mobile.jni

import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

/**
 * Runs `so-sanity-check.sh <readelf> <so>` on every file under [jniLibsDirectory] after
 * realignment (see [Jni16KbPlugin]) and fails the build when any .so lacks the dynamic tables the
 * linker needs. The .so files are left in place for inspection; each one's script output is kept
 * in [reportDirectory]. A missing llvm-readelf is itself a failure, never a skipped check.
 */
@DisableCachingByDefault(because = "Verification only; produces no outputs.")
abstract class VerifyJniLibSanityTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:InputFile
    abstract val script: RegularFileProperty

    @get:Internal
    abstract val jniLibsDirectory: DirectoryProperty

    @get:InputFiles
    abstract val sharedObjects: ConfigurableFileCollection

    /** Per-.so script output (one file each, so no failure's diagnostics overwrite another's). */
    @get:Internal
    abstract val reportDirectory: DirectoryProperty

    @TaskAction
    fun verify() {
        val readelf = locateReadelf()
        val failures = jniLibsDirectory.get().asFileTree.filter(File::isFile)
            .mapNotNull { soFile -> checkOne(readelf, soFile) }
        if (failures.isNotEmpty()) throw GradleException(failureMessage(readelf, failures))
    }

    private fun locateReadelf(): String {
        val locator = ReadelfLocator(execOperations)
        return locator.locate() ?: throw GradleException(
            "[verifyJniLibSanity] llvm-readelf not found. Tried: " +
                locator.ndkCandidates.joinToString(", ") +
                ", and PATH lookup for 'llvm-readelf'/'readelf'. " +
                "Set ANDROID_NDK_HOME to your NDK install root (e.g. " +
                "/opt/android-sdk/ndk/28.2.13676358) or add llvm-readelf " +
                "to PATH. Without readelf we cannot enforce the " +
                "DT_HASH/DT_GNU_HASH fire #53 guard.",
        )
    }

    /** null when [soFile] passes, otherwise its failure line. */
    private fun checkOne(readelf: String, soFile: File): Failure? {
        val reports = reportDirectory.get().asFile.apply { mkdirs() }
        val out = reports.resolve("${soFile.name}.txt")
        val err = reports.resolve("${soFile.name}.err.txt")
        val result = try {
            out.outputStream().use { stream ->
                execOperations.exec {
                    commandLine(script.get().asFile.absolutePath, readelf, soFile.absolutePath)
                    isIgnoreExitValue = true
                    standardOutput = stream
                    errorOutput = stream
                }.exitValue
            }
        } catch (e: Exception) {
            // Spawn failures (script missing, not executable): keep WHY for the build log.
            err.writeText("Exception type: ${e.javaClass.name}\nMessage: ${e.message}\nStack trace:\n${e.stackTraceToString()}\n")
            -1
        }
        if (result == 0) return null
        val detail = when {
            out.exists() && out.length() > 0 -> out.readText().trim()
            err.exists() -> "SCRIPT FAILED TO SPAWN: ${err.readText().trim()}"
            else -> "(no output captured)"
        }
        return Failure(soFile.name, "${soFile.name} (exit=$result): $detail")
    }

    private fun failureMessage(readelf: String, failures: List<Failure>): String =
        "[verifyJniLibSanity] the following .so files are missing " +
            "DT_HASH/DT_GNU_HASH/DT_STRTAB/DT_SYMTAB/DT_DYNAMIC and will " +
            "fail to dlopen() at runtime:\n  " + failures.joinToString("\n  ") { it.name } +
            "\n\nScript stderr:\n  " + failures.joinToString("\n  ") { it.detail } +
            "\n\nThis is the bookend check to check-elf-16kb-alignment.sh: " +
            "the alignment is OK, but the dynamic linker needs the hash + " +
            "strtab + symtab + dynamic tables to load the .so. Re-run " +
            "realign-16kb.py with RUST_BACKTRACE=1 to find the bug, or " +
            "revert to the pre-realign .so on build host.\n\nTo skim just the " +
            "problem, run on the host:\n  $readelf -d <path-to-.so>"

    private class Failure(val name: String, val detail: String)
}
