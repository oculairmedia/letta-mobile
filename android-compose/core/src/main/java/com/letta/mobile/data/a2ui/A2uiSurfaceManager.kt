package com.letta.mobile.data.a2ui

import androidx.compose.runtime.Stable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale

@Stable
data class A2uiSurfaceState(
    val surfaceId: String,
    val catalogId: String? = null,
    val theme: JsonObject? = null,
    val sendDataModel: Boolean = false,
    val agentId: String? = null,
    val conversationId: String? = null,
    val turnId: String? = null,
    val runId: String? = null,
    val approvalRequestId: String? = null,
    val rootComponentId: String? = null,
    val components: Map<String, A2uiComponent> = emptyMap(),
    val dataModel: A2uiDataModel = A2uiDataModel(),
    val dataModelRevision: Long = 0L,
) {
    val rootComponent: A2uiComponent?
        get() = rootComponentId?.let(components::get)
}

class A2uiSurfaceManager(
    initialSurfaces: Map<String, A2uiSurfaceState> = emptyMap(),
) {
    private val _surfaces = MutableStateFlow(initialSurfaces)
    val surfaces: StateFlow<Map<String, A2uiSurfaceState>> = _surfaces.asStateFlow()

    fun apply(event: A2uiFrameEvent) {
        _surfaces.update { current ->
            val updated = event.messages.fold(current) { surfaces, message -> surfaces.applyMessage(message) }
            updated.withEnvelopeMetadata(event)
        }
    }

    fun applyMessages(messages: Iterable<A2uiMessage>) {
        _surfaces.update { current ->
            messages.fold(current) { surfaces, message -> surfaces.applyMessage(message) }
        }
    }

    fun applyMessage(message: A2uiMessage) {
        applyMessages(listOf(message))
    }

    fun replaceWith(messages: Iterable<A2uiMessage>) {
        _surfaces.value = messages.fold(emptyMap()) { surfaces, message -> surfaces.applyMessage(message) }
    }

    fun clear() {
        _surfaces.value = emptyMap()
    }

    fun surface(surfaceId: String): A2uiSurfaceState? = surfaces.value[surfaceId]
}

object A2uiBindingResolver {
    fun resolve(binding: JsonElement?, dataModel: A2uiDataModel): A2uiResolvedBinding = when (binding) {
        null -> A2uiResolvedBinding.Missing
        is JsonPrimitive -> A2uiResolvedBinding.Value(binding)
        is JsonArray -> A2uiResolvedBinding.Value(binding)
        is JsonObject -> resolveObjectBinding(binding, dataModel)
    }

    fun resolve(binding: JsonElement?, dataModel: JsonElement): A2uiResolvedBinding = when (binding) {
        null -> A2uiResolvedBinding.Missing
        is JsonPrimitive -> A2uiResolvedBinding.Value(binding)
        is JsonArray -> A2uiResolvedBinding.Value(binding)
        is JsonObject -> resolveObjectBinding(binding, dataModel)
    }

    fun resolvePath(dataModel: A2uiDataModel, path: String): JsonElement? =
        dataModel.resolve(path)

    fun resolvePath(dataModel: JsonElement, path: String): JsonElement? {
        return A2uiJsonPointer.resolve(dataModel, path)
    }

    fun applyDataModelPatch(
        dataModel: A2uiDataModel,
        path: String,
        value: JsonElement?,
    ): A2uiDataModel = dataModel.apply { applyPatch(path, value) }

    fun applyDataModelPatch(
        dataModel: JsonElement,
        path: String,
        value: JsonElement?,
    ): JsonElement = A2uiJsonPointer.applyPatch(dataModel, path, value)

    fun displayText(value: JsonElement): String = when (value) {
        JsonNull -> ""
        is JsonPrimitive -> value.contentOrNull ?: value.toString()
        else -> value.toString()
    }

    private fun resolveObjectBinding(
        binding: JsonObject,
        dataModel: A2uiDataModel,
    ): A2uiResolvedBinding {
        binding["path"]?.jsonPrimitiveOrNull?.contentOrNull?.let { path ->
            return dataModel.resolve(path)?.let(A2uiResolvedBinding::Value)
                ?: A2uiResolvedBinding.Missing
        }
        binding["call"]?.jsonPrimitiveOrNull?.contentOrNull?.let {
            return A2uiResolvedBinding.Value(A2uiFunctionEvaluator.evaluate(binding, dataModel))
        }
        binding["literalString"]?.let { return A2uiResolvedBinding.Value(it) }
        binding["literal"]?.let { return A2uiResolvedBinding.Value(it) }
        binding["value"]?.let { return A2uiResolvedBinding.Value(it) }
        return A2uiResolvedBinding.Missing
    }

