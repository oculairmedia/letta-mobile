package com.letta.mobile.docs

import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.tasks.Copy
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinBaseExtension
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

/** Compiler-derived public signatures for every module included in API documentation. */
class ApiContractPlugin : Plugin<Project> {
    @OptIn(ExperimentalAbiValidation::class)
    override fun apply(project: Project) {
        listOf("org.jetbrains.kotlin.multiplatform", "org.jetbrains.kotlin.jvm").forEach { plugin ->
            project.pluginManager.withPlugin(plugin) {
                val abi = project.extensions.getByType(KotlinBaseExtension::class.java).abiValidation
                abi.referenceDumpDir.set(project.layout.projectDirectory.dir("api"))
                project.tasks.register("apiCheck") {
                    group = "verification"
                    description = "Compare public API with the reviewed, checked-in compiler dump."
                    dependsOn(abi.checkTaskProvider)
                }
                project.tasks.register("apiDump") {
                    group = "documentation"
                    description = "Update public API dumps for review alongside package overviews."
                    dependsOn(abi.updateTaskProvider)
                }
            }
        }
        project.pluginManager.withPlugin("com.android.kotlin.multiplatform.library") {
            configureAndroid(project)
        }
    }

    private fun configureAndroid(project: Project) {
        val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
        kotlin.targets.withType(KotlinMultiplatformAndroidLibraryTarget::class.java).configureEach {
            compilations.named("main") {
                val compiled = output.classesDirs
                val dump = project.tasks.register("androidApiDump", AndroidApiDumpTask::class.java) {
                    classes.from(compiled)
                    dumpFile.set(project.layout.buildDirectory.file("api-contract/android.api"))
                }
                val check = project.tasks.register("androidApiCheck", AndroidApiCheckTask::class.java) {
                    actualFile.set(dump.flatMap { it.dumpFile })
                    referenceFile.set(project.layout.projectDirectory.file("api-android/android.api"))
                }
                val update = project.tasks.register("updateAndroidApi", Copy::class.java) {
                    from(dump.flatMap { it.dumpFile })
                    into(project.layout.projectDirectory.dir("api-android"))
                }
                project.tasks.named("apiCheck") { dependsOn(check) }
                project.tasks.named("apiDump") { dependsOn(update) }
            }
        }
    }
}
