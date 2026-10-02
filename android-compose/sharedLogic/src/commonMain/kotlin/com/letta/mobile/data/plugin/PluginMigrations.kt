package com.letta.mobile.data.plugin

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One declarative props migration step (plan section 3.1): nothing but these three. */
sealed interface PluginMigrationOp {
    /** [from] becomes [to]; when both are present, [to] wins and [from] is dropped. */
    data class Rename(val from: String, val to: String) : PluginMigrationOp

    /** [field] gets [value] when it has none. */
    data class Default(val field: String, val value: JsonElement) : PluginMigrationOp

    data class Drop(val field: String) : PluginMigrationOp

    companion object {
        /** [step] as an op, or null when it is not exactly one well-formed op (refused). */
        fun of(step: PluginMigrationStep): PluginMigrationOp? {
            val ops = listOfNotNull(step.rename?.let(::rename), step.default?.let(::default), step.drop?.let(::Drop))
            val written = listOfNotNull(step.rename, step.default, step.drop).size
            return ops.singleOrNull()?.takeIf { written == 1 }
        }

        private fun rename(pair: List<String>): Rename? =
            pair.takeIf { it.size == 2 && it[0] != it[1] }?.let { Rename(it[0], it[1]) }

        private fun default(pair: List<JsonElement>): Default? {
            val field = (pair.getOrNull(0) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val value = (pair.getOrNull(1) as? JsonPrimitive)?.takeIf { it !is JsonNull } ?: return null
            return Default(field, value).takeIf { pair.size == 2 }
        }
    }
}

/** The outcome of migrating an element's props. */
sealed interface PluginMigrationResult {
    data class Migrated(val props: JsonObject, val v: Int) : PluginMigrationResult

    data class Refused(val reason: String) : PluginMigrationResult
}

/**
 * Props migrations (plan section 3.1, letta-mobile-s416w.24): a kind's `migrations` map is keyed by
 * the version a step list migrates FROM, so migrating v1 props to v3 applies the lists at "1" and
 * "2" in order; a version with no list changed nothing the props carry. Pure: the host applies it
 * when an element of an older version is read, and the props validator then holds the result to
 * the current schema.
 */
object PluginMigrations {
    fun apply(props: JsonObject, fromV: Int, toV: Int, kind: PluginElementKind): PluginMigrationResult {
        if (toV !in validTargets(fromV, kind)) {
            return PluginMigrationResult.Refused("cannot migrate v$fromV to v$toV; the kind is at v${kind.schemaVersion}")
        }
        var current = props
        for (v in fromV until toV) {
            kind.migrations[v.toString()].orEmpty().forEachIndexed { index, step ->
                val op = PluginMigrationOp.of(step) ?: return PluginMigrationResult.Refused("migration $v step $index is not rename, default or drop")
                current = applyOp(current, op)
            }
        }
        return PluginMigrationResult.Migrated(current, toV)
    }

    /** The versions props at [fromV] can be migrated to: none when [fromV] is not one of the kind's. */
    private fun validTargets(fromV: Int, kind: PluginElementKind): IntRange =
        if (fromV >= 1) fromV..kind.schemaVersion else IntRange.EMPTY

    /** [props] at [fromV] brought to the kind's current version. */
    fun toCurrent(props: JsonObject, fromV: Int, kind: PluginElementKind): PluginMigrationResult =
        apply(props, fromV, kind.schemaVersion, kind)

    private fun applyOp(props: JsonObject, op: PluginMigrationOp): JsonObject = when (op) {
        is PluginMigrationOp.Rename -> rename(props, op)
        is PluginMigrationOp.Default -> if (op.field in props) props else JsonObject(props + (op.field to op.value))
        is PluginMigrationOp.Drop -> JsonObject(props - op.field)
    }

    private fun rename(props: JsonObject, op: PluginMigrationOp.Rename): JsonObject {
        val value = props[op.from] ?: return props
        val kept = props - op.from
        return JsonObject(if (op.to in kept) kept else kept + (op.to to value))
    }
}