    private fun resolveObjectBinding(
        binding: JsonObject,
        dataModel: JsonElement,
    ): A2uiResolvedBinding {
        binding["path"]?.jsonPrimitiveOrNull?.contentOrNull?.let { path ->
            return resolvePath(dataModel, path)?.let(A2uiResolvedBinding::Value)
                ?: A2uiResolvedBinding.Missing
        }
        binding["call"]?.jsonPrimitiveOrNull?.contentOrNull?.let {
            return A2uiResolvedBinding.Value(A2uiFunctionEvaluator.evaluate(binding, dataModel))
        }
        binding["literalString"]?.let { return A2uiResolvedBinding.Value(it) }
        binding["literal"]?.let { return A2uiResolvedBinding.Value(it) }
        binding["value"]?.let { return A2uiResolvedBinding.Value(it) }
        return A2uiResolvedBinding.Missing
    }
}

sealed interface A2uiResolvedBinding {
    data object Missing : A2uiResolvedBinding
    data class Value(val value: JsonElement) : A2uiResolvedBinding
}

private object A2uiFunctionEvaluator {
    fun evaluate(binding: JsonObject, dataModel: A2uiDataModel): JsonElement =
        evaluate(binding, dataModel.root)

    fun evaluate(binding: JsonObject, dataModel: JsonElement): JsonElement {
        val call = binding.stringValue("call") ?: return JsonPrimitive("<unknown function>")
        val args = binding["args"] as? JsonObject ?: JsonObject(emptyMap())
        return runCatching { evaluateFunction(call, args, dataModel) }
            .getOrElse { JsonPrimitive("<function error: $call>") }
    }

    private fun evaluateFunction(
        call: String,
        args: JsonObject,
        dataModel: JsonElement,
    ): JsonElement = when (call) {
        "required" -> JsonPrimitive(required(argument(args, "value", dataModel)))
        "regex" -> JsonPrimitive(
            argumentText(args, "value", dataModel).orEmpty().matchesRegex(argumentText(args, "pattern", dataModel).orEmpty())
        )
        "length" -> evaluateLength(args, dataModel)
        "numeric" -> JsonPrimitive(argumentNumber(args, "value", dataModel) != null)
        "email" -> JsonPrimitive(EmailRegex.matches(argumentText(args, "value", dataModel).orEmpty()))
        "formatString" -> JsonPrimitive(formatString(args, dataModel))
        "formatNumber" -> JsonPrimitive(formatNumber(args, dataModel))
        "formatCurrency" -> JsonPrimitive(formatCurrency(args, dataModel))
        "formatDate" -> JsonPrimitive(formatDate(args, dataModel))
        "pluralize" -> JsonPrimitive(pluralize(args, dataModel))
        "openUrl" -> JsonPrimitive(argumentText(args, "url", dataModel) ?: argumentText(args, "href", dataModel).orEmpty())
        "and" -> JsonPrimitive(booleanArguments(args, dataModel).all { it })
        "or" -> JsonPrimitive(booleanArguments(args, dataModel).any { it })
        "not" -> JsonPrimitive(!argumentBoolean(args, "value", dataModel))
        else -> JsonPrimitive("<unknown function: $call>")
    }

    private fun evaluateLength(args: JsonObject, dataModel: JsonElement): JsonElement {
        val length = argumentText(args, "value", dataModel).orEmpty().length
        val min = argumentNumber(args, "min", dataModel)
        val max = argumentNumber(args, "max", dataModel)
        return if (min != null || max != null) {
            JsonPrimitive((min == null || length >= min) && (max == null || length <= max))
        } else {
            JsonPrimitive(length)
        }
    }

    private fun formatString(args: JsonObject, dataModel: JsonElement): String {
        val template = argumentText(args, "template", dataModel)
            ?: argumentText(args, "format", dataModel)
            ?: argumentText(args, "value", dataModel)
            ?: return ""
        return InterpolationRegex.replace(template) { match ->
            interpolate(match.groupValues[1].trim(), dataModel)
        }
    }

    private fun interpolate(token: String, dataModel: JsonElement): String {
        if (token.startsWith("/")) {
            return A2uiJsonPointer.resolve(dataModel, token)?.let(A2uiBindingResolver::displayText).orEmpty()
        }
        val expression = FunctionExpressionRegex.matchEntire(token)
        if (expression != null) {
            val call = expression.groupValues[1]
            val args = parseExpressionArgs(expression.groupValues[2])
            return A2uiBindingResolver.displayText(evaluate(buildJsonObject {
                put("call", call)
                put("args", args)
            }, dataModel))
        }
        return A2uiJsonPointer.resolve(dataModel, token)?.let(A2uiBindingResolver::displayText).orEmpty()
    }

