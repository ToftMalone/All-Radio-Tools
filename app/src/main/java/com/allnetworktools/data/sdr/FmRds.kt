package com.allnetworktools.data.sdr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sin
import com.allnetworktools.R
import com.allnetworktools.util.S

/** What a station's RDS data told so far. */
data class RdsInfo(
    val pi: Int? = null,
    val ps: String? = null,
    val radioText: String? = null,
    val pty: Int? = null,
    val tp: Boolean = false,
    val ta: Boolean = false,
    val groups: Int = 0,
)

/** Radio Data System (EN 50067): block coding, character set and the groups that carry the station name and text. */
object Rds {
    const val BIT_RATE = 1187.5

    /** Offset words A, B, C, C', D. */
    val OFFSETS = intArrayOf(0x0FC, 0x198, 0x168, 0x350, 0x1B4)
    private const val POLY = 0x5B9

    /** CRC-10 of a 16-bit information word, as the standard's generator x^10+x^8+x^7+x^5+x^4+x^3+1. */
    fun crc10(info: Int): Int {
        var r = info shl 10
        for (b in 25 downTo 10) if (r and (1 shl b) != 0) r = r xor (POLY shl (b - 10))
        return r and 0x3FF
    }

    /** The 26 bits of a block, as an int (information first). */
    fun block(info: Int, offsetIndex: Int): Int = (info shl 10) or (crc10(info) xor OFFSETS[offsetIndex])

    private val ACCENTS = "áàéèíìóòúùÑÇŞß¡Ĳâäêëîïôöûüñçşğıĳ"

    /** One RDS character; anything outside the basic and first accented rows becomes a space. */
    fun char(code: Int): Char = when (code) {
        in 0x20..0x7D -> code.toChar()
        in 0x80..0x9F -> ACCENTS.getOrElse(code - 0x80) { ' ' }
        else -> ' '
    }

    val PTY = listOf(
        S(R.string.none), S(R.string.news), S(R.string.current_affairs), S(R.string.information), "Sport", S(R.string.education), "Fiction", "Culture", S(R.string.science), S(R.string.varied), "Pop", "Rock",
        S(R.string.soft_music), S(R.string.light_classical), S(R.string.classic), S(R.string.other_music), S(R.string.weather), S(R.string.finance), S(R.string.children_s), S(R.string.social_affairs), "Religion",
        S(R.string.phone_in), S(R.string.travel), S(R.string.leisure), "Jazz", "Country", S(R.string.national_music), "Oldies", "Folk", S(R.string.documentary),
        S(R.string.alarm_test), S(R.string.alarm),
    )
}

/** Collects the groups of one station into a [RdsInfo]. */
class RdsParser {
    private var pi: Int? = null
    private var pty: Int? = null
    private var tp = false
    private var ta = false
    private val ps = CharArray(8) { ' ' }
    private val psSeen = BooleanArray(4)
    private val rt = CharArray(64) { ' ' }
    private var rtAb = -1
    private var rtEnd = 64
    private var rtSeen = 0L
    private var groups = 0

    /** [w] are the four 16-bit information words, [ok] tells which blocks passed their check. */
    fun onGroup(w: IntArray, ok: BooleanArray) {
        if (ok[0]) pi = w[0]
        if (!ok[1]) return
        groups++
        val type = (w[1] shr 12) and 0xF
        val b = (w[1] shr 11) and 1
        tp = (w[1] shr 10) and 1 == 1
        pty = (w[1] shr 5) and 0x1F
        when {
            type == 0 && ok[3] -> {
                ta = (w[1] shr 4) and 1 == 1
                val a = w[1] and 3
                ps[2 * a] = Rds.char(w[3] shr 8)
                ps[2 * a + 1] = Rds.char(w[3] and 0xFF)
                psSeen[a] = true
            }
            type == 2 && b == 0 && ok[2] && ok[3] -> rtChars(w[1], 4, intArrayOf(w[2] shr 8, w[2] and 0xFF, w[3] shr 8, w[3] and 0xFF))
            type == 2 && b == 1 && ok[3] -> rtChars(w[1], 2, intArrayOf(w[3] shr 8, w[3] and 0xFF))
        }
    }

