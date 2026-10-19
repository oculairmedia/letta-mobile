package com.letta.mobile.docs

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.abi.tools.AbiFilters
import org.jetbrains.kotlin.abi.tools.AbiTools

/** Android KMP companion until the repository's Kotlin version supports AGP 9 ABI extraction. */
@CacheableTask
abstract class AndroidApiDumpTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classes: ConfigurableFileCollection

    @get:OutputFile
    abstract val dumpFile: RegularFileProperty

    @TaskAction
    fun dump() {
        val inputs = classes.asFileTree.files.filter { it.extension == "class" }.sortedBy { it.path }
        if (inputs.isEmpty()) throw GradleException("Android API extraction received no compiled classes")
        val output = dumpFile.get().asFile
        output.parentFile.mkdirs()
        output.bufferedWriter(Charsets.UTF_8).use {
            AbiTools.getInstance().printJvmDump(it, inputs, AbiFilters.EMPTY)
        }
    }
}

/** A missing or changed Android signature snapshot fails; checking never rewrites it. */
abstract class AndroidApiCheckTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val referenceFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val actualFile: RegularFileProperty

    @TaskAction
    fun check() {
        val diff = AbiTools.getInstance().filesDiff(referenceFile.get().asFile, actualFile.get().asFile)
        if (diff != null) throw GradleException("Android API changed. Review Module.md and run apiDump.\n$diff")
    }
}