    private fun formatNumber(args: JsonObject, dataModel: JsonElement): String {
        val value = argumentNumber(args, "value", dataModel) ?: return ""
        val formatter = NumberFormat.getNumberInstance(Locale.US)
        argumentNumber(args, "minimumFractionDigits", dataModel)?.toInt()?.let {
            formatter.minimumFractionDigits = it
        }
        argumentNumber(args, "maximumFractionDigits", dataModel)?.toInt()?.let {
            formatter.maximumFractionDigits = it
        }
        argumentNumber(args, "fractionDigits", dataModel)?.toInt()?.let {
            formatter.minimumFractionDigits = it
            formatter.maximumFractionDigits = it
        }
        return formatter.format(value)
    }

    private fun formatCurrency(args: JsonObject, dataModel: JsonElement): String {
        val value = argumentNumber(args, "value", dataModel) ?: return ""
        val formatter = NumberFormat.getCurrencyInstance(Locale.US)
        val currencyCode = argumentText(args, "currency", dataModel)
            ?: argumentText(args, "currencyCode", dataModel)
        currencyCode?.let { code ->
            runCatching { Currency.getInstance(code) }.getOrNull()?.let { formatter.currency = it }
        }
        return formatter.format(value)
    }

    private fun formatDate(args: JsonObject, dataModel: JsonElement): String {
        val value = argumentText(args, "value", dataModel) ?: return ""
        val pattern = argumentText(args, "pattern", dataModel)
            ?: argumentText(args, "format", dataModel)
            ?: "yyyy-MM-dd"
        val formatter = runCatching { DateTimeFormatter.ofPattern(pattern) }.getOrElse { DateTimeFormatter.ISO_LOCAL_DATE }
        val temporal = parseDateTime(value) ?: return value
        return formatter.format(temporal)
    }

    private fun pluralize(args: JsonObject, dataModel: JsonElement): String {
        val count = argumentNumber(args, "count", dataModel) ?: 0.0
        val countText = if (count % 1.0 == 0.0) count.toLong().toString() else count.toString()
        val singular = argumentText(args, "singular", dataModel) ?: ""
        val plural = argumentText(args, "plural", dataModel) ?: "${singular}s"
        val word = if (count == 1.0) singular else plural
        return "$countText $word"
    }

    private fun argument(args: JsonObject, key: String, dataModel: JsonElement): JsonElement? =
        args[key]?.let { evaluateElement(it, dataModel) }

    private fun argumentText(args: JsonObject, key: String, dataModel: JsonElement): String? =
        argument(args, key, dataModel)?.let(A2uiBindingResolver::displayText)

    private fun argumentNumber(args: JsonObject, key: String, dataModel: JsonElement): Double? =
        argument(args, key, dataModel)?.numberValue()

    private fun argumentBoolean(args: JsonObject, key: String, dataModel: JsonElement): Boolean =
        argument(args, key, dataModel).booleanValue()

    private fun booleanArguments(args: JsonObject, dataModel: JsonElement): List<Boolean> {
        val values = args["values"] as? JsonArray
        if (values != null) return values.map { evaluateElement(it, dataModel).booleanValue() }
        return args.values.map { evaluateElement(it, dataModel).booleanValue() }
    }

    private fun evaluateElement(element: JsonElement, dataModel: JsonElement): JsonElement = when (element) {
        is JsonObject -> when (val resolved = A2uiBindingResolver.resolve(element, dataModel)) {
            A2uiResolvedBinding.Missing -> element
            is A2uiResolvedBinding.Value -> resolved.value
        }
        else -> element
    }

    private fun required(value: JsonElement?): Boolean = when (value) {
        null, JsonNull -> false
        is JsonPrimitive -> value.contentOrNull?.isNotBlank() ?: true
        is JsonArray -> value.isNotEmpty()
        is JsonObject -> value.isNotEmpty()
    }

    private fun JsonElement?.numberValue(): Double? = when (this) {
        is JsonPrimitive -> doubleOrNull ?: contentOrNull?.toDoubleOrNull()
        else -> null
    }

    private fun JsonElement?.booleanValue(): Boolean = when (this) {
        is JsonPrimitive -> booleanOrNull
            ?: contentOrNull?.toBooleanStrictOrNull()
            ?: (numberValue()?.let { it != 0.0 } ?: false)
        is JsonArray -> isNotEmpty()
        is JsonObject -> isNotEmpty()
        else -> false
    }

    private fun String.matchesRegex(pattern: String): Boolean =
        runCatching { Regex(pattern).matches(this) }.getOrDefault(false)

