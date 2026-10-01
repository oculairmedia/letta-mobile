package com.letta.mobile.ui.canvas

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The reserve estimator's note fixtures, copied from sharedLogic commonTest `CanvasComposeFixtures`
 * (letta-mobile-bglj6.9), which this module's tests cannot see. Same documents, same pinned
 * reserves: [CanvasNoteAutoFitTest] asserts each pinned reserve still equals what
 * `CanvasComposeReserve.reserveDocument` books, so an estimator change that is not copied here
 * fails in this module too, and [CanvasNoteAutoFitUiTest] renders every one for real and holds the
 * reserve to the measured height (the estimator gate of plan section 3.4).
 */
internal object CanvasNoteAutoFitFixtures {
    /** One note fixture: a Cascade document at [width] and the height the estimator books for it. */
    data class NoteCase(val name: String, val documentJson: String, val width: Float, val reserve: Float)

    /** A block for [document]: its Cascade `typeId`, text, optional level, checked state and children. */
    data class B(
        val typeId: String,
        val text: String = "",
        val level: Int? = null,
        val checked: Boolean? = null,
        val children: List<B> = emptyList(),
    )

    /** A Cascade v2 document of [blocks], ids `b1`, `b2`, ... depth-first. */
    fun document(vararg blocks: B): String = document(blocks.toList())

    fun document(blocks: List<B>): String {
        var next = 0
        fun encode(list: List<B>): JsonArray = buildJsonArray {
            list.forEach { block ->
                next++
                add(
                    buildJsonObject {
                        put("id", "b$next")
                        put(
                            "type",
                            buildJsonObject {
                                // cascade-editor 1.9.2 writes a heading's level into its typeId
                                // (`heading_2`), not as a separate property (letta-mobile-bglj6.11).
                                if (block.typeId == "heading" && block.level != null) {
                                    put("typeId", "heading_${block.level}")
                                } else {
                                    put("typeId", block.typeId)
                                    block.level?.let { put("level", it) }
                                }
                                block.checked?.let { put("checked", it) }
                            },
                        )
                        put(
                            "content",
                            buildJsonObject {
                                put("kind", "text")
                                put("version", 1)
                                put("text", block.text)
                                put("spans", JsonArray(emptyList()))
                            },
                        )
                        if (block.children.isNotEmpty()) put("children", encode(block.children))
                    },
                )
            }
        }
        return buildJsonObject {
            put("version", JsonPrimitive(2))
            put("blocks", encode(blocks))
        }.toString()
    }

    private const val LOREM = "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua."

    /** The case the estimator books at its cap on purpose (a note longer than the cap scrolls or grows). */
    const val CAP_CASE = "cap"

    val notes: List<NoteCase> = listOf(
        NoteCase("empty", document(), 320f, 120f),
        NoteCase("one word", document(B("paragraph", "Hello")), 320f, 120f),
        NoteCase(
            "weekend meals",
            document(
                B("heading", "Saturday", level = 2),
                B("bullet_list", "Pasta"),
                B("heading", "Sunday", level = 2),
                B("bullet_list", "Roast"),
                B("todo", "Buy a chicken", checked = false),
            ),
            320f,
            290f,
        ),
        NoteCase(
            "shopping checklist",
            document(B("todo", "Milk", checked = false), B("todo", "Eggs", checked = true), B("todo", "Bread", checked = false)),
            320f,
            175f,
        ),
        NoteCase(
            "card with fields",
            document(B("heading", "Walk", level = 2), B("paragraph", "When: Sat 09:00"), B("paragraph", "Where: Park")),
            320f,
            196f,
        ),
        NoteCase("long paragraph", document(B("paragraph", "$LOREM $LOREM $LOREM")), 320f, 516f),
        NoteCase("capitals", document(B("paragraph", "WORLD WIDE WEB MOMENTUM ".repeat(6).trim())), 320f, 295f),
        NoteCase("unbroken url", document(B("paragraph", "https://example.com/" + "a".repeat(180))), 320f, 295f),
        NoteCase("cjk", document(B("paragraph", "漢字かな交じり文".repeat(8))), 320f, 212f),
        NoteCase("code", document(B("code", "fun main() {\n    println(\"hello\")\n}")), 320f, 165f),
        NoteCase(
            "nested lists",
            document(
                B("bullet_list", "Groceries for the week ahead", children = listOf(B("bullet_list", "Apples and pears from the market"))),
                B("numbered_list", "Call the plumber about the kitchen sink"),
                B("quote", "Simplicity is prerequisite for reliability."),
                B("divider"),
            ),
            320f,
            387f,
        ),
        NoteCase(CAP_CASE, document(List(60) { B("paragraph", LOREM) }), 320f, 1200f),
    )

    /** letta-mobile-8tlf9's first shape: a 12-item shopping checklist that clipped at 480x500. */
    val shoppingList: String = document(
        List(12) { i -> B("todo", "Shopping item ${i + 1}: " + listOf("milk", "eggs", "bread", "apples")[i % 4], checked = false) },
    )

    /** letta-mobile-8tlf9's second shape: a meal plan, day headings with to-dos under each (12 blocks). */
    val mealPlan: String = document(
        listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday").flatMapIndexed { i, day ->
            if (i < 2) {
                listOf(B("heading", day, level = 3), B("todo", "$day dinner: pasta bake", checked = false), B("todo", "$day lunch: soup", checked = false))
            } else {
                listOf(B("heading", day, level = 3), B("todo", "$day dinner: tacos", checked = false))
            }
        }.take(12),
    )
}
