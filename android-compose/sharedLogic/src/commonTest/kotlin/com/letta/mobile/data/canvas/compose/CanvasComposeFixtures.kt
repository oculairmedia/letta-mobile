package com.letta.mobile.data.canvas.compose

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Fixture texts for the reserve estimator (letta-mobile-bglj6.9) and the height each one books,
 * pinned. The auto-fit renderer's measurement test (C6, letta-mobile-bglj6.11) renders the same
 * documents for real and asserts `reserve >= measured` for every one; the pinned value here is the
 * reserve it compares against, so a change to the estimator shows up in both places.
 *
 * Documents are Cascade v2 JSON (plan section 3.2), the stored form; no markdown codec involved.
 */
object CanvasComposeFixtures {
    /** One note fixture: a Cascade document at [width] and the height the estimator books for it. */
    data class NoteCase(val name: String, val kind: ComposeKind, val documentJson: String, val width: Float, val reserve: Float)

    /** One TEXT fixture. */
    data class TextCase(val name: String, val text: String, val size: ComposeTextSize, val reserve: Float)

    /** A block for [document]: its Cascade `typeId`, text, optional level, checked state and children. */
    data class B(
        val typeId: String,
        val text: String = "",
        val level: Int? = null,
        val checked: Boolean? = null,
        val children: List<B> = emptyList(),
        /** `attributes.indentationLevel`, how cascade-editor and the compiler store a nested list item (a flat list). */
        val indent: Int = 0,
    )

    /** A Cascade v2 document of [blocks], ids `b1`, `b2`, ... depth-first. */
    fun document(vararg blocks: B): String {
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
                        if (block.indent > 0) put("attributes", buildJsonObject { put("indentationLevel", block.indent) })
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
            put("blocks", encode(blocks.toList()))
        }.toString()
    }

    private const val LOREM = "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua."

    val notes: List<NoteCase> = listOf(
        NoteCase("empty", ComposeKind.NOTE, document(), 320f, 120f),
        NoteCase("one word", ComposeKind.NOTE, document(B("paragraph", "Hello")), 320f, 120f),
        NoteCase(
            "weekend meals",
            ComposeKind.NOTE,
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
            ComposeKind.CHECKLIST,
            document(B("todo", "Milk", checked = false), B("todo", "Eggs", checked = true), B("todo", "Bread", checked = false)),
            320f,
            175f,
        ),
        NoteCase(
            "card with fields",
            ComposeKind.CARD,
            document(B("heading", "Walk", level = 2), B("paragraph", "When: Sat 09:00"), B("paragraph", "Where: Park")),
            320f,
            196f,
        ),
        NoteCase("long paragraph", ComposeKind.NOTE, document(B("paragraph", "$LOREM $LOREM $LOREM")), 320f, 516f),
        NoteCase("capitals", ComposeKind.NOTE, document(B("paragraph", "WORLD WIDE WEB MOMENTUM ".repeat(6).trim())), 320f, 295f),
        NoteCase("unbroken url", ComposeKind.NOTE, document(B("paragraph", "https://example.com/" + "a".repeat(180))), 320f, 295f),
        NoteCase("cjk", ComposeKind.NOTE, document(B("paragraph", "漢字かな交じり文".repeat(8))), 320f, 212f),
        NoteCase("code", ComposeKind.NOTE, document(B("code", "fun main() {\n    println(\"hello\")\n}")), 320f, 165f),
        NoteCase(
            "nested lists",
            ComposeKind.NOTE,
            document(
                // Nested as cascade-editor and the compose compiler store it: a flat list, the child indented.
                B("bullet_list", "Groceries for the week ahead"),
                B("bullet_list", "Apples and pears from the market", indent = 1),
                B("numbered_list", "Call the plumber about the kitchen sink"),
                B("quote", "Simplicity is prerequisite for reliability."),
                B("divider"),
            ),
            320f,
            387f,
        ),
        NoteCase("cap", ComposeKind.NOTE, document(*Array(60) { B("paragraph", LOREM) }), 320f, 1200f),
    )

    val texts: List<TextCase> = listOf(
        TextCase("heading", "Weekend plan", ComposeTextSize.HEADING, 63f),
        TextCase("body", "A short caption", ComposeTextSize.BODY, 32f),
        TextCase("long heading", "Everything we need to decide before the trip next month", ComposeTextSize.HEADING, 187f),
    )
}