    private fun rtChars(b: Int, per: Int, chars: IntArray) {
        val ab = (b shr 4) and 1
        if (rtAb != -1 && ab != rtAb) { rt.fill(' '); rtEnd = 64; rtSeen = 0 } // a new text starts
        rtAb = ab
        val at = (b and 0xF) * per
        chars.forEachIndexed { i, c ->
            val p = at + i
            if (p >= 64) return@forEachIndexed
            if (c == 0x0D) { rtEnd = p; return@forEachIndexed }
            rt[p] = Rds.char(c)
            rtSeen = rtSeen or (1L shl p)
        }
    }

    fun info(): RdsInfo {
        val name = if (psSeen.all { it }) String(ps).trimEnd() else null
        val text = String(rt, 0, rtEnd.coerceAtMost(64)).trimEnd().takeIf { it.isNotEmpty() && rtSeen != 0L }
        return RdsInfo(pi, name, text, pty, tp, ta, groups)
    }
}

/**
 * RDS demodulator for the composite signal at 237.5 kS/s (200 samples per bit): 57 kHz BPSK carrier recovered by a
 * Costas loop, Manchester matched filter with a bit clock chosen by energy, differential decoding, then block
 * synchronisation on the offset words.
 */
class RdsDecoder(private val onGroup: (IntArray, BooleanArray) -> Unit) {
    private val w0 = 2 * PI * 57_000.0 / SAMPLE_RATE
    private var phase = 0.0
    private var freq = 0.0 // correction, rad per decimated sample
    private val dec = FloatDecimator(DECIM, 3000.0 / SAMPLE_RATE, 301)
    private val mr = FloatArray(DECIM)
    private val mi = FloatArray(DECIM)
    private val oRe = FloatArray(1)
    private val oIm = FloatArray(1)
    private var k = 0

    // Bit clock: eight samples per bit after the decimator.
    private val hist = FloatArray(8)
    private var n = 0
    private val energy = FloatArray(8)
    private var prevBit = 0

    // Block sync.
    private var reg = 0
    private var bitPos = 0L
    private var synced = false
    private var expect = 0 // 0 A, 1 B, 2 C or C', 3 D
    private var nextAt = 0L
    private var bad = 0
    private var lastMatchAt = -1L
    private var lastMatch = -1
    private val words = IntArray(4)
    private val ok = BooleanArray(4)

    fun reset() {
        synced = false; bad = 0; lastMatch = -1; lastMatchAt = -1
    }

    fun feed(x: FloatArray, len: Int) {
        for (i in 0 until len) {
            mr[k] = (x[i] * cos(phase)).toFloat()
            mi[k] = (-x[i] * sin(phase)).toFloat()
            phase += w0 + freq / DECIM
            if (phase > 2 * PI) phase -= 2 * PI
            if (++k == DECIM) {
                k = 0
                dec.process(mr, mi, DECIM, oRe, oIm)
                val re = oRe[0]
                val im = oIm[0]
                // BPSK phase error: half the angle of the squared signal.
                val err = 0.5 * atan2(2.0 * re * im, (re * re - im * im).toDouble())
                phase += 0.02 * err
                freq += 0.0001 * err
                bitSample(re)
            }
        }
    }

    private fun bitSample(v: Float) {
        hist[n and 7] = v
        // Manchester matched filter over the last eight samples: first half minus second half.
        var m = 0f
        for (j in 0 until 4) m += hist[(n - 7 + j) and 7] - hist[(n - 3 + j) and 7]
        val p = n and 7
        energy[p] += (abs(m) - energy[p]) / 64f
        var best = 0
        for (j in 1 until 8) if (energy[j] > energy[best]) best = j
        n++
        if (p == best) {
            val b = if (m > 0) 1 else 0
            bit(b xor prevBit)
            prevBit = b
        }
    }

    private fun bit(d: Int) {
        reg = ((reg shl 1) or d) and 0x3FFFFFF
        bitPos++
        if (bitPos < 26) return
        if (!synced) {
            val t = match(reg)
            if (t < 0) return
            val kind = if (t == 3) 2 else if (t == 4) 3 else t // C' counts as C
            if (lastMatch >= 0 && bitPos - lastMatchAt == 26L && kind == (lastMatch + 1) % 4) {
                synced = true; bad = 0
                expect = (kind + 1) % 4
                nextAt = bitPos + 26
                ok.fill(false) // the group in progress is incomplete
            }
            lastMatch = kind; lastMatchAt = bitPos
            return
        }
        if (bitPos != nextAt) return
        nextAt += 26
        val t = match(reg)
        val good = t >= 0 && (if (expect == 2) t == 2 || t == 3 else if (expect == 3) t == 4 else t == expect)
        if (expect == 0) ok.fill(false)
        ok[expect] = good
        words[expect] = reg shr 10
        if (good) bad = 0 else if (++bad > 40) { synced = false; lastMatch = -1; return }
        if (expect == 3) onGroup(words.copyOf(), ok.copyOf())
        expect = (expect + 1) % 4
    }

