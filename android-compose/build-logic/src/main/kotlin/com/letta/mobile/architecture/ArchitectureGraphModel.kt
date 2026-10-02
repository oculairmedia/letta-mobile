package com.letta.mobile.architecture

import java.io.File

/** One module record from `graph.jsonl`. */
internal data class GraphModule(val path: String, val kind: String)

/** One declared project-to-project dependency from `graph.jsonl`. */
internal data class GraphEdge(val from: String, val to: String, val configuration: String)

/**
 * The slice of the exported architecture graph the boundary gate reads.
 *
 * It is only ever built through [ArchitectureGraphReader], which fails closed:
 * a missing, empty, edgeless or malformed graph never reaches the rules.
 */
internal class ArchitectureGraph(modules: List<GraphModule>, val edges: List<GraphEdge>) {
    private val modulesByPath = modules.associateBy(GraphModule::path)

    fun kindOf(path: String): String = modulesByPath.getValue(path).kind

    fun hasModule(path: String): Boolean = path in modulesByPath
}

internal class ArchitectureGraphException(message: String) : RuntimeException(message)

internal object ArchitectureGraphReader {
    private val field = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*(?:null|\"((?:[^\"\\\\]|\\\\.)*)\")")

    fun read(file: File): ArchitectureGraph {
        if (!file.isFile) {
            throw ArchitectureGraphException("Architecture graph ${file.path} is missing; run exportArchitectureGraph.")
        }
        return parse(file.readLines(), file.path)
    }

    fun parse(lines: List<String>, source: String = "graph"): ArchitectureGraph {
        val records = lines.map(String::trim).filter(String::isNotEmpty).map { line -> parseRecord(line, source) }
        val modules = records.filter { it["type"] == "module" }.map { GraphModule(it.require("path"), it.require("kind")) }
        val edges = records.filter { it["type"] == "projectEdge" }
            .map { GraphEdge(it.require("from"), it.require("to"), it.require("configuration")) }
        if (modules.isEmpty() || edges.isEmpty()) {
            throw ArchitectureGraphException(
                "Architecture graph $source has ${modules.size} modules and ${edges.size} project edges; " +
                    "an empty graph cannot prove any boundary, so the gate fails closed.",
            )
        }
        val graph = ArchitectureGraph(modules, edges)
        val unknown = edges.flatMap { listOf(it.from, it.to) }.filterNot(graph::hasModule).distinct()
        if (unknown.isNotEmpty()) {
            throw ArchitectureGraphException("Architecture graph $source has edges to unknown modules: $unknown")
        }
        return graph
    }

    private fun parseRecord(line: String, source: String): Map<String, String?> {
        if (!line.startsWith("{") || !line.endsWith("}")) {
            throw ArchitectureGraphException("Architecture graph $source has a malformed record: $line")
        }
        val record = field.findAll(line).associate { match -> match.groupValues[1] to match.groups[2]?.value }
        if (record["type"] == null) {
            throw ArchitectureGraphException("Architecture graph $source has a record without a type: $line")
        }
        return record
    }

    private fun Map<String, String?>.require(key: String): String =
        this[key] ?: throw ArchitectureGraphException("Architecture graph record ${this["type"]} lacks \"$key\": $this")
}
