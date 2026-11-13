package com.allnetworktools.data.sdr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Complex baseband to transmit, [rate] samples per second. [next] fills up to the size of the arrays and returns how
 * many it wrote, or -1 once the transmission is over.
 */
interface BasebandSource {
    val rate: Int
    fun next(re: FloatArray, im: FloatArray): Int
}

/**
 * Turns a [BasebandSource] into the radio's transmit buffers: interpolated to [txRate] (two polyphase stages), moved
 * [shiftHz] up so the signal stays clear of the radio's own carrier leak at the tuned frequency, and written as signed
 * 8-bit I/Q. Once the source ends, a few buffers of silence let the last samples leave the USB queue before [finished].
 */
class TxStream(private val source: BasebandSource, private val txRate: Int, private val shiftHz: Double, private val level: Float = 0.9f) {
    private val factor = txRate / source.rate
    private val stages: List<Interpolator> = run {
        require(factor >= 1 && factor * source.rate == txRate) { "rate ${source.rate} does not divide $txRate" }
        when {
            factor == 1 -> emptyList()
            factor % 5 == 0 && factor > 5 -> listOf(Interpolator(5, 0.45 / 5, 80), Interpolator(factor / 5, 0.5 / factor, 8 * (factor / 5)))
            else -> listOf(Interpolator(factor, 0.45 / factor, 16 * factor))
        }
    }
    private val inRe = FloatArray(BLOCK)
    private val inIm = FloatArray(BLOCK)
    private var fifoRe = FloatArray(BLOCK * factor)
    private var fifoIm = FloatArray(BLOCK * factor)
    private var fifoLen = 0
    private var fifoPos = 0
    private var ended = false
    private var silentBuffers = 0
    private var phRe = 1.0
    private var phIm = 0.0
    private val stRe = cos(2 * PI * shiftHz / txRate)
    private val stIm = sin(2 * PI * shiftHz / txRate)
    private var norm = 0

    /** Set once the source ended and its last samples are out. */
    @Volatile var finished = false
        private set

    private fun refill(): Boolean {
        val n = source.next(inRe, inIm)
        if (n < 0) { ended = true; return false }
        var re = inRe.copyOf(n)
        var im = inIm.copyOf(n)
        for (s in stages) { val o = s.process(re, im); re = o.first; im = o.second }
        fifoRe = re; fifoIm = im; fifoLen = re.size; fifoPos = 0
        return true
    }

    /** Fills all of [buf] (interleaved I/Q bytes). */
    fun fill(buf: ByteArray) {
        val pairs = buf.size / 2
        var k = 0
        var wroteSignal = false
        while (k < pairs) {
            if (fifoPos >= fifoLen && (ended || !refill())) {
                // Over: silence for the rest.
                while (k < pairs) { buf[2 * k] = 0; buf[2 * k + 1] = 0; k++ }
                break
            }
            if (fifoPos >= fifoLen) continue
            val r = fifoRe[fifoPos]
            val i = fifoIm[fifoPos]
            fifoPos++
            val sr = (r * phRe - i * phIm) * level
            val si = (r * phIm + i * phRe) * level
            val t = phRe * stRe - phIm * stIm
            phIm = phRe * stIm + phIm * stRe
            phRe = t
            if (++norm == 4096) { norm = 0; val g = 1.0 / kotlin.math.hypot(phRe, phIm); phRe *= g; phIm *= g }
            buf[2 * k] = q(sr)
            buf[2 * k + 1] = q(si)
            wroteSignal = true
            k++
        }
        if (ended && !wroteSignal && fifoPos >= fifoLen) { if (++silentBuffers >= TAIL_BUFFERS) finished = true }
    }

    private fun q(v: Double): Byte = (v * 127).toInt().coerceIn(-127, 127).toByte()

    companion object {
        const val BLOCK = 512
        private const val TAIL_BUFFERS = 6
    }
}

/** Polyphase interpolation by [l] with a windowed-sinc low-pass of [taps] taps and cutoff [fc] (of the output rate). */
class Interpolator(private val l: Int, fc: Double, taps: Int) {
    private val n = (taps + l - 1) / l * l
    private val h = DoubleArray(n) { i ->
        val m = i - (n - 1) / 2.0
        val sinc = if (m == 0.0) 2 * fc else sin(2 * PI * fc * m) / (PI * m)
        sinc * (0.42 - 0.5 * cos(2 * PI * i / (n - 1)) + 0.08 * cos(4 * PI * i / (n - 1)))
    }.let { raw -> val s = raw.sum(); FloatArray(n) { (raw[it] / s * l).toFloat() } }
    private val per = n / l
    private val histRe = FloatArray(per * 2)
    private val histIm = FloatArray(per * 2)
    private var pos = 0

    fun process(re: FloatArray, im: FloatArray): Pair<FloatArray, FloatArray> {
        val oRe = FloatArray(re.size * l)
        val oIm = FloatArray(re.size * l)
        for (k in re.indices) {
            histRe[pos] = re[k]; histRe[pos + per] = re[k]
            histIm[pos] = im[k]; histIm[pos + per] = im[k]
            pos++
            if (pos == per) pos = 0
            for (p in 0 until l) {
                var ar = 0f
                var ai = 0f
                // Newest sample first: history[pos + per - 1] is the latest.
                for (j in 0 until per) {
                    val c = h[p + j * l]
                    val idx = pos + per - 1 - j
                    ar += c * histRe[idx]; ai += c * histIm[idx]
                }
                oRe[k * l + p] = ar; oIm[k * l + p] = ai
            }
        }
        return oRe to oIm
    }
}

