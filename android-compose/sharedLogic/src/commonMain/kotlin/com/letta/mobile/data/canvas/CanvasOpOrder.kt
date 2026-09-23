package com.letta.mobile.data.canvas

/**
 * The one total order on canvas ops: (lamport, actorId, opId).
 *
 * Projection settles every conflict by it (last writer wins) and replay applies batches in it, so
 * every client reaches the same scene whatever order ops arrived in. The op id is the final
 * tie-break because every device edits as the same user actor with its own Lamport clock: two
 * devices can write at an equal (lamport, actorId), and without it the later arrival would win -
 * a different winner on each device.
 */
object CanvasOpOrder : Comparator<CanvasOp> {
    override fun compare(a: CanvasOp, b: CanvasOp): Int =
        compare(a.lamport, a.actorId, a.opId, b.lamport, b.actorId, b.opId)

    fun compare(
        lamportA: Long,
        actorA: String,
        opIdA: String,
        lamportB: Long,
        actorB: String,
        opIdB: String,
    ): Int = when {
        lamportA != lamportB -> lamportA.compareTo(lamportB)
        actorA != actorB -> actorA.compareTo(actorB)
        else -> opIdA.compareTo(opIdB)
    }
}

/**
 * A short, stable fingerprint of a scene, for comparing what two clients hold once they settle:
 * FNV-1a (64-bit) over the canonical scene string the projector writes. Not a security hash.
 */
object CanvasSceneDigest {
    fun of(sceneJson: String): String {
        var hash = FNV_OFFSET
        for (byte in sceneJson.encodeToByteArray()) {
            hash = hash xor (byte.toLong() and 0xff)
            hash *= FNV_PRIME
        }
        return hash.toULong().toString(16).padStart(16, '0')
    }

    private const val FNV_OFFSET = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
    private const val FNV_PRIME = 0x100000001b3L
}
