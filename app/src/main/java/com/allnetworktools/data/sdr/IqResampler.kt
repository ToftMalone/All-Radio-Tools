package com.allnetworktools.data.sdr

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Turns interleaved I/Q at one rate into interleaved I/Q at another, by a rational factor L/M (windowed-sinc polyphase
 * filter), for radios whose sample rates are not the ones the decoders ask for (an Airspy makes 2.5 or 3 MS/s, the tools
 * want 2). Input and output are signed 16-bit I/Q pairs; the state carries from one call to the next.
 */
class IqResampler(inRate: Int, outRate: Int) {
    val up: Int
    val down: Int
    private val tapsPerPhase = TAPS
    private val h: FloatArray
    private val hist = FloatArray(2 * TAPS)
    private var histPos = 0
    /** Position of the next output in the stretched input, as a phase counter in 0 until [up] steps. */
    private var phase = 0

    init {
        val g = gcd(inRate, outRate)
        up = outRate / g
        down = inRate / g
        require(up in 1..MAX_FACTOR && down in 1..MAX_FACTOR) { "ratio $up/$down" }
        val n = up * tapsPerPhase
        h = FloatArray(n)
        // Low-pass at the stretched rate: cut-off a little under the narrower of the two Nyquist frequencies.
        val fc = 0.45 / max(up, down)
        val mid = (n - 1) / 2.0
        for (k in 0 until n) {
            val x = k - mid
            val sinc = if (x == 0.0) 2 * fc else sin(2 * PI * fc * x) / (PI * x)
            val w = 0.42 - 0.5 * cos(2 * PI * k / (n - 1)) + 0.08 * cos(4 * PI * k / (n - 1))
            h[k] = (sinc * w * up).toFloat()
        }
    }

    val passThrough get() = up == 1 && down == 1

    /**
     * Resamples [count] int16 values (count/2 I/Q pairs) of [src]; the result goes to [dst] and its length in values is
     * returned. [dst] must hold at least `count * up / down + 4` values.
     */
    fun process(src: ShortArray, count: Int, dst: ShortArray): Int {
        if (passThrough) { System.arraycopy(src, 0, dst, 0, count); return count }
        var out = 0
        var i = 0
        while (i < count) {
            // One new input pair enters the history; as many outputs as the phase allows leave it.
            hist[histPos] = src[i].toFloat(); hist[histPos + 1] = src[i + 1].toFloat()
            // Duplicated at the end so a window never wraps.
            histPos += 2
            if (histPos >= hist.size) histPos = 0
            while (phase < up) {
                var si = 0f
                var sq = 0f
                var k = phase
                var p = (histPos - 2 + hist.size) % hist.size
                var t = 0
                while (t < tapsPerPhase) {
                    val c = h[k]
                    si += c * hist[p]; sq += c * hist[p + 1]
                    k += up
                    p -= 2
                    if (p < 0) p += hist.size
                    t++
                }
                dst[out++] = si.coerceIn(-32768f, 32767f).toInt().toShort()
                dst[out++] = sq.coerceIn(-32768f, 32767f).toInt().toShort()
                phase += down
            }
            phase -= up
            i += 2
        }
        return out
    }

    companion object {
        private const val TAPS = 24
        const val MAX_FACTOR = 200

        fun possible(inRate: Int, outRate: Int): Boolean {
            val g = gcd(inRate, outRate)
            return outRate / g <= MAX_FACTOR && inRate / g <= MAX_FACTOR
        }

        private fun gcd(a: Int, b: Int): Int { var x = a; var y = b; while (y != 0) { val t = x % y; x = y; y = t }; return x }
    }
}
