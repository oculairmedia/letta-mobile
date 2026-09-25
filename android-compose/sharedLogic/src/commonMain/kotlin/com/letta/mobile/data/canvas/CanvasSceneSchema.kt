package com.letta.mobile.data.canvas

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** How one element field is written on the wire, as DrawBox's decoder reads it. */
enum class CanvasFieldKind { STRING, NUMBER, INTEGER, LONG, BOOLEAN, COLOR, POINT, POINT_LIST, SAMPLE_LIST }

/** One element field: its wire [kind] and, for an enumeration, the [allowed] values. */
data class CanvasFieldSpec(val name: String, val kind: CanvasFieldKind, val allowed: List<String> = emptyList())

/**
 * One element type the renderer draws: what a writer must send ([required]), what the host fills in
 * when it is left out ([defaults]), what it may add ([optional]), and a compact [example].
 */
data class CanvasElementSpec(
    val type: String,
    val note: String,
    val required: List<String>,
    val defaults: Map<String, JsonElement>,
    val optional: List<String>,
    val example: JsonObject,
    val minPoints: Int = 0,
)

/**
 * The scene format the apps can draw, and the one source of truth for it (letta-mobile-qygvv.21).
 *
 * Read off DrawBox's own decoder (drawbox `domain/model/Serialization.kt`: `SerializableDrawing`,
 * `SerializableElement`, `ElementDto.toElement`), which is what renders a scene on every app:
 *
 * - a scene is `{"bgColor": "#rrggbbaa", "elements": [...]}`, both keys mandatory;
 * - every element carries `id`, `type`, `zIndex`, `points`, `strokeColor` and `strokeWidth`, or the
 *   whole scene fails to decode and the board shows nothing;
 * - `type` is one of Shape, Text, Path, Image. Anything else decodes as an empty Path.
 *   Lines and arrows are Shapes (`shapeType`); notes are block documents (`set_document`), not
 *   elements.
 *
 * The tool contract describes it from here, the host validates agent writes against it and the
 * apps drop what their decoder cannot read ([CanvasSceneRenderGuard]).
 */
object CanvasSceneSchema {
    const val DEFAULT_BG_COLOR = "#ffffffff"

    /** The keys DrawBox's decoder requires on every element. */
    val wireRequired: List<String> = listOf("id", "type", "zIndex", "points", "strokeColor", "strokeWidth")

    val shapeTypes = listOf("RECTANGLE", "CIRCLE", "TRIANGLE", "LINE", "ARROW")
    val strokeStyles = listOf("SOLID", "DASHED", "DOTTED")
    val alignments = listOf("LEFT", "CENTER", "RIGHT")
    val fontFamilies = listOf("sans", "serif", "mono")

    /** Every field DrawBox reads from an element, by name. */
    val fields: Map<String, CanvasFieldSpec> = listOf(
        CanvasFieldSpec("id", CanvasFieldKind.STRING),
        CanvasFieldSpec("type", CanvasFieldKind.STRING),
        CanvasFieldSpec("zIndex", CanvasFieldKind.INTEGER),
        CanvasFieldSpec("points", CanvasFieldKind.POINT_LIST),
        CanvasFieldSpec("strokeColor", CanvasFieldKind.COLOR),
        CanvasFieldSpec("strokeWidth", CanvasFieldKind.NUMBER),
        CanvasFieldSpec("alpha", CanvasFieldKind.NUMBER),
        CanvasFieldSpec("shapeType", CanvasFieldKind.STRING, shapeTypes),
        CanvasFieldSpec("fillColor", CanvasFieldKind.COLOR),
        CanvasFieldSpec("rotation", CanvasFieldKind.NUMBER),
        CanvasFieldSpec("cornerRadius", CanvasFieldKind.NUMBER),
        CanvasFieldSpec("strokeStyle", CanvasFieldKind.STRING, strokeStyles),
        CanvasFieldSpec("bend", CanvasFieldKind.POINT),
        CanvasFieldSpec("startBinding", CanvasFieldKind.STRING),
        CanvasFieldSpec("endBinding", CanvasFieldKind.STRING),
        CanvasFieldSpec("createdAt", CanvasFieldKind.LONG),
        CanvasFieldSpec("modifiedAt", CanvasFieldKind.LONG),
        CanvasFieldSpec("samples", CanvasFieldKind.SAMPLE_LIST),
        CanvasFieldSpec("strokeEnabled", CanvasFieldKind.BOOLEAN),
        CanvasFieldSpec("imageData", CanvasFieldKind.STRING),
        CanvasFieldSpec("imageRef", CanvasFieldKind.STRING),
        CanvasFieldSpec("imageMediaType", CanvasFieldKind.STRING),
        CanvasFieldSpec("imagePreview", CanvasFieldKind.STRING),
        CanvasFieldSpec("intrinsicWidth", CanvasFieldKind.NUMBER),
        CanvasFieldSpec("intrinsicHeight", CanvasFieldKind.NUMBER),
        CanvasFieldSpec("opacity", CanvasFieldKind.NUMBER),
        CanvasFieldSpec("text", CanvasFieldKind.STRING),
        CanvasFieldSpec("fontFamilyKey", CanvasFieldKind.STRING, fontFamilies),
        CanvasFieldSpec("fontSize", CanvasFieldKind.NUMBER),
        CanvasFieldSpec("alignment", CanvasFieldKind.STRING, alignments),
        CanvasFieldSpec("textTopLeft", CanvasFieldKind.POINT),
        CanvasFieldSpec("wrapWidth", CanvasFieldKind.NUMBER),
        CanvasFieldSpec("textColor", CanvasFieldKind.COLOR),
    ).associateBy { it.name }

