package com.letta.mobile.architecture

import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

class ArchitectureGraphPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        require(project == project.rootProject) {
            "The architecture graph plugin must be applied to the root project."
        }

        val androidVariants = sortedSetOf(compareBy<Pair<String, String>>({ it.first }, { it.second }))
        project.allprojects.forEach { candidate ->
            candidate.pluginManager.withPlugin("com.android.application") {
                captureAndroidVariants(candidate, androidVariants)
            }
            candidate.pluginManager.withPlugin("com.android.library") {
                captureAndroidVariants(candidate, androidVariants)
            }
            candidate.pluginManager.withPlugin("com.android.kotlin.multiplatform.library") {
                captureAndroidVariants(candidate, androidVariants)
            }
        }

        val exportTask = project.tasks.register("exportArchitectureGraph", ArchitectureGraphTask::class.java) {
            group = "reporting"
            description = "Exports the configured Gradle, KMP, and Android architecture as deterministic JSONL."
            outputFile.convention(project.layout.buildDirectory.file("reports/architecture/graph.jsonl"))
            contractDirectory.convention(project.layout.buildDirectory.dir("reports/architecture/contract"))
        }

        project.gradle.projectsEvaluated {
            exportTask.configure {
                records.set(collectRecords(project, androidVariants))
            }
        }
    }

    private fun captureAndroidVariants(
        project: Project,
        variants: MutableSet<Pair<String, String>>,
    ) {
        project.extensions.findByType(AndroidComponentsExtension::class.java)
            ?.onVariants { variant -> variants += project.path to variant.name }
    }

    private fun collectRecords(
        root: Project,
        androidVariants: Set<Pair<String, String>>,
    ): List<String> = buildList {
        root.allprojects.sortedBy(Project::getPath).forEach { project ->
            add(
                JsonLine.record(
                    "module",
                    "path" to project.path,
                    "directory" to project.projectDir.relativeTo(root.projectDir).invariantSeparatorsPath.ifEmpty { "." },
                    "kind" to projectKind(project),
                ),
            )

            project.extensions.findByType(KotlinMultiplatformExtension::class.java)?.let { kotlin ->
                kotlin.targets.sortedBy { it.name }.forEach { target ->
                    add(JsonLine.record("target", "module" to project.path, "name" to target.name, "platform" to target.platformType.name))
                }
                kotlin.sourceSets.sortedBy { it.name }.forEach { sourceSet ->
                    add(JsonLine.record("sourceSet", "module" to project.path, "name" to sourceSet.name))
                    sourceSet.dependsOn.sortedBy { it.name }.forEach { parent ->
                        add(
                            JsonLine.record(
                                "sourceSetEdge",
                                "module" to project.path,
                                "from" to sourceSet.name,
                                "to" to parent.name,
                            ),
                        )
                    }
                }
            }

            project.configurations.sortedBy { it.name }
                .filter { it.dependencies.isNotEmpty() && isArchitectureConfiguration(it.name) }
                .forEach { configuration ->
                configuration.dependencies.sortedWith(compareBy({ it.group.orEmpty() }, { it.name }, { it.version.orEmpty() }))
                    .forEach { dependency ->
                        when (dependency) {
                            is ProjectDependency -> add(
                                JsonLine.record(
                                    "projectEdge",
                                    "from" to project.path,
                                    "to" to dependency.path,
                                    "configuration" to configuration.name,
                                ),
                            )
                            is ExternalModuleDependency -> add(
                                JsonLine.record(
                                    "externalDependency",
                                    "module" to project.path,
                                    "configuration" to configuration.name,
                                    "group" to dependency.group,
                                    "name" to dependency.name,
                                    "version" to dependency.version,
                                ),
                            )
                        }
                    }
            }
        }

        androidVariants.forEach { (path, variant) ->
            add(JsonLine.record("variant", "module" to path, "name" to variant, "platform" to "android"))
        }
    }

    private fun projectKind(project: Project): String = when {
        project.pluginManager.hasPlugin("com.android.application") -> "android-application"
        project.pluginManager.hasPlugin("com.android.library") -> "android-library"
        project.pluginManager.hasPlugin("com.android.kotlin.multiplatform.library") -> "android-kmp-library"
        project.pluginManager.hasPlugin("org.jetbrains.kotlin.multiplatform") -> "kotlin-multiplatform"
        project.pluginManager.hasPlugin("org.jetbrains.kotlin.jvm") -> "kotlin-jvm"
        project.pluginManager.hasPlugin("java-library") -> "java-library"
        project.pluginManager.hasPlugin("java") -> "java"
        else -> "gradle"
    }

    private fun isArchitectureConfiguration(name: String): Boolean =
        name == "api" ||
            name == "implementation" ||
            name == "compileOnly" ||
            name == "runtimeOnly" ||
            name == "ksp" ||
            name.endsWith("Api") ||
            name.endsWith("Implementation") ||
            name.endsWith("CompileOnly") ||
            name.endsWith("RuntimeOnly")
}
