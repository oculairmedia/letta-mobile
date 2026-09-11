package com.letta.mobile.ui.theme

import com.google.android.material.color.utilities.Hct
import org.junit.Test

class AmbientTintProbeTest {
    @Test
    fun printAchievableChromaPerToneForRealHues() {
        val hues = listOf("teal" to 174.0, "violet" to 300.0, "indigo" to 277.0, "pink" to 350.0, "blue" to 250.0)
        hues.forEach { (name, hue) ->
            val at30 = Hct.from(hue, 72.0, 30.0)
            val best = (20..70).maxByOrNull { tone -> Hct.from(hue, 72.0, tone.toDouble()).chroma }!!
            val bestHct = Hct.from(hue, 72.0, best.toDouble())
            println(
                "$name hue=$hue  requested chroma 72 at tone 30 -> ${"%.1f".format(at30.chroma)}" +
                    "  | best tone $best -> ${"%.1f".format(bestHct.chroma)}",
            )
        }
    }
}
