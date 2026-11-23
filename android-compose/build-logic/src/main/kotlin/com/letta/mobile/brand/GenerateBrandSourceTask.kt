package com.letta.mobile.brand

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * Writes the runtime `Brand` object ([BrandIdentity.kotlinSource]) into [outputDirectory], which a
 * module adds as a Kotlin source directory. The source text is the task input, so a rename in
 * `branding/brand.properties` regenerates it and nothing else.
 */
@CacheableTask
abstract class GenerateBrandSourceTask : DefaultTask() {
    @get:Input
    abstract val packageName: Property<String>

    @get:Input
    abstract val source: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val root = outputDirectory.get().asFile
        root.deleteRecursively()
        val file = root.resolve(packageName.get().replace('.', '/')).resolve("Brand.kt")
        file.parentFile.mkdirs()
        file.writeText(source.get())
    }
}
