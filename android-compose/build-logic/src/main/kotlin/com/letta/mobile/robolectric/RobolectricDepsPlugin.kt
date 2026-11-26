package com.letta.mobile.robolectric

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import org.gradle.process.CommandLineArgumentProvider

/**
 * `com.letta.mobile.robolectric-deps`: resolves Robolectric's pre-instrumented Android framework
 * jars through Gradle instead of letting Robolectric download them while the tests run.
 *
 * Robolectric's own downloader has no retry and no repository fallback, so one failed request from a
 * shared CI address turned every Robolectric class into an `initializationError`. Gradle resolves the
 * same jars with its retry budget, the Maven Central mirror and the dependency cache CI restores.
 *
 * Every `robolectric-android-all-*` entry of the version catalog gets its own `robolectricAndroidAll*`
 * configuration. [WriteRobolectricDepsTask] records where Gradle put each jar, and every test task of
 * the module passes that file to Robolectric as `robolectric-deps.properties`. Robolectric then maps
 * an SDK level straight to its jar and never touches the network; an SDK missing from the catalog
 * fails with "no artifacts found" rather than falling back to a live download.
 */
class RobolectricDepsPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val catalog = project.extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
        // One configuration per SDK: every entry shares the module android-all-instrumented, so in a
        // single configuration Gradle's conflict resolution would keep only the highest version.
        val resolved = catalog.androidAllAliases().map { alias ->
            project.configurations.create("$CONFIGURATION_PREFIX${alias.removePrefix(ALIAS_PREFIX)}") {
                isCanBeConsumed = false
                isCanBeResolved = true
                isTransitive = false
                description = "Robolectric android-all-instrumented jar for $alias."
            }.also { project.dependencies.add(it.name, catalog.findLibrary(alias).get()) }
        }
        val androidAll = project.files(resolved)
        val write = project.tasks.register(TASK, WriteRobolectricDepsTask::class.java) {
            group = "verification"
            description = "Writes robolectric-deps.properties mapping each SDK jar to its Gradle-resolved file."
            resolved.forEach { configuration ->
                artifacts.putAll(
                    configuration.incoming.artifacts.resolvedArtifacts.map { jars ->
                        jars.associate { artifact ->
                            val id = artifact.id.componentIdentifier as ModuleComponentIdentifier
                            "${id.group}:${id.module}:${id.version}" to artifact.file.absolutePath
                        }
                    },
                )
            }
            propertiesFile.set(project.layout.buildDirectory.file("robolectric/$PROPERTIES_FILE"))
        }
        project.tasks.withType(Test::class.java).configureEach {
            dependsOn(write)
            jvmArgumentProviders.add(RobolectricDepsArguments(androidAll, write.flatMap { it.propertiesFile }))
        }
    }

    private fun VersionCatalog.androidAllAliases(): List<String> =
        libraryAliases.filter { it.startsWith(ALIAS_PREFIX) }.sorted()

    private companion object {
        const val CONFIGURATION_PREFIX = "robolectricAndroidAll"
        const val TASK = "writeRobolectricDeps"
        const val PROPERTIES_FILE = "robolectric-deps.properties"
        const val ALIAS_PREFIX = "robolectric.android.all."
    }
}

/**
 * Points Robolectric at [propertiesFile]. The jars are the declared input, so a changed Android
 * framework invalidates cached test results; the file's own location is not part of the cache key.
 */
internal class RobolectricDepsArguments(
    @get:InputFiles @get:PathSensitive(PathSensitivity.NAME_ONLY) val androidAll: FileCollection,
    @get:Internal val propertiesFile: Provider<RegularFile>,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> =
        listOf("-D$SYSTEM_PROPERTY=${propertiesFile.get().asFile.absolutePath}")

    private companion object {
        /** The system property Robolectric's dependency resolver reads (`LegacyDependencyResolver`). */
        const val SYSTEM_PROPERTY = "robolectric-deps.properties"
    }
}
