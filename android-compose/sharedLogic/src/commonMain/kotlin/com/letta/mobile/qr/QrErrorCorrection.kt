package com.letta.mobile.qr

/**
 * QR error-correction level: the share of codewords that may be damaged and still recovered.
 * Higher levels survive more damage at the cost of capacity.
 */
enum class QrErrorCorrection(internal val formatBits: Int) {
    /** About 7% of codewords recoverable. */
    L(1),

    /** About 15% of codewords recoverable. */
    M(0),

    /** About 25% of codewords recoverable. */
    Q(3),

    /** About 30% of codewords recoverable. */
    H(2),
}
