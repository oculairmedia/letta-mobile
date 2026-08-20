package com.letta.mobile.util

import android.graphics.Color
import com.google.android.material.color.utilities.Hct
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Tag

@Tag("unit")
class ColorHarmonizerTest : FunSpec({

    val harmonizer = ColorHarmonizer()

    context("harmonizeColor") {
        test("should return a valid ARGB color") {
            val sourceColor = Color.rgb(255, 100, 50)
            val result = harmonizer.harmonizeColor(sourceColor)
            result shouldNotBe 0
            result shouldNotBe Color.TRANSPARENT
        }

        test("should respect target tone when specified") {
            val sourceColor = Color.rgb(255, 100, 50)
            val targetTone = 80
            val result = harmonizer.harmonizeColor(sourceColor, targetTone)
            val resultHct = Hct.fromInt(result)
            // HCT solver can drift ±1 tone after gamut mapping
            resultHct.tone.toInt() shouldBeInRange (targetTone - 1)..(targetTone + 1)
        }

        test("should handle light colors") {
            val lightColor = Color.rgb(200, 200, 200)
            val result = harmonizer.harmonizeColor(lightColor)
            result shouldNotBe 0
        }

        test("should handle dark colors") {
            val darkColor = Color.rgb(50, 50, 50)
            val result = harmonizer.harmonizeColor(darkColor)
            result shouldNotBe 0
        }
    }

    context("blendColors") {
        test("should blend two colors") {
            val color1 = Color.rgb(255, 0, 0)
            val color2 = Color.rgb(0, 0, 255)
            val blended = harmonizer.blendColors(color1, color2, 0.5)
            blended shouldNotBe color1
            blended shouldNotBe color2
        }

        test("should return color1 when t=0") {
            val color1 = Color.rgb(255, 0, 0)
            val color2 = Color.rgb(0, 0, 255)
            val result = harmonizer.blendColors(color1, color2, 0.0)
            val resultHct = Hct.fromInt(result)
            val color1Hct = Hct.fromInt(color1)
            kotlin.math.abs(resultHct.hue - color1Hct.hue) shouldBeLessThan 1.0
        }

        test("should return color2 when t=1") {
            val color1 = Color.rgb(255, 0, 0)
            val color2 = Color.rgb(0, 0, 255)
            val result = harmonizer.blendColors(color1, color2, 1.0)
            val resultHct = Hct.fromInt(result)
            val color2Hct = Hct.fromInt(color2)
            kotlin.math.abs(resultHct.hue - color2Hct.hue) shouldBeLessThan 1.0
        }
    }

    context("harmonizeHue") {
        test("should harmonize hue between two colors") {
            val sourceColor = Color.rgb(255, 0, 0)
            val targetColor = Color.rgb(0, 255, 0)
            val result = harmonizer.harmonizeHue(sourceColor, targetColor)
            result shouldNotBe sourceColor
        }

        test("should respect Material's 15° hue rotation constraint") {
            val sourceColor = Color.rgb(255, 0, 0)
            val targetColor = Color.rgb(0, 255, 0)
            val result = harmonizer.harmonizeHue(sourceColor, targetColor)

            val sourceHct = Hct.fromInt(sourceColor)
            val resultHct = Hct.fromInt(result)

            val hueDifference = kotlin.math.abs(resultHct.hue - sourceHct.hue)
            hueDifference shouldBeLessThan 16.0
        }
    }
})