    /** Index into [Rds.OFFSETS] of the offset word this 26-bit block carries, or −1. */
    private fun match(w: Int): Int {
        val chk = (w and 0x3FF) xor Rds.crc10(w shr 10)
        return Rds.OFFSETS.indexOf(chk)
    }

    companion object {
        const val SAMPLE_RATE = 237_500.0
        private const val DECIM = 25
    }
}

/** Plain real FIR low-pass and decimator. */
class RealDecimator(private val decim: Int, cutoff: Double, private val n: Int) {
    private val h = FloatArray(n)
    private val buf = FloatArray(n * 2)
    private var pos = 0
    private var phase = 0

    init {
        val raw = DoubleArray(n) { i ->
            val m = i - (n - 1) / 2.0
            val sinc = if (m == 0.0) 2 * cutoff else sin(2 * PI * cutoff * m) / (PI * m)
            sinc * (0.42 - 0.5 * cos(2 * PI * i / (n - 1)) + 0.08 * cos(4 * PI * i / (n - 1)))
        }
        val sum = raw.sum()
        for (i in 0 until n) h[i] = (raw[i] / sum).toFloat()
    }

    fun process(x: FloatArray, len: Int, out: FloatArray): Int {
        var o = 0
        for (i in 0 until len) {
            buf[pos] = x[i]; buf[pos + n] = x[i]
            pos++
            if (pos == n) pos = 0
            if (++phase == decim) {
                phase = 0
                var a = 0f
                for (j in 0 until n) a += h[j] * buf[pos + j]
                out[o++] = a
            }
        }
        return o
    }
}

/**
 * Broadcast FM from complex baseband at 237.5 kS/s: discriminator (±75 kHz deviation = ±1), mono audio at 47.5 kS/s with
 * 50 µs de-emphasis, and the RDS decoder on the composite signal.
 */
class FmReceiver(private val onAudio: (ShortArray, Int) -> Unit, onRds: (RdsInfo) -> Unit) {
    private val k = (SAMPLE_RATE / (2 * PI * 75_000.0)).toFloat()
    private var pr = 0f
    private var pi = 0f
    private val composite = FloatArray(32768)
    private val lowpass = RealDecimator(5, 16_000.0 / SAMPLE_RATE, 161)
    private val audioF = FloatArray(8192)
    private val audioS = ShortArray(8192)
    private val deemph = (1 - exp(-1.0 / (AUDIO_RATE * 50e-6))).toFloat()
    private var y = 0f
    private var dcIn = 0f
    private var dcOut = 0f
    private var parser = RdsParser()
    private val rds = RdsDecoder { w, ok -> parser.onGroup(w, ok); onRds(parser.info()) }
    private var power = 0.0
    private var powerN = 0

    /** Tuning changed: forget the old station. */
    fun reset() {
        parser = RdsParser(); rds.reset()
    }

    /** Mean signal level since the last call, in dBFS. */
    fun levelDb(): Double {
        val p = if (powerN > 0) power / powerN else 0.0
        power = 0.0; powerN = 0
        return 10 * log10(p + 1e-12)
    }

    fun feed(re: FloatArray, im: FloatArray, n: Int) {
        var done = 0
        while (done < n) {
            val len = minOf(composite.size, n - done)
            for (i in 0 until len) {
                val r = re[done + i]
                val q = im[done + i]
                power += r * r + q * q
                val a = r * pr + q * pi
                val b = q * pr - r * pi
                pr = r; pi = q
                composite[i] = atan2(b, a) * k
            }
            powerN += len
            rds.feed(composite, len)
            val m = lowpass.process(composite, len, audioF)
            for (i in 0 until m) {
                // Remove the DC that a tuning error leaves, then de-emphasis.
                dcOut = audioF[i] - dcIn + 0.995f * dcOut
                dcIn = audioF[i]
                y += deemph * (dcOut - y)
                audioS[i] = (y * 28_000f).toInt().coerceIn(-32768, 32767).toShort()
            }
            onAudio(audioS, m)
            done += len
        }
    }

    companion object {
        const val SAMPLE_RATE = 237_500.0
        const val AUDIO_RATE = 47_500
    }
}