/** Audio to transmit at [rate]: [next] fills up to the array size and returns how many, or -1 at the end. */
interface AudioSource {
    val rate: Int
    fun next(out: FloatArray): Int
}

/** A finished piece of audio (an APRS packet, an SSTV picture). */
class BufferAudio(private val samples: FloatArray, override val rate: Int) : AudioSource {
    private var pos = 0
    override fun next(out: FloatArray): Int {
        if (pos >= samples.size) return -1
        val n = minOf(out.size, samples.size - pos)
        System.arraycopy(samples, pos, out, 0, n)
        pos += n
        return n
    }
}

/** How audio becomes a radio signal. */
enum class TxMode { Fm, Am, Usb, Lsb }

/**
 * Modulates [audio] (levels within ±1) as [mode]: FM with [deviationHz], AM at 90 % at most, or one sideband (the
 * audio's analytic signal from a Hilbert filter, the other sideband cancelled). Voice is first kept to 300–3000 Hz.
 */
class AudioModulator(private val audio: AudioSource, private val mode: TxMode, private val deviationHz: Double = 2_500.0, private val voice: Boolean = true) : BasebandSource {
    override val rate = audio.rate
    private val buf = FloatArray(TxStream.BLOCK)
    private var phase = 0.0
    private val bp = if (voice) VoiceFilter(rate) else null
    private val hilbert = FloatArray(HILBERT_TAPS) { i ->
        val m = i - (HILBERT_TAPS - 1) / 2
        if (m % 2 == 0) 0f else (2.0 / (PI * m) * (0.54 - 0.46 * cos(2 * PI * i / (HILBERT_TAPS - 1)))).toFloat()
    }
    private val hist = FloatArray(HILBERT_TAPS * 2)
    private var hp = 0

    override fun next(re: FloatArray, im: FloatArray): Int {
        val n = audio.next(buf)
        if (n < 0) return -1
        for (k in 0 until n) {
            var x = buf[k]
            if (bp != null) x = bp.filter(x)
            x = x.coerceIn(-1f, 1f)
            when (mode) {
                TxMode.Fm -> {
                    phase += 2 * PI * deviationHz * x / rate
                    if (phase > PI) phase -= 2 * PI else if (phase < -PI) phase += 2 * PI
                    re[k] = cos(phase).toFloat(); im[k] = sin(phase).toFloat()
                }
                TxMode.Am -> { re[k] = 0.5f * (1f + 0.9f * x); im[k] = 0f }
                TxMode.Usb, TxMode.Lsb -> {
                    hist[hp] = x; hist[hp + HILBERT_TAPS] = x
                    hp++
                    if (hp == HILBERT_TAPS) hp = 0
                    var q = 0f
                    for (j in 0 until HILBERT_TAPS) q += hilbert[j] * hist[hp + HILBERT_TAPS - 1 - j]
                    val delayed = hist[hp + HILBERT_TAPS - 1 - (HILBERT_TAPS - 1) / 2]
                    re[k] = delayed
                    im[k] = if (mode == TxMode.Usb) q else -q
                }
            }
        }
        return n
    }

    private companion object { const val HILBERT_TAPS = 127 }
}

/** 300–3000 Hz band-pass for voice (a high-pass and a low-pass, both second order). */
class VoiceFilter(rate: Int) {
    private val hp = Biquad.highPass(rate.toDouble(), 300.0)
    private val lp1 = Biquad.lowPass(rate.toDouble(), 3_000.0)
    private val lp2 = Biquad.lowPass(rate.toDouble(), 3_000.0)
    fun filter(x: Float): Float = lp2.filter(lp1.filter(hp.filter(x)))
}

class Biquad(private val b0: Double, private val b1: Double, private val b2: Double, private val a1: Double, private val a2: Double) {
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0
    fun filter(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x.toDouble(); y2 = y1; y1 = y
        return y.toFloat()
    }

    companion object {
        private fun make(fs: Double, f: Double, high: Boolean): Biquad {
            val w = 2 * PI * f / fs
            val alpha = sin(w) / (2 * 0.7071)
            val c = cos(w)
            val a0 = 1 + alpha
            return if (high) Biquad((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
            else Biquad((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
        }
        fun lowPass(fs: Double, f: Double) = make(fs, f, false)
        fun highPass(fs: Double, f: Double) = make(fs, f, true)
    }
}

/** Loudness levelling for the microphone: brings speech up to about [target], never above full scale. */
class SpeechLeveller(private val target: Float = 0.7f) {
    private var gain = 4f
    fun level(x: Float): Float {
        val y = x * gain
        if (abs(y) > target) gain *= 0.995f else gain = minOf(gain * 1.00005f, 30f)
        return y.coerceIn(-1f, 1f)
    }
}
