package com.letta.mobile.ui.chat.surface.ambient

import org.jetbrains.skia.RuntimeEffect
import kotlin.test.Test

/**
 * The voice band's wave falls back to a plain gradient when its shader does not compile, so a
 * syntax error would ship as "the band looks flat". Compiling the shared source here with both
 * platform mains turns that into a test failure, as AmbientShaderCompilesTest does for the thinking
 * glow. Skia parses SkSL without a GPU, so this runs headless; AGSL accepts the same source.
 */
class VoiceWaveShaderCompilesTest {
    @Test
    fun theVoiceWaveCompilesForBothPlatformMains() {
        listOf(
            "premultiplied" to VOICE_WAVE_MAIN_PREMULTIPLIED,
            "unpremultiplied" to VOICE_WAVE_MAIN_UNPREMULTIPLIED,
        ).forEach { (name, main) ->
            val effect = runCatching { RuntimeEffect.makeForShader(VOICE_WAVE_SHADER_SOURCE + main) }
            check(effect.isSuccess) { "$name main failed to compile: ${effect.exceptionOrNull()?.message}" }
            effect.getOrThrow().close()
        }
    }

    @Test
    fun theDesktopShaderIsCreated() {
        val shader = checkNotNull(createVoiceWaveShader()) { "the Skia wave did not compile" }
        shader.release()
        shader.release()
    }
}
