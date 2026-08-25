package com.letta.mobile.data.model

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.listSerialDescriptor
import kotlinx.serialization.descriptors.mapSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonBuilder

/**
 * KSerializer for kotlinx.collections.immutable.ImmutableList<T>.
 *
 * kotlinx-serialization-json does NOT ship a serializer for ImmutableList; without an
 * explicit serializer, `Json.encodeToString(ImmutableListOfT)` and `Json.decodeFromString`
 * fail with "Expected JsonObject, but had JsonArray as the serialized body of
 *  kotlinx.serialization.Polymorphic<ImmutableList>" on JSON-array input.
 *
 * This serializer delegates to the SDK's [ListSerializer] for the wire encoding (JsonArray
 * of elements), and converts to/from ImmutableList at the boundary via
 * toPersistentList()/toList().
 *
 * Per-field usage:
 * ```
 * @Serializable
 * data class Sample(@Serializable(with = ImmutableListSerializer::class)
 *                   val ids: ImmutableList<String>)
 *
 * @Serializable
 * data class WithInt(@Serializable(with = ImmutableListSerializer::class)
 *                    val nums: ImmutableList<Int>)
 * ```
 *
 * The element serializer is resolved via contextual lookup of the @Serializable
 * annotation's type. For top-level non-annotated fields, callers should use
 * [immutableListOf] which takes the element serializer explicitly:
 * ```
 * val s = ImmutableListSerializer(String.serializer())
 * ```
 *
 * Closes the kotlinx-serialization-kotlinx-collections-immutable gap that
 * letta-mobile-9e8zn / letta-mobile-91er9.19 (List<T> -> ImmutableList<T> in sharedLogic/data/model)
 * depend on.
 */
class ImmutableListSerializer<T>(
    private val elementSerializer: KSerializer<T>,
) : KSerializer<ImmutableList<T>> {
    private val delegate = ListSerializer(elementSerializer)
    override val descriptor: SerialDescriptor = listSerialDescriptor(elementSerializer.descriptor)

    override fun serialize(encoder: Encoder, value: ImmutableList<T>) {
        delegate.serialize(encoder, value.toList())
    }

    override fun deserialize(decoder: Decoder): ImmutableList<T> =
        delegate.deserialize(decoder).toPersistentList()
}

/**
 * KSerializer for kotlinx.collections.immutable.ImmutableMap<K, V>.
 */
class ImmutableMapSerializer<K, V>(
    keySerializer: KSerializer<K>,
    valueSerializer: KSerializer<V>,
) : KSerializer<ImmutableMap<K, V>> {
    private val delegate = MapSerializer(keySerializer, valueSerializer)
    override val descriptor: SerialDescriptor = mapSerialDescriptor(keySerializer.descriptor, valueSerializer.descriptor)

    override fun serialize(encoder: Encoder, value: ImmutableMap<K, V>) {
        delegate.serialize(encoder, value.toMap())
    }

    override fun deserialize(decoder: Decoder): ImmutableMap<K, V> =
        delegate.deserialize(decoder).toPersistentMap()
}

/**
 * Factory function for the project's standard Json configuration. Defaults:
 * - `ignoreUnknownKeys = true` (forward-compatible to backend schema additions)
 * - `encodeDefaults = false` (omit default values to keep payloads small)
 * - `isLenient = true` (tolerate trailing commas / unquoted keys in development)
 *
 * Callers may override any default by passing a `JsonBuilder.() -> Unit` block:
 *
 *   lettaJson { prettyPrint = true }
 *
 * **For ImmutableList<T> fields, use `@Serializable(with = ImmutableListSerializer::class)`
 * directly on each field** — the contextual-serializers registration is not a viable
 * mechanism for generic interfaces in kotlinx-serialization 1.11.0 (the contextual provider
 * lambda receives an empty List<KSerializer<*>> because the raw class's typeArguments
 * don't bind a runtime serializer).
 *
 * Pairs with the kotlinx-serialization-kotlinx-collections-immutable pre-bead
 * (letta-mobile-9e8zn). For the migration target (letta-mobile-91er9.19), each
 * `List<T>` -> `ImmutableList<T>` field in the model classes adds
 * `@Serializable(with = ImmutableListSerializer::class)` annotation.
 */
fun lettaJson(builderAction: JsonBuilder.() -> Unit = {}): Json {
    return Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        isLenient = true
        builderAction()
    }
}