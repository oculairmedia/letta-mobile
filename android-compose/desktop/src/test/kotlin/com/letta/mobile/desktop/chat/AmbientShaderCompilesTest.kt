package com.letta.mobile.desktop.chat

import com.letta.mobile.ui.ambient.AMBIENT_GLOW_MAIN_PREMULTIPLIED
import com.letta.mobile.ui.ambient.AMBIENT_GLOW_MAIN_UNPREMULTIPLIED
import com.letta.mobile.ui.ambient.AMBIENT_GLOW_SHADER_SOURCE
import org.jetbrains.skia.RuntimeEffect
import kotlin.test.Test

/**
 * A shader that fails to compile does not crash: both hosts catch it and fall back to a
 * plain gradient, so a syntax error ships as "the glow looks wrong" and nothing else.
 * Compiling the real source here turns that into a build failure instead. Skia parses
 * SkSL without a GPU context, so this runs headless.
 */
class AmbientShaderCompilesTest {
    @Test
    fun theAmbientShaderCompilesForBothPlatformMains() {
        listOf(
            "premultiplied" to AMBIENT_GLOW_MAIN_PREMULTIPLIED,
            "unpremultiplied" to AMBIENT_GLOW_MAIN_UNPREMULTIPLIED,
        ).forEach { (name, main) ->
            val effect = runCatching { RuntimeEffect.makeForShader(AMBIENT_GLOW_SHADER_SOURCE + main) }
            check(effect.isSuccess) { "$name main failed to compile: ${effect.exceptionOrNull()?.message}" }
            effect.getOrThrow().close()
        }
    }
}
