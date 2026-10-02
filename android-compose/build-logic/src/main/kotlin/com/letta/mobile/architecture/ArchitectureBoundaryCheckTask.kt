package com.letta.mobile.architecture

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails the build when the exported module graph breaks a boundary rule that
 * the checked-in baseline does not name, or when a baseline line is stale.
 */
abstract class ArchitectureBoundaryCheckTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val graphFile: RegularFileProperty

    /** Optional on disk: a missing baseline means "no tolerated violations". */
    @get:Internal
    abstract val baselineFile: RegularFileProperty

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    init {
        // The baseline may legitimately be absent, so it is not an @InputFile;
        // never let a stale up-to-date result skip the gate.
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun check() {
        val result = try {
            ArchitectureBoundaryGate.evaluate(
                ArchitectureGraphReader.read(graphFile.get().asFile),
                ArchitectureBoundaryBaseline.read(baselineFile.get().asFile),
            )
        } catch (failure: ArchitectureGraphException) {
            throw GradleException(failure.message.orEmpty(), failure)
        }
        val report = result.report()
        reportFile.get().asFile.apply { parentFile.mkdirs() }.writeText(report)
        if (!result.passed) throw GradleException(report)
        logger.lifecycle(report)
    }
}
