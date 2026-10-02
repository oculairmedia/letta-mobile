package com.letta.mobile.architecture

/**
 * Fast source-level check for project dependencies in a build script, in both
 * spellings: `project(":core:android-data")` and the type-safe accessor
 * `projects.core.androidData`. The authoritative check is the resolved-graph
 * gate (`./gradlew checkArchitectureBoundaries`, letta-mobile-o4ygk.1); this
 * one keeps the per-module isolation tests from passing on an accessor.
 */
internal object GradleProjectDependencyScan {
    fun hits(buildScript: String, forbiddenPaths: List<String>): List<String> =
        forbiddenPaths.filter { path -> declaresDependency(buildScript, path) }

    fun accessorFor(path: String): String =
        "projects." + path.trim(':').split(':').joinToString(".") { segment -> camelCase(segment) }

    private fun declaresDependency(buildScript: String, path: String): Boolean =
        "project(\"$path\")" in buildScript ||
            Regex(Regex.escape(accessorFor(path)) + "(?![A-Za-z0-9_.])").containsMatchIn(buildScript)

    private fun camelCase(segment: String): String =
        segment.split('-', '_').filter(String::isNotEmpty).mapIndexed { index, part ->
            if (index == 0) part else part.replaceFirstChar(Char::uppercaseChar)
        }.joinToString("")
}
