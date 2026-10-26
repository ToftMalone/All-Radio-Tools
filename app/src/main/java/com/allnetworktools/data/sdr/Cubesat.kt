package com.allnetworktools.data.sdr

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Amateur satellites' AX.25 telemetry, the framing most CubeSats and the ISS use: 1200 baud AFSK, or 9600 baud
 * G3RUH (FSK, scrambled with x^17 + x^12 + 1, NRZI). After OnAir's CubeSat mode (core/src/cubesat, GPL-3.0).
 */
object Cubesat {
    enum class Mode(val baud: Int) { Afsk1200(1200), G3ruh9600(9600) }

    /** A satellite to listen to: its catalogue number gives its passes and Doppler. */
    data class Sat(val name: String, val norad: Int, val hz: Long, val mode: Mode)

    /** Starting points; satellites come and go, so a frequency of one's own can be set too. */
    val sats = listOf(
        Sat("ISS (ARISS APRS)", 25544, 145_825_000L, Mode.Afsk1200),
        Sat("NO-44 (PCSat)", 26931, 145_825_000L, Mode.Afsk1200),
    )

    /** G3RUH scrambling: each bit sent is the data bit xor the bits sent 12 and 17 before. */
    fun scramble(bits: IntArray): IntArray {
        var s = 0
        return IntArray(bits.size) { i -> val o = bits[i] xor ((s shr 11) and 1) xor ((s shr 16) and 1); s = (s shl 1) or o; o }
    }

    /** NRZI: a 0 is a change of level, a 1 none. */
    fun nrzi(bits: IntArray): IntArray {
        var level = 0
        return IntArray(bits.size) { if (bits[it] == 0) level = 1 - level; level }
    }

    /** HDLC bits of AX.25 frames (FCS added, bit-stuffed, LSB first) between flags: what is scrambled for 9600. */
    fun hdlcBits(frames: List<ByteArray>, flags: Int = 16): IntArray {
        val out = ArrayList<Int>()
        fun flag() { for (b in 0 until 8) out += (0x7E shr b) and 1 }
        repeat(flags) { flag() }
        for (f in frames) {
            var crc = 0xFFFF
            for (x in f) { var v = x.toInt() and 0xFF; repeat(8) { crc = if ((crc xor v) and 1 != 0) (crc ushr 1) xor 0x8408 else crc ushr 1; v = v shr 1 } }
            crc = crc.inv() and 0xFFFF
            val all = f + byteArrayOf((crc and 0xFF).toByte(), (crc ushr 8).toByte())
            var ones = 0
            for (x in all) for (b in 0 until 8) {
                val bit = (x.toInt() shr b) and 1
                out += bit
                if (bit == 1) { if (++ones == 5) { out += 0; ones = 0 } } else ones = 0
            }
            repeat(4) { flag() }
        }
        return out.toIntArray()
    }
}

/**
 * 9600 baud G3RUH from FM-discriminated baseband: the level (carrier offset taken off), sliced at eight clock phases
 * one eighth of a bit apart, each with its own NRZI decoder, descrambler and HDLC reader; the FCS decides.
 */
class G3ruhDemodulator(private val rate: Double, private val onFrame: (ByteArray) -> Unit) {
    private val spb = rate / 9600.0
    private var mean = 0f
    private var n = 0L
    private var pr = 0f
    private var pi = 0f
    private val box = FloatArray(maxOf(1, (spb * 0.6).toInt()))
    private var boxPos = 0
    private var boxSum = 0f
    private val next = DoubleArray(PHASES) { spb + it * spb / PHASES }
    private val prev = IntArray(PHASES)
    private val shift = IntArray(PHASES)
    private val hdlc = Array(PHASES) { Hdlc { bits, count -> frame(bits, count) } }
    private val recent = HashMap<Int, Long>()

