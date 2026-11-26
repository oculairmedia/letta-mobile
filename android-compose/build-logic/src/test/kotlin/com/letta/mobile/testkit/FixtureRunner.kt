package com.letta.mobile.testkit

import java.io.File
import org.gradle.testkit.runner.GradleRunner

/**
 * Wires a TestKit [GradleRunner] to the dependency cache of the build that runs the tests.
 *
 * A TestKit build starts with an empty Gradle user home and declares its own repositories, so it
 * inherits neither the parent build's dependency cache nor its retry settings: on a fresh CI runner
 * every fixture downloaded the Kotlin, Dokka and Android artifacts live, and one failed request
 * failed the test. Gradle's read-only dependency cache (`GRADLE_RO_DEP_CACHE`) serves whatever the
 * parent build already resolved, and the repository retry budget matches the parent's
 * `gradle.properties`. Nothing is skipped: a missing artifact is still fetched from the repository.
 */
object FixtureRunner {
    /** Test task system property holding the Gradle user home of the build running these tests. */
    private const val USER_HOME_PROPERTY = "letta.gradle.user.home"
    private const val RO_CACHE_ENV = "GRADLE_RO_DEP_CACHE"
    private const val DEPENDENCY_CACHE_DIR = "caches"
    private const val RETRY_ATTEMPTS = 5
    private const val RETRY_BACKOFF_MS = 2000
    private const val HTTP_TIMEOUT_MS = 60_000

    /** Retry budget copied from `android-compose/gradle.properties` (the fixtures never read that file). */
    private val repositoryResilience = listOf(
        "-Dorg.gradle.internal.repository.max.tentatives=$RETRY_ATTEMPTS",
        "-Dorg.gradle.internal.repository.initial.backoff=$RETRY_BACKOFF_MS",
        "-Dorg.gradle.internal.http.connectionTimeout=$HTTP_TIMEOUT_MS",
        "-Dorg.gradle.internal.http.socketTimeout=$HTTP_TIMEOUT_MS",
    )

    /** A runner for [projectDir] that runs [arguments] with the shared cache and retry budget. */
    fun create(projectDir: File, arguments: List<String>): GradleRunner {
        val runner = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(arguments + repositoryResilience)
        val cache = System.getProperty(USER_HOME_PROPERTY)?.let { File(it, DEPENDENCY_CACHE_DIR) }
        return if (cache != null && cache.resolve("modules-2").isDirectory) {
            runner.withEnvironment(System.getenv() + (RO_CACHE_ENV to cache.absolutePath))
        } else {
            runner
        }
    }
}
