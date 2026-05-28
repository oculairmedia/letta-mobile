package com.letta.mobile.data.a2ui

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// Inner A2UI message version (createSurface / updateComponents / …).
// Wire-format protocol revision — leading "v" matches spec §3.
const val A2UI_PROTOCOL_VERSION = "v0.9"

// Hello-frame `a2ui_version` field. The shim does *string equality*
// against its server-side A2UI_VERSION env (no "v" prefix), so this
// must stay distinct from [A2UI_PROTOCOL_VERSION] — a leading "v" gets
// rejected and the handshake silently drops a2ui_negotiated.
const val A2UI_HELLO_VERSION = "0.9"

// Short catalog handle the shim's `supported_catalogs` negotiation
// matches on. NOT the upstream catalog $id URL — the shim does
// string-equality against its registered handle list.
const val A2UI_BASIC_CATALOG_ID = "basic"
const val A2UI_LIST_VIEW_WIDGET_ID = "ListView"

const val LETTA_TOOL_APPROVAL_CATALOG_ID = "com.letta.mobile:tool-approval/v1"
const val LETTA_TOOL_APPROVAL_WIDGET_ID = "ToolApprovalCard"

const val LETTA_SCHEDULE_CATALOG_ID = "com.letta.mobile:schedule/v1"
const val LETTA_SCHEDULE_CARD_WIDGET_ID = "ScheduleCard"
const val LETTA_SCHEDULE_SELECTOR_WIDGET_ID = "ScheduleSelectorInput"

val A2UI_DEFAULT_SUPPORTED_CATALOGS: List<String> = listOf(
    A2UI_BASIC_CATALOG_ID,
    LETTA_TOOL_APPROVAL_CATALOG_ID,
    LETTA_SCHEDULE_CATALOG_ID,
)

val A2UI_DEFAULT_SUPPORTED_WIDGETS: List<String> = listOf(
    "Text",
    "Column",
    "Row",
    "Card",
    "Button",
    "Divider",
    "TextField",
    "DateTimeInput",
    "CheckBox",
    "Checkbox",
    "Switch",
    "Radio",
    "ChoicePicker",
    "Slider",
    "Stepper",
    "LinearProgress",
    "CircularProgress",
    "Dropdown",
    "Select",
    "Chip",
    "FilterChip",
    "Badge",
    "Tabs",
    "Accordion",
    "Icon",
    "Image",
    "List",
    A2UI_LIST_VIEW_WIDGET_ID,
    "Modal",
    "Video",
    "AudioPlayer",
    LETTA_TOOL_APPROVAL_WIDGET_ID,
    LETTA_SCHEDULE_CARD_WIDGET_ID,
    LETTA_SCHEDULE_SELECTOR_WIDGET_ID,
)

@Serializable
data class A2uiCapabilityDeclaration(
    @SerialName("a2ui_version") val a2uiVersion: String = A2UI_HELLO_VERSION,
    @SerialName("supported_catalogs") val supportedCatalogs: List<String> = A2UI_DEFAULT_SUPPORTED_CATALOGS,
    @SerialName("supported_widgets") val supportedWidgets: List<String> = A2UI_DEFAULT_SUPPORTED_WIDGETS,
    @SerialName("theme_hints") val themeHints: A2uiThemeHints = A2uiThemeHints(),
)

@Serializable
data class A2uiThemeHints(
    val platform: String = "android-compose",
    @SerialName("material_version") val materialVersion: String = "material3",
    @SerialName("supports_dark_mode") val supportsDarkMode: Boolean = true,
    @SerialName("supports_dynamic_color") val supportsDynamicColor: Boolean = true,
)

/**
 * Per shim §2.2: nested `a2ui` object on the welcome frame, present
 * only when `a2ui_negotiated == true`. `catalog_id` is the shim's
 * short catalog handle (not the upstream `$id` URL).
 */
@Serializable
data class A2uiHandshakeAck(
    val version: String,
    @SerialName("catalog_id") val catalogId: String,
)

@Serializable(with = A2uiMessageSerializer::class)
sealed interface A2uiMessage {
    val version: String
    val messageType: String
    val surfaceId: String

    @Serializable
    data class CreateSurface(
        override val version: String = A2UI_PROTOCOL_VERSION,
        @SerialName("createSurface") val createSurface: A2uiCreateSurfacePayload,
    ) : A2uiMessage {
        override val messageType: String get() = "createSurface"
        override val surfaceId: String get() = createSurface.surfaceId
    }

    @Serializable
    data class UpdateComponents(
        override val version: String = A2UI_PROTOCOL_VERSION,
        @SerialName("updateComponents") val updateComponents: A2uiUpdateComponentsPayload,
    ) : A2uiMessage {
        override val messageType: String get() = "updateComponents"
        override val surfaceId: String get() = updateComponents.surfaceId
    }

    @Serializable
    data class UpdateDataModel(
        override val version: String = A2UI_PROTOCOL_VERSION,
        @SerialName("updateDataModel") val updateDataModel: A2uiUpdateDataModelPayload,
    ) : A2uiMessage {
        override val messageType: String get() = "updateDataModel"
        override val surfaceId: String get() = updateDataModel.surfaceId
    }

