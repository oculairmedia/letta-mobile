package com.letta.mobile.architecture

/** A forbidden module edge, independent of which configuration declared it. */
internal data class BoundaryViolation(val rule: String, val from: String, val to: String) {
    override fun toString(): String = "$rule $from -> $to"
}

/**
 * One module-boundary rule over a declared project edge.
 *
 * Rules see edges from every configuration (implementation, test, ksp, kover,
 * baselineProfile, ...), so a test-only or tooling edge is still an edge.
 */
internal class BoundaryRule(
    val id: String,
    val description: String,
    private val forbids: (GraphEdge, ArchitectureGraph) -> Boolean,
) {
    fun violatedBy(edge: GraphEdge, graph: ArchitectureGraph): Boolean = forbids(edge, graph)
}

internal object ModuleRoles {
    /** Platform entry points: the Android app, desktop, web, and the JVM command-line tools. */
    val SHELLS = setOf(":app", ":desktop", ":web", ":cli", ":appserver-cli", ":iroh-wrapper-cli")

    private val MULTIPLATFORM_KINDS = setOf("android-kmp-library", "kotlin-multiplatform")

    fun isShell(path: String): Boolean = path in SHELLS

    fun isFeature(path: String): Boolean = path.startsWith(":feature-") || path.startsWith(":feature:")

    fun isMultiplatform(kind: String): Boolean = kind in MULTIPLATFORM_KINDS

    /**
     * `com.android.test` modules (macrobenchmark, baselineprofile) instrument the
     * built APK through `testedApks`; targeting :app is their whole purpose.
     */
    fun isApkTestHarness(kind: String): Boolean = kind == "android-test"
}

internal object ArchitectureBoundaryRules {
    /** The root project aggregates every module (kover, detekt) and is not part of the module graph. */
    private const val ROOT = ":"

    val ALL: List<BoundaryRule> = listOf(
        BoundaryRule("into-app", "nothing but an APK test harness (com.android.test) may depend on the :app shell") { edge, graph ->
            edge.to == ":app" && !ModuleRoles.isApkTestHarness(graph.kindOf(edge.from))
        },
        BoundaryRule("shell-to-shell", "a shell may not depend on another shell") { edge, _ ->
            ModuleRoles.isShell(edge.from) && ModuleRoles.isShell(edge.to)
        },
        BoundaryRule("feature-to-feature", "a feature module may not depend on another feature module") { edge, _ ->
            ModuleRoles.isFeature(edge.from) && ModuleRoles.isFeature(edge.to)
        },
        BoundaryRule("library-to-feature", "only shells may depend on feature modules") { edge, _ ->
            ModuleRoles.isFeature(edge.to) && !ModuleRoles.isFeature(edge.from) && !ModuleRoles.isShell(edge.from)
        },
        BoundaryRule("multiplatform-to-single-platform", "a multiplatform module may only depend on multiplatform modules") { edge, graph ->
            ModuleRoles.isMultiplatform(graph.kindOf(edge.from)) && !ModuleRoles.isMultiplatform(graph.kindOf(edge.to))
        },
    )

    fun violations(graph: ArchitectureGraph, rules: List<BoundaryRule> = ALL): Set<BoundaryViolation> =
        graph.edges
            .filter { edge -> edge.from != ROOT && edge.from != edge.to }
            .flatMap { edge ->
                rules.filter { rule -> rule.violatedBy(edge, graph) }
                    .map { rule -> BoundaryViolation(rule.id, edge.from, edge.to) }
            }
            .toSortedSet(compareBy({ it.rule }, { it.from }, { it.to }))
}
