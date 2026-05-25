package com.letta.mobile.data.attachment

/**
 * Tunable caps for image attachments. Defaults follow vision-input guidance
 * (<= 1568 px longest side, <= about 2 MB per image, <= 4 images per turn)
 * which keeps payloads comfortably under the admin-shim's 10 MB
 * `content_parts` hard cap.
 *
 * Platform apps can inject or store this value however they need; [Default]
 * is the shared baseline.
 *
 * @param maxAttachmentCount hard count cap per send.
 * @param maxLongestEdgePx downscale target for the longest edge.
 * @param maxRawBytesPerImage post-encode byte cap per image. The encoder
 * loops down from [initialJpegQuality] to [minJpegQuality] in
 * [jpegQualityStep] increments until the bytes fit.
 * @param maxTotalBase64Bytes cumulative cap on staged attachments.
 * @param initialJpegQuality first JPEG quality the encoder tries.
 * @param minJpegQuality floor for the quality-fallback loop.
 * @param jpegQualityStep decrement per loop iteration.
 */
data class AttachmentLimits(
    val maxAttachmentCount: Int = 4,
    val maxLongestEdgePx: Int = 1568,
    val maxRawBytesPerImage: Int = 2 * 1024 * 1024,
    val maxTotalBase64Bytes: Int = 8 * 1024 * 1024,
    val initialJpegQuality: Int = 85,
    val minJpegQuality: Int = 50,
    val jpegQualityStep: Int = 10,
) {
    init {
        require(maxAttachmentCount > 0) { "maxAttachmentCount must be > 0" }
        require(maxLongestEdgePx > 0) { "maxLongestEdgePx must be > 0" }
        require(maxRawBytesPerImage > 0) { "maxRawBytesPerImage must be > 0" }
        require(maxTotalBase64Bytes > 0) { "maxTotalBase64Bytes must be > 0" }
        require(initialJpegQuality in 1..100) { "initialJpegQuality must be in 1..100" }
        require(minJpegQuality in 1..100) { "minJpegQuality must be in 1..100" }
        require(minJpegQuality <= initialJpegQuality) {
            "minJpegQuality ($minJpegQuality) must be <= initialJpegQuality ($initialJpegQuality)"
        }
        require(jpegQualityStep > 0) { "jpegQualityStep must be > 0" }
    }

    /**
     * Yields JPEG quality settings to try in order, from [initialJpegQuality]
     * down to [minJpegQuality]. The last value is always exactly
     * [minJpegQuality] so the floor is honored even when the step does not
     * divide the range cleanly.
     */
    fun jpegQualityFallbackLadder(): List<Int> = buildList {
        var quality = initialJpegQuality
        while (quality > minJpegQuality) {
            add(quality)
            quality -= jpegQualityStep
        }
        add(minJpegQuality)
    }

    companion object {
        val Default = AttachmentLimits()
    }
}