    @Serializable
    data class DeleteSurface(
        override val version: String = A2UI_PROTOCOL_VERSION,
        @SerialName("deleteSurface") val deleteSurface: A2uiDeleteSurfacePayload,
    ) : A2uiMessage {
        override val messageType: String get() = "deleteSurface"
        override val surfaceId: String get() = deleteSurface.surfaceId
    }

    data class Unknown(
        override val version: String = "",
        override val messageType: String = "unknown",
        override val surfaceId: String = "",
        val raw: JsonObject,
    ) : A2uiMessage
}

@Serializable
data class A2uiCreateSurfacePayload(
    @SerialName("surfaceId") val surfaceId: String,
    @SerialName("catalogId") val catalogId: String,
    val theme: JsonObject? = null,
    @SerialName("sendDataModel") val sendDataModel: Boolean = false,
)

@Serializable
data class A2uiUpdateComponentsPayload(
    @SerialName("surfaceId") val surfaceId: String,
    val components: List<A2uiComponent>,
    val root: String? = null,
)

@Serializable(with = A2uiUpdateDataModelPayloadSerializer::class)
data class A2uiUpdateDataModelPayload(
    @SerialName("surfaceId") val surfaceId: String,
    val path: String = "/",
    val value: JsonElement? = null,
)

@Serializable
data class A2uiDeleteSurfacePayload(
    @SerialName("surfaceId") val surfaceId: String,
)

@Serializable(with = A2uiComponentSerializer::class)
data class A2uiComponent(
    val id: String,
    val component: String,
    val raw: JsonObject,
) {
    val child: String?
        get() = raw["child"]?.jsonPrimitiveOrNull?.contentOrNull

    val children: List<String>
        get() = (raw["children"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitiveOrNull?.contentOrNull }
            .orEmpty()

    /**
     * A2UI v2 list-template metadata. The component remains raw-preserving for
     * forward compatibility; this typed view exposes the fields renderer work
     * needs without making ListView a separate envelope format.
     */
    val listTemplate: A2uiListTemplatePayload?
        get() = A2uiListTemplatePayload.from(raw).takeIf { component == A2UI_LIST_VIEW_WIDGET_ID || component == "List" }
}

/**
 * Template/repeat primitive for rendering a component once per item in a data
 * model array. Bindings inside [itemTemplateComponentId] resolve relative to
 * each item at [itemsPath]/<index>, so `{ "path": "title" }` resolves against
 * `/issues/0/title` for the first item instead of the surface root.
 *
 * Per-item actions should include item identity in their emitted context, e.g.
 * `{ "type": "user_action", "name": "issue.open", "context": { "itemId": "..." } }`.
 */
data class A2uiListTemplatePayload(
    val itemTemplateComponentId: String,
    val itemsPath: String,
    val itemKeyPath: String = "id",
) {
    companion object {
        fun from(raw: JsonObject): A2uiListTemplatePayload? {
            val childrenObject = raw["children"] as? JsonObject
            val itemTemplate = raw.stringValue("itemTemplate", "itemTemplateId", "templateComponentId")
                ?.takeIf { it.isNotBlank() }
                ?: childrenObject?.stringValue("componentId", "itemTemplate", "itemTemplateId", "templateComponentId")
                    ?.takeIf { it.isNotBlank() }
                ?: return null
            val itemsPath = raw.bindingPath("items")
                ?.takeIf { it.isNotBlank() }
                ?: childrenObject?.bindingPath("path")?.takeIf { it.isNotBlank() }
                ?: return null
            val itemKey = raw.stringValue("itemKey", "itemKeyPath")?.takeIf { it.isNotBlank() } ?: "id"
            return A2uiListTemplatePayload(
                itemTemplateComponentId = itemTemplate,
                itemsPath = itemsPath,
                itemKeyPath = itemKey,
            )
        }
    }
}

data class A2uiFrameEvent(
    val transport: String,
    val frameId: String?,
    val timestamp: String?,
    val agentId: String?,
    val conversationId: String?,
    val turnId: String?,
    val runId: String?,
    val requestId: String?,
    val messages: List<A2uiMessage>,
)

object A2uiProtocolJson {
    val Default: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }
}

fun decodeA2uiMessages(json: Json, element: JsonElement): List<A2uiMessage> = when (element) {
    is JsonArray -> element.map { json.decodeFromJsonElement(A2uiMessageSerializer, it) }
    is JsonObject -> {
        val nested = element["messages"] ?: element["message"] ?: element["payload"] ?: element["data"]
        if (nested != null && !element.hasA2uiMessageKey()) {
            decodeA2uiMessages(json, nested)
        } else {
            listOf(json.decodeFromJsonElement(A2uiMessageSerializer, element))
        }
    }
    else -> throw SerializationException("Expected A2UI message object or array")
}

