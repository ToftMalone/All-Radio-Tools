package com.allnetworktools.data.sdr

/**
 * Mending a frame that failed its check from soft decisions (a light Chase decoder): when each bit was read, how far it
 * fell from the decision threshold was kept; a weak signal mostly loses a frame to one or two marginal bits, the ones a
 * noise spike nudged across the threshold. Those are flipped, one then two at a time, and the frame's own check (a
 * CRC-16 or stronger) decides.
 *
 * Guards, so that mending wins frames without forging any:
 * - Noise is refused outright: most of a real frame's bits sit well clear of the threshold, noise's do not. A frame with
 *   more than 4 % of its bits under a quarter of the median margin is not tried ([Gate.Strict]). Decoders that call
 *   this only once a strong sync word matched (so the frame is real) use [Gate.Synced], which allows 15 %: their
 *   frames are long and a weak signal's discriminator leaves more faint bits. The risk of a forged frame comes from the
 *   number of tries, which the gate does not change.
 * - Only suspects are flipped: bits under [SUSPECT] of the median margin, the [WEAK] weakest at most, and the [PAIRS]
 *   weakest of those in pairs. At most 27 tries, so a corrupt frame passes a 16-bit check about once in 2400 failures,
 *   before the decoder's own sanity checks.
 * - The check must be at least 16 bits strong; decoders with an 8-bit checksum do not use this.
 */
object SoftMend {
    const val WEAK = 12
    const val PAIRS = 6
    const val SUSPECT = 0.4f
    private const val FAINT = 0.25f

    /** How much of a frame may be faint before it is taken for noise. */
    enum class Gate(val share: Float) { Strict(0.04f), Synced(0.15f) }

    /** Off only to measure what mending wins, in tests. */
    @Volatile internal var enabled = true
    /** For tests: calls, refused as noise, mended. */
    internal val stats = java.util.concurrent.atomic.AtomicIntegerArray(3)

    private val scratch = object : ThreadLocal<FloatArray>() { override fun initialValue() = FloatArray(4096) }
    private val picks = object : ThreadLocal<IntArray>() { override fun initialValue() = IntArray(WEAK) }

    /**
     * [bits] (0 or 1 per entry) and [margin] (distance from the threshold, in any unit: only compared) for bits
     * [from] until [to]; [check] reads [bits] and says whether the frame is good. Returns the number of bits flipped (1
     * or 2) when a mended frame passed, with [bits] left mended; 0 when it could not be mended, with [bits] as given.
     * Call it only after the frame as read failed its check.
     */
    fun mend(bits: ByteArray, margin: FloatArray, from: Int, to: Int, gate: Gate = Gate.Strict, check: () -> Boolean): Int {
        val n = to - from
        if (!enabled || n < 16) return 0
        stats.incrementAndGet(0)
        var sorted = scratch.get()!!
        if (sorted.size < n) { sorted = FloatArray(n); scratch.set(sorted) }
        System.arraycopy(margin, from, sorted, 0, n)
        java.util.Arrays.sort(sorted, 0, n)
        val median = sorted[n / 2]
        if (median <= 0f) return 0
        var faint = 0
        for (i in 0 until n) { if (sorted[i] < median * FAINT) faint++ else break }
        if (faint > (n * gate.share).toInt() + 2) { stats.incrementAndGet(1); return 0 }
        val suspect = median * SUSPECT
        // The WEAK weakest suspects, kept sorted.
        val pick = picks.get()!!
        var k = 0
        for (i in from until to) {
            val c = margin[i]
            if (c >= suspect) continue
            if (k == WEAK && c >= margin[pick[k - 1]]) continue
            var j = if (k < WEAK) k++ else WEAK - 1
            while (j > 0 && margin[pick[j - 1]] > c) { pick[j] = pick[j - 1]; j-- }
            pick[j] = i
        }
        fun flip(i: Int) { bits[i] = (bits[i].toInt() xor 1).toByte() }
        for (x in 0 until k) {
            flip(pick[x])
            if (check()) { stats.incrementAndGet(2); return 1 }
            flip(pick[x])
        }
        val p = minOf(k, PAIRS)
        for (x in 0 until p) for (y in x + 1 until p) {
            flip(pick[x]); flip(pick[y])
            if (check()) { stats.incrementAndGet(2); return 2 }
            flip(pick[x]); flip(pick[y])
        }
        return 0
    }

    /** Packs bits MSB first into [out] (bits.size / 8 bytes from [from]). */
    fun packMsb(bits: ByteArray, from: Int, out: ByteArray, bytes: Int) {
        for (b in 0 until bytes) {
            var v = 0
            for (i in 0 until 8) v = (v shl 1) or bits[from + 8 * b + i].toInt()
            out[b] = v.toByte()
        }
    }

    /** Packs bits LSB first into [out]. */
    fun packLsb(bits: ByteArray, from: Int, out: ByteArray, bytes: Int) {
        for (b in 0 until bytes) {
            var v = 0
            for (i in 0 until 8) v = v or (bits[from + 8 * b + i].toInt() shl i)
            out[b] = v.toByte()
        }
    }
}
