package com.letta.mobile.jni

import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFiles
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

/**
 * Runs `realign-16kb.py` on every file under [jniLibsDirectory] (see [Jni16KbPlugin]).
 *
 * Falls back gracefully: without Python 3 on PATH it warns and leaves the prebuilt .so files
 * alone, and a failed realignment of one file is a warning, not a build failure.
 */
@DisableCachingByDefault(because = "Rewrites the vendored .so files in place.")
abstract class Realign16KbJniLibsTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:InputFile
    abstract val script: RegularFileProperty

    @get:Internal
    abstract val jniLibsDirectory: DirectoryProperty

    /** Every .so as input and output, so a content change re-runs the task. */
    @get:InputFiles
    abstract val sharedObjects: ConfigurableFileCollection

    @get:OutputFiles
    abstract val realignedObjects: ConfigurableFileCollection

    @TaskAction
    fun realign() {
        val python = PYTHON_CANDIDATES.firstOrNull { execOperations.exitCode(listOf(it, "-c", "import sys; sys.exit(0)")) == 0 }
        if (python == null) {
            logger.warn(
                "[realign16KbJniLibs] Python 3 not found on PATH; skipping 16 KB realignment. " +
                    "Prebuilt .so files in src/rootDebug/jniLibs/ will keep their 4 KB alignment, " +
                    "and app-play-*.apk will be flagged by Play Console.",
            )
            return
        }
        jniLibsDirectory.get().asFileTree.filter(File::isFile).forEach { soFile -> realignOne(python, soFile) }
    }

    private fun realignOne(python: String, soFile: File) {
        val result = execOperations.exitCode(listOf(python, script.get().asFile.absolutePath, soFile.absolutePath))
        if (result != 0) {
            logger.warn(
                "[realign16KbJniLibs] realignment failed for ${soFile.name} (exit $result); " +
                    "the prebuilt .so will keep its original alignment.",
            )
        }
    }

    private companion object {
        val PYTHON_CANDIDATES = listOf("python3", "python", "py")
    }
}
