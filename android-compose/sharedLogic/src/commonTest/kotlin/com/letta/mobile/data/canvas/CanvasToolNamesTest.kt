package com.letta.mobile.data.canvas

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Model providers reject a tool whose name is not letters, digits, `_` or `-` (at most 64). A
 * dotted `canvas_get_scene` was refused by the providers, so every agent run that carried the
 * canvas tools failed. Every canvas tool name, offered or not, must pass.
 */
class CanvasToolNamesTest {
    private val providerSafe = Regex("^[a-zA-Z0-9_-]{1,64}$")

    @Test
    fun everyCanvasToolNameIsOneEveryProviderAccepts() {
        val names = listOf(
            CanvasToolContract.CREATE,
            CanvasToolContract.GET_SCENE,
            CanvasToolContract.REPLACE_SCENE,
            CanvasToolContract.APPLY_OPS,
            CanvasToolContract.EXPORT_SVG,
            CanvasToolContract.LIST,
            CanvasToolContract.RENDER_PREVIEW,
        ) + CanvasToolContract.all.map { it.name }
        names.forEach { name -> assertTrue(providerSafe.matches(name), "provider-unsafe tool name: $name") }
    }
}
