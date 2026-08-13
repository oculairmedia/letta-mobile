package com.letta.mobile.ui.screens.pairing

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BinaryBitmap
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * CameraX [ImageAnalysis.Analyzer] that decodes QR codes with ZXing's
 * `core` artifact (letta-mobile-g2d2i).
 *
 * Deliberately NOT ML Kit barcode scanning and NOT `zxing:javase`:
 *  - ML Kit either bundles a model (APK bloat, all flavors pay for it) or
 *    requires Google Play Services (unbundled) — the Root/Sideload flavors
 *    cannot assume Play Services is present.
 *  - `zxing:javase` pulls in `jai-imageio`, which references
 *    `javax.imageio.spi.*` classes that don't exist on Android and currently
 *    break R8 on `main`. `zxing:core` is pure Java with no AWT/ImageIO
 *    dependency and is already used by [com.letta.mobile.qr.QrCode].
 *
 * Only the Y (luma) plane of the YUV_420_888 frame is read — QR decoding
 * only needs luminance, not chroma, and zxing's [PlanarYUVLuminanceSource]
 * is built exactly for this: a single 8-bit-per-pixel plane with a known
 * row stride.
 */
class QrScanAnalyzer(
    private val onDecoded: (String) -> Unit,
) : ImageAnalysis.Analyzer {

    // QRCodeReader is not documented as thread-safe for concurrent decode()
    // calls, but ImageAnalysis with STRATEGY_KEEP_ONLY_LATEST invokes analyze()
    // serially on its executor, so a single shared instance is safe here.
    private val reader = QRCodeReader()

    override fun analyze(image: ImageProxy) {
        try {
            val decodedText = decodeYPlane(image)
            if (decodedText != null) onDecoded(decodedText)
        } catch (_: Exception) {
            // Any per-frame decode failure is the common case (most frames
            // don't contain a readable code, or the frame is mid-motion-blur)
            // — never let it crash the analyzer loop.
        } finally {
            image.close()
        }
    }

    private fun decodeYPlane(image: ImageProxy): String? {
        val yPlane = image.planes[0]
        val buffer = yPlane.buffer
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        val source = PlanarYUVLuminanceSource(
            data,
            // The Y plane's rowStride (bytes/row) is used as the source's
            // logical width: Y has pixelStride == 1, so rowStride already
            // accounts for any row padding beyond the visible image width.
            yPlane.rowStride,
            image.height,
            0,
            0,
            image.width,
            image.height,
            false,
        )
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        return try {
            reader.decode(bitmap).text
        } catch (_: NotFoundException) {
            null
        } finally {
            reader.reset()
        }
    }
}
