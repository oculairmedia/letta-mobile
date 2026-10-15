package com.letta.mobile.data.meridian.endpoint

/**
 * True when [actual] equals the secret [expected], comparing every byte whatever the first
 * difference, so a caller probing tokens learns nothing from the answer's timing.
 */
internal fun constantTimeEquals(expected: String, actual: String): Boolean {
    val a = expected.encodeToByteArray()
    val b = actual.encodeToByteArray()
    var diff = a.size xor b.size
    for (i in a.indices) diff = diff or (a[i].toInt() xor b.getOrElse(i) { 0 }.toInt())
    return diff == 0
}