    val shape = CanvasElementSpec(
        type = "Shape",
        note = "points are two \"x,y\" corners (RECTANGLE, TRIANGLE), the ends of a LINE/ARROW, " +
            "or the ends of a CIRCLE's diameter; text is a label drawn inside; an ARROW's " +
            "startBinding/endBinding name the shapes it connects",
        required = listOf("shapeType", "points"),
        defaults = drawnDefaults(strokeWidth = 2.0),
        optional = listOf(
            "fillColor", "cornerRadius", "strokeStyle", "strokeEnabled", "rotation", "bend", "startBinding",
            "endBinding", "text", "textColor", "fontSize", "fontFamilyKey", "alignment",
        ),
        example = example("box-1", "Shape") {
            put("shapeType", "RECTANGLE")
            put("points", points("100.0,100.0", "400.0,260.0"))
            put("strokeColor", "#1e88e5ff")
            put("strokeWidth", 3.0)
            put("fillColor", "#bbdefbff")
            put("cornerRadius", 16.0)
            put("text", "Plan")
            put("zIndex", 1)
        },
        minPoints = 2,
    )

    val text = CanvasElementSpec(
        type = "Text",
        note = "textTopLeft is the top-left corner of the wrap box; strokeColor is the text colour",
        required = listOf("text", "textTopLeft"),
        defaults = drawnDefaults(strokeWidth = 0.0) + mapOf("points" to JsonArray(emptyList())),
        optional = listOf("fontSize", "wrapWidth", "alignment", "fontFamilyKey", "opacity", "rotation"),
        example = example("title-1", "Text") {
            put("text", "Weekly plan")
            put("textTopLeft", "100.0,30.0")
            put("wrapWidth", 480.0)
            put("fontSize", 36.0)
            put("alignment", "LEFT")
            put("fontFamilyKey", "sans")
            put("strokeColor", "#212121ff")
            put("zIndex", 2)
        },
    )

    val path = CanvasElementSpec(
        type = "Path",
        note = "a freehand stroke; samples are \"x,y,width\" points along it",
        required = listOf("samples"),
        defaults = drawnDefaults(strokeWidth = 4.0) + mapOf("points" to JsonArray(emptyList())),
        optional = listOf("alpha", "strokeStyle", "rotation"),
        example = example("stroke-1", "Path") {
            put("samples", points("100.0,320.0,4.0", "160.0,340.0,4.0", "220.0,320.0,4.0"))
            put("strokeColor", "#e53935ff")
            put("strokeWidth", 4.0)
            put("zIndex", 3)
        },
    )

    val image = CanvasElementSpec(
        type = "Image",
        note = "points are the top-left and bottom-right corners; imageRef must name an image " +
            "already on the board (copy it from canvas.get_scene)",
        required = listOf("points", "imageRef"),
        defaults = drawnDefaults(strokeWidth = 0.0),
        optional = listOf("imageMediaType", "intrinsicWidth", "intrinsicHeight", "opacity", "rotation"),
        example = example("image-1", "Image") {
            put("points", points("100.0,400.0", "420.0,640.0"))
            put("imageRef", "<imageRef from canvas.get_scene>")
            put("zIndex", 0)
        },
        minPoints = 2,
    )

    val elementTypes: List<CanvasElementSpec> = listOf(shape, text, path, image)

    val allowedTypes: List<String> = elementTypes.map { it.type }

    /** The spec for [type], matched without regard to case, or null for a type nothing draws. */
    fun spec(type: String): CanvasElementSpec? = elementTypes.firstOrNull { it.type.equals(type, ignoreCase = true) }

    /** The type a writer most likely meant by [unknown], for its example. */
    fun closestType(unknown: String): CanvasElementSpec {
        val word = unknown.lowercase()
        return when {
            TEXT_WORDS.any { it in word } -> text
            PATH_WORDS.any { it in word } -> path
            IMAGE_WORDS.any { it in word } -> image
            else -> shape
        }
    }

    /** A whole valid scene: a labelled box and a title. */
    val sceneExample: JsonObject = buildJsonObject {
        put("bgColor", DEFAULT_BG_COLOR)
        put("elements", JsonArray(listOf(shape.example, text.example)))
    }

    /** The format in full, for the tool descriptions. */
    val description: String by lazy { CanvasSceneSchemaText.full(this) }

    /** The format in a line, returned with every scene read. */
    val hint: String by lazy { CanvasSceneSchemaText.hint(this) }

    private val TEXT_WORDS = listOf("text", "label", "title", "heading", "document", "note", "doc", "caption", "sticky")
    private val PATH_WORDS = listOf("path", "stroke", "pen", "draw", "freehand", "scribble", "ink")
    private val IMAGE_WORDS = listOf("image", "picture", "photo", "img")

    private fun drawnDefaults(strokeWidth: Double): Map<String, JsonElement> = mapOf(
        "zIndex" to JsonPrimitive(0),
        "strokeColor" to JsonPrimitive("#000000ff"),
        "strokeWidth" to JsonPrimitive(strokeWidth),
    )

    private fun points(vararg values: String): JsonArray = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }

    private fun example(
        id: String,
        type: String,
        body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): JsonObject = buildJsonObject {
        put("id", id)
        put("type", type)
        body()
    }
}
