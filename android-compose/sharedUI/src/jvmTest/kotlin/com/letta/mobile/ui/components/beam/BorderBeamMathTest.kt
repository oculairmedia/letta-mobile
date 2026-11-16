package com.letta.mobile.ui.components.beam

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BorderBeamMathTest {
    @Test
    fun anEmptyPaletteBecomesOneWhite() {
        assertEquals(listOf(Color.White), fitBeamPalette(emptyList()))
    }

    @Test
    fun aLongPaletteIsThinnedAndKeepsItsTailAndHead() {
        val colors = List(20) { Color(red = it / 20f, green = 0f, blue = 0f) }
        val fitted = fitBeamPalette(colors)
        assertEquals(MAX_BEAM_COLORS, fitted.size)
        assertEquals(colors.first(), fitted.first())
        assertEquals(colors.last(), fitted.last())
    }

    @Test
    fun thePackedPaletteIsPaddedForTheShaderArray() {
        val packed = packBeamPalette(listOf(Color.Red, Color.Blue))
        assertEquals(MAX_BEAM_COLORS * 4, packed.size)
        assertEquals(listOf(1f, 0f, 0f, 1f, 0f, 0f, 1f, 1f), packed.take(8))
        assertTrue(packed.drop(8).all { it == 0f }, "unused slots stay zero")
    }

    @Test
    fun samplingBlendsBetweenNeighbours() {
        val palette = listOf(Color.Black, Color.White)
        assertEquals(Color.Black, sampleBeamPalette(palette, 0f))
        assertEquals(Color.White, sampleBeamPalette(palette, 1f))
        val middle = sampleBeamPalette(palette, 0.5f).red
        assertTrue(middle > 0.2f && middle < 0.8f, "halfway is a mid grey (Compose blends in Oklab), was $middle")
        assertEquals(Color.White, sampleBeamPalette(palette, 3f), "t is clamped")
    }

    @Test
    fun theBellIsDarkAtBothEndsAndBrightInTheMiddle() {
        assertEquals(0f, beamBell(0f, 1.2f), 1e-4f)
        assertEquals(1f, beamBell(0.5f, 1.2f), 1e-4f)
        assertEquals(0f, beamBell(1f, 1.2f), 1e-4f)
    }

    @Test
    fun aFrameAdvancesThePhaseByItsShareOfTheLap() {
        val sixtieth = 16_666_667L
        assertEquals(1f / 60f / 2.4f, advanceBeamPhase(0f, sixtieth, periodMillis = 2400), 1e-5f)
        assertEquals(0.05f, advanceBeamPhase(0.95f, 100_000_000L, periodMillis = 1000), 1e-4f, "wraps past one lap")
    }

    @Test
    fun aLongPauseResumesInPlaceRatherThanJumping() {
        val afterStall = advanceBeamPhase(0.2f, 5_000_000_000L, periodMillis = 2000)
        assertEquals(0.25f, afterStall, 1e-4f, "a five-second gap counts as a tenth of a second")
    }

    @Test
    fun theSweepGoesThereAndBackEasedAtBothEnds() {
        assertEquals(0f, sweepPosition(0f), 1e-5f)
        assertEquals(1f, sweepPosition(0.5f), 1e-5f)
        assertEquals(0f, sweepPosition(1f), 1e-5f)
        assertEquals(sweepPosition(0.2f), sweepPosition(0.8f), 1e-5f)
        assertTrue(sweepPosition(0.05f) < 0.05f, "eases in")
    }

    @Test
    fun aSingleColourBeamFadesAtBothEnds() {
        val beam = LettaBeamDefaults.single(Color.Cyan)
        assertEquals(3, beam.size)
        assertEquals(Color.Cyan, beam[1])
        assertTrue(beam.first().alpha < 1f && beam.first() == beam.last())
    }
}
