package com.letta.mobile.data.attachment

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AttachmentLimitsTest {

    @Test
    fun defaultMatchesVisionInputGuidance() {
        assertEquals(4, AttachmentLimits.Default.maxAttachmentCount)
        assertEquals(1568, AttachmentLimits.Default.maxLongestEdgePx)
        assertEquals(2 * 1024 * 1024, AttachmentLimits.Default.maxRawBytesPerImage)
    }

    @Test
    fun defaultRawBytesStayUnderShimContentPartsCapAssumption() {
        val rawTotal = AttachmentLimits.Default.maxAttachmentCount.toLong() *
            AttachmentLimits.Default.maxRawBytesPerImage.toLong()

        assertEquals(8L * 1024 * 1024, rawTotal)
    }

    @Test
    fun qualityFallbackLadderStepsFromInitialToFloor() {
        val limits = AttachmentLimits(
            initialJpegQuality = 85,
            minJpegQuality = 50,
            jpegQualityStep = 10,
        )

        assertContentEquals(listOf(85, 75, 65, 55, 50), limits.jpegQualityFallbackLadder())
    }

    @Test
    fun qualityFallbackLadderYieldsInitialAndFloorWhenInitialEqualsFloorPlusStep() {
        val limits = AttachmentLimits(
            initialJpegQuality = 60,
            minJpegQuality = 50,
            jpegQualityStep = 10,
        )

        assertContentEquals(listOf(60, 50), limits.jpegQualityFallbackLadder())
    }

    @Test
    fun qualityFallbackLadderYieldsFloorWhenInitialEqualsFloor() {
        val limits = AttachmentLimits(
            initialJpegQuality = 70,
            minJpegQuality = 70,
            jpegQualityStep = 10,
        )

        assertContentEquals(listOf(70), limits.jpegQualityFallbackLadder())
    }

    @Test
    fun qualityFallbackLadderHonoursFloorWhenStepDoesNotDivideRange() {
        val limits = AttachmentLimits(
            initialJpegQuality = 90,
            minJpegQuality = 55,
            jpegQualityStep = 20,
        )

        assertContentEquals(listOf(90, 70, 55), limits.jpegQualityFallbackLadder())
    }

    @Test
    fun constructorRejectsZeroOrNegativeCount() {
        assertFailsWith<IllegalArgumentException> {
            AttachmentLimits(maxAttachmentCount = 0)
        }
    }

    @Test
    fun constructorRejectsMinJpegQualityGreaterThanInitialJpegQuality() {
        assertFailsWith<IllegalArgumentException> {
            AttachmentLimits(initialJpegQuality = 50, minJpegQuality = 60)
        }
    }
}
