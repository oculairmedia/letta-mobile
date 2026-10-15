package com.letta.mobile.architecture

/**
 * Package-level import graph for one module (letta-mobile-by4vm.27). A node is a package declared
 * by the scanned files; an edge A -> B means a file in A imports a symbol whose package is B. An
 * import resolves to the longest declared package that prefixes it, so nested-class, member and
 * wildcard imports land on their package, and imports of packages the module does not declare
 * (stdlib, other modules) are dropped.
 */
internal data class SourceFileImports(val packageName: String, val imports: List<String>)

internal object PackageImportGraph {
    /** Pairs of packages that import each other, as sorted `a <-> b` lines with a < b. */
    fun mutualPairs(files: List<SourceFileImports>): List<String> {
        val edges = edges(files)
        return edges
            .filter { (from, to) -> from < to && (to to from) in edges }
            .map { (from, to) -> "$from <-> $to" }
            .sorted()
    }

    private fun edges(files: List<SourceFileImports>): Set<Pair<String, String>> {
        val declared = files.map(SourceFileImports::packageName).toSet()
        return files.flatMap { file ->
            file.imports
                .mapNotNull { import -> owningPackage(import.removeSuffix(".*"), declared) }
                .filter { target -> target != file.packageName }
                .map { target -> file.packageName to target }
        }.toSet()
    }

    private fun owningPackage(importPath: String, declared: Set<String>): String? =
        generateSequence(importPath) { path -> path.substringBeforeLast('.', "").ifEmpty { null } }
            .firstOrNull(declared::contains)
}
