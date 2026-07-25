package com.letta.mobile.data.attachment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImageIngressPolicyTest {
    @Test
    fun acceptsSupportedExtensionsCaseInsensitively() {
        listOf("png", "PNG", "jpg", "JPEG", "webp", "gif", "bmp").forEach {
            assertTrue(ImageIngressPolicy.isSupportedExtension(it), "expected $it to be supported")
        }
    }

    @Test
    fun rejectsNonImageExtensions() {
        listOf("pdf", "kt", "txt", "svg", "", "jpg.exe").forEach {
            assertFalse(ImageIngressPolicy.isSupportedExtension(it), "expected $it to be rejected")
        }
    }

    @Test
    fun jpegIsSupportedAndAdvertised() {
        // Regression: the drop-hint copy once omitted .jpeg while the filter
        // accepted it, so users were told a valid format was unsupported.
        assertTrue(ImageIngressPolicy.isSupportedExtension("jpeg"))
        assertTrue(ImageIngressPolicy.supportedFormatsLabel().contains("jpeg"))
    }

    @Test
    fun everySupportedExtensionAppearsInTheLabel() {
        val label = ImageIngressPolicy.supportedFormatsLabel()
        ImageIngressPolicy.SUPPORTED_EXTENSIONS.forEach {
            assertTrue(label.contains(it), "label is missing $it")
        }
    }

    @Test
    fun capMatchesTheComposerAttachmentGrid() {
        assertEquals(4, ImageIngressPolicy.MAX_FILES)
    }
}