    private fun frame(bits: ByteArray, count: Int) {
        if (count % 8 != 0) return
        val out = ByteArray(count / 8)
        for (i in 0 until count) if (bits[i].toInt() == 1) out[i / 8] = (out[i / 8].toInt() or (1 shl (i % 8))).toByte()
        val h = out.contentHashCode()
        val last = recent[h]
        if (last != null && n - last < rate) return
        recent[h] = n
        if (recent.size > 64) recent.entries.removeAll { n - it.value > 10 * rate }
        onFrame(out)
    }

    fun feed(re: FloatArray, im: FloatArray, count: Int) {
        for (i in 0 until count) {
            val a = re[i] * pr + im[i] * pi
            val b = im[i] * pr - re[i] * pi
            pr = re[i]; pi = im[i]
            step(atan2(b, a))
        }
    }

    private fun step(f: Float) {
        mean += (f - mean) / 2000f
        val x = f - mean
        boxSum += x - box[boxPos]; box[boxPos] = x; boxPos = (boxPos + 1) % box.size
        val level = if (boxSum > 0) 1 else 0
        for (p in 0 until PHASES) {
            if (n >= next[p]) {
                val raw = if (level == prev[p]) 1 else 0 // NRZI
                prev[p] = level
                val s = shift[p]
                hdlc[p].bit(raw xor ((s shr 11) and 1) xor ((s shr 16) and 1))
                shift[p] = (s shl 1) or raw
                next[p] += spb
            }
        }
        n++
    }

    companion object {
        private const val PHASES = 8
    }
}

/**
 * HackRF bytes at 2 MS/s tuned fs/4 below the downlink: 500 kS/s, the Doppler shift taken off (set it as the pass goes),
 * 50 kS/s, then the AFSK or G3RUH demodulator; AX.25 frames out.
 */
class CubesatReceiver(mode: Cubesat.Mode, private val onFrame: (Ax25Frame) -> Unit) {
    private val d1 = Decimator(4, 0.07, taps = 48)
    private val d2 = FloatDecimator(10, (if (mode == Cubesat.Mode.G3ruh9600) 15_000.0 else 12_000.0) / 500_000.0, 64)
    private val aRe = FloatArray(32768)
    private val aIm = FloatArray(32768)
    private val bRe = FloatArray(4096)
    private val bIm = FloatArray(4096)
    private val afsk = if (mode == Cubesat.Mode.Afsk1200) AfskDemodulator { b -> Ax25.parse(b)?.let(onFrame) } else null
    private val g3ruh = if (mode == Cubesat.Mode.G3ruh9600) G3ruhDemodulator(50_000.0) { b -> Ax25.parse(b)?.let(onFrame) } else null
    /** The Doppler shift (Hz) to take off: the satellite is heard this much above its nominal frequency. */
    @Volatile var dopplerHz = 0.0
    private var pr = 1.0
    private var pim = 0.0
    var frames = 0
        private set

    fun feed(buf: ByteArray, len: Int) {
        val k = d1.process(buf, len, aRe, aIm, 0)
        feedFloat(aRe, aIm, k)
    }

    /** Samples at 500 kS/s centred on the nominal frequency. */
    fun feedFloat(re: FloatArray, im: FloatArray, k: Int) {
        val w = -2 * PI * dopplerHz / 500_000.0
        val sr = cos(w); val sj = sin(w)
        var p = pr; var q = pim
        for (i in 0 until k) {
            val a = re[i]; val b = im[i]
            re[i] = (a * p - b * q).toFloat(); im[i] = (a * q + b * p).toFloat()
            val nr = p * sr - q * sj; q = p * sj + q * sr; p = nr
        }
        val g = 1 / hypot(p, q); pr = p * g; pim = q * g
        var off = 0
        while (off < k) {
            val m = minOf(32768, k - off)
            val n = d2.process(if (off == 0) re else re.copyOfRange(off, off + m), if (off == 0) im else im.copyOfRange(off, off + m), m, bRe, bIm)
            afsk?.feed(bRe, bIm, n)
            g3ruh?.feed(bRe, bIm, n)
            off += m
        }
    }

    companion object {
        const val SAMPLE_RATE = 2_000_000
    }
}