    private fun parseDateTime(value: String): java.time.temporal.TemporalAccessor? =
        runCatching { Instant.parse(value).atZone(ZoneOffset.UTC) }.getOrNull()
            ?: runCatching { LocalDateTime.parse(value, DateTimeFormatter.ISO_DATE_TIME) }.getOrNull()
            ?: runCatching { LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull()

    private fun parseExpressionArgs(raw: String): JsonObject = buildJsonObject {
        raw.split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .forEach { part ->
                val key = part.substringBefore(':').trim()
                val value = part.substringAfter(':', missingDelimiterValue = "").trim()
                if (key.isNotBlank()) put(key, parseExpressionValue(value))
            }
    }

    private fun parseExpressionValue(value: String): JsonElement = when {
        value.startsWith("/") -> buildJsonObject { put("path", value) }
        value.equals("true", ignoreCase = true) -> JsonPrimitive(true)
        value.equals("false", ignoreCase = true) -> JsonPrimitive(false)
        value.toLongOrNull() != null -> JsonPrimitive(value.toLong())
        value.toDoubleOrNull() != null -> JsonPrimitive(value.toDouble())
        value.length >= 2 && value.first() == '"' && value.last() == '"' -> JsonPrimitive(value.drop(1).dropLast(1))
        value.length >= 2 && value.first() == '\'' && value.last() == '\'' -> JsonPrimitive(value.drop(1).dropLast(1))
        else -> JsonPrimitive(value)
    }

    private fun JsonObject.stringValue(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> (this[key] as? JsonPrimitive)?.contentOrNull }

    private val EmailRegex = Regex("""^[^\s@]+@[^\s@]+\.[^\s@]+$""")
    private val InterpolationRegex = Regex("""\$\{([^}]+)}""")
    private val FunctionExpressionRegex = Regex("""^([A-Za-z_][\w.-]*)\((.*)\)$""")
}

private fun Map<String, A2uiSurfaceState>.applyMessage(message: A2uiMessage): Map<String, A2uiSurfaceState> =
    when (message) {
        is A2uiMessage.CreateSurface -> upsertSurface(message)
        is A2uiMessage.UpdateComponents -> updateComponents(message)
        is A2uiMessage.UpdateDataModel -> updateDataModel(message)
        is A2uiMessage.DeleteSurface -> minus(message.surfaceId)
        is A2uiMessage.Unknown -> this
    }

private fun Map<String, A2uiSurfaceState>.withEnvelopeMetadata(
    event: A2uiFrameEvent,
): Map<String, A2uiSurfaceState> {
    val surfaceIds = event.messages.mapNotNull { it.surfaceId.takeIf(String::isNotBlank) }.toSet()
    if (surfaceIds.isEmpty()) return this
    return mapValues { (surfaceId, surface) ->
        if (surfaceId !in surfaceIds) {
            surface
        } else {
            surface.copy(
                agentId = event.agentId ?: surface.agentId,
                conversationId = event.conversationId ?: surface.conversationId,
                turnId = event.turnId ?: surface.turnId,
                runId = event.runId ?: surface.runId,
                approvalRequestId = event.requestId ?: surface.approvalRequestId,
            )
        }
    }
}

private fun Map<String, A2uiSurfaceState>.upsertSurface(
    message: A2uiMessage.CreateSurface,
): Map<String, A2uiSurfaceState> {
    val payload = message.createSurface
    val existing = this[payload.surfaceId]
    val next = (existing ?: A2uiSurfaceState(surfaceId = payload.surfaceId)).copy(
        catalogId = payload.catalogId,
        theme = payload.theme,
        sendDataModel = payload.sendDataModel,
    )
    return plus(payload.surfaceId to next)
}

private fun Map<String, A2uiSurfaceState>.updateComponents(
    message: A2uiMessage.UpdateComponents,
): Map<String, A2uiSurfaceState> {
    val payload = message.updateComponents
    val existing = this[payload.surfaceId] ?: A2uiSurfaceState(surfaceId = payload.surfaceId)
    val incoming = payload.components.associateBy(A2uiComponent::id)
    val nextComponents = existing.components + incoming
    val root = payload.root
        ?: existing.rootComponentId
        ?: payload.components.firstOrNull()?.id
    return plus(
        payload.surfaceId to existing.copy(
            rootComponentId = root,
            components = nextComponents,
        )
    )
}

private fun Map<String, A2uiSurfaceState>.updateDataModel(
    message: A2uiMessage.UpdateDataModel,
): Map<String, A2uiSurfaceState> {
    val payload = message.updateDataModel
    val existing = this[payload.surfaceId] ?: A2uiSurfaceState(surfaceId = payload.surfaceId)
    A2uiBindingResolver.applyDataModelPatch(
        dataModel = existing.dataModel,
        path = payload.path,
        value = payload.value,
    )
    return plus(
        payload.surfaceId to existing.copy(
            dataModelRevision = existing.dataModelRevision + 1,
        )
    )
}

private val JsonElement.jsonPrimitiveOrNull: JsonPrimitive?
    get() = this as? JsonPrimitive
