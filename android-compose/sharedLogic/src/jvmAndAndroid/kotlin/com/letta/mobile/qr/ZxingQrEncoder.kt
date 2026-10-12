package com.letta.mobile.qr

import com.letta.mobile.data.pairing.QrEncoder
import com.letta.mobile.data.pairing.QrModules

/** [QrEncoder] for Android and desktop, over the zxing-backed [QrCode] (letta-mobile-dpv7m.2). */
object ZxingQrEncoder : QrEncoder {
    override fun encode(content: String): QrModules {
        val matrix = QrCode.encode(content)
        return QrModules(matrix.size) { row, col -> matrix[row, col] }
    }
}