object A2uiMessageSerializer : KSerializer<A2uiMessage> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("A2uiMessage")

    override fun deserialize(decoder: Decoder): A2uiMessage {
        val jsonDecoder = decoder as? JsonDecoder
            ?: error("A2uiMessageSerializer requires a JsonDecoder")
        val element = jsonDecoder.decodeJsonElement().jsonObject
        return when {
            "createSurface" in element -> jsonDecoder.json.decodeFromJsonElement(
                A2uiMessage.CreateSurface.serializer(),
                element,
            )
            "updateComponents" in element -> jsonDecoder.json.decodeFromJsonElement(
                A2uiMessage.UpdateComponents.serializer(),
                element,
            )
            "updateDataModel" in element -> jsonDecoder.json.decodeFromJsonElement(
                A2uiMessage.UpdateDataModel.serializer(),
                element,
            )
            "deleteSurface" in element -> jsonDecoder.json.decodeFromJsonElement(
                A2uiMessage.DeleteSurface.serializer(),
                element,
            )
            else -> A2uiMessage.Unknown(
                version = element["version"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty(),
                messageType = element.keys.firstOrNull { it != "version" } ?: "unknown",
                raw = element,
            )
        }
    }

    override fun serialize(encoder: Encoder, value: A2uiMessage) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: error("A2uiMessageSerializer only supports JSON")
        when (value) {
            is A2uiMessage.CreateSurface -> jsonEncoder.encodeSerializableValue(
                A2uiMessage.CreateSurface.serializer(),
                value,
            )
            is A2uiMessage.UpdateComponents -> jsonEncoder.encodeSerializableValue(
                A2uiMessage.UpdateComponents.serializer(),
                value,
            )
            is A2uiMessage.UpdateDataModel -> jsonEncoder.encodeSerializableValue(
                A2uiMessage.UpdateDataModel.serializer(),
                value,
            )
            is A2uiMessage.DeleteSurface -> jsonEncoder.encodeSerializableValue(
                A2uiMessage.DeleteSurface.serializer(),
                value,
            )
            is A2uiMessage.Unknown -> jsonEncoder.encodeJsonElement(value.raw)
        }
    }
}

object A2uiComponentSerializer : KSerializer<A2uiComponent> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("A2uiComponent")

    override fun deserialize(decoder: Decoder): A2uiComponent {
        val jsonDecoder = decoder as? JsonDecoder
            ?: error("A2uiComponentSerializer requires a JsonDecoder")
        val obj = jsonDecoder.decodeJsonElement().jsonObject
        val id = obj["id"]?.jsonPrimitiveOrNull?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: throw SerializationException("A2uiComponent.id is required")
        val component = obj["component"]?.jsonPrimitiveOrNull?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: throw SerializationException("A2uiComponent.component is required")
        return A2uiComponent(
            id = id,
            component = component,
            raw = obj,
        )
    }

    override fun serialize(encoder: Encoder, value: A2uiComponent) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: error("A2uiComponentSerializer only supports JSON")
        jsonEncoder.encodeJsonElement(value.raw)
    }
}

object A2uiUpdateDataModelPayloadSerializer : KSerializer<A2uiUpdateDataModelPayload> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("A2uiUpdateDataModelPayload")

    override fun deserialize(decoder: Decoder): A2uiUpdateDataModelPayload {
        val jsonDecoder = decoder as? JsonDecoder
            ?: error("A2uiUpdateDataModelPayloadSerializer requires a JsonDecoder")
        val obj = jsonDecoder.decodeJsonElement().jsonObject
        return A2uiUpdateDataModelPayload(
            surfaceId = obj["surfaceId"]?.jsonPrimitiveOrNull?.contentOrNull
                ?: throw SerializationException("updateDataModel.surfaceId is required"),
            path = obj["path"]?.jsonPrimitiveOrNull?.contentOrNull ?: "/",
            value = if ("value" in obj) obj["value"] ?: JsonNull else null,
        )
    }

    override fun serialize(encoder: Encoder, value: A2uiUpdateDataModelPayload) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: error("A2uiUpdateDataModelPayloadSerializer only supports JSON")
        jsonEncoder.encodeJsonElement(
            buildJsonObject {
                put("surfaceId", value.surfaceId)
                if (value.path != "/") put("path", value.path)
                value.value?.let { put("value", it) }
            }
        )
    }
}

private fun JsonObject.hasA2uiMessageKey(): Boolean =
    "createSurface" in this ||
        "updateComponents" in this ||
        "updateDataModel" in this ||
        "deleteSurface" in this

private val JsonElement.jsonPrimitiveOrNull: JsonPrimitive?
    get() = this as? JsonPrimitive

private val JsonPrimitive.contentOrNull: String?
    get() = runCatching { content }.getOrNull()

private fun JsonObject.stringValue(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
    this[key]?.jsonPrimitiveOrNull?.contentOrNull
}

private fun JsonObject.bindingPath(key: String): String? = when (val value = this[key]) {
    is JsonPrimitive -> value.contentOrNull
    is JsonObject -> value.stringValue("path")
    else -> null
}
