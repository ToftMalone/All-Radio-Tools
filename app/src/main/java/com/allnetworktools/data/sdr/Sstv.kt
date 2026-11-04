package com.allnetworktools.data.sdr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * SSTV, ported from OnAir (core/src/hfdig/hfdig_sstv*, GPL-3.0). Mode timings are the published ones (J. L. Barber
 * N7CXI, "Proposal for SSTV Mode Specifications", 2000, cross-checked against MMSSTV and slowrx). Tones: sync 1200 Hz,
 * black 1500 Hz, white 2300 Hz.
 */
object Sstv {
    enum class Kind { Seq, Robot36, Robot72, PD }
    const val R = 0; const val G = 1; const val B = 2; const val Y = 3; const val RY = 4; const val BY = 5; const val Y2 = 6

    class Scan(val startMs: Double, val lenMs: Double, val px: Int, val comp: Int)
    /** A stretch of steady tone other than the sync; hz < 0 is the Robot 36 separator (1500 Hz on even lines, 2300 on odd). */
    class Fixed(val startMs: Double, val lenMs: Double, val hz: Double)

    class Mode(
        val vis: Int, val name: String, val width: Int, val height: Int, val lineMs: Double, val syncMs: Double,
        val kind: Kind, val syncOffMs: Double, val totalLines: Int, val scans: List<Scan>, val fixed: List<Fixed>,
    )

    val modes: List<Mode> = buildList {
        add(Mode(8, "Robot 36", 320, 240, 150.0, 9.0, Kind.Robot36, 0.0, 240, listOf(Scan(12.0, 88.0, 320, Y), Scan(106.0, 44.0, 160, RY)), listOf(Fixed(100.0, 4.5, -1.0), Fixed(104.5, 1.5, 1900.0))))
        add(Mode(12, "Robot 72", 320, 240, 300.0, 9.0, Kind.Robot72, 0.0, 240, listOf(Scan(12.0, 138.0, 320, Y), Scan(156.0, 69.0, 160, RY), Scan(231.0, 69.0, 160, BY)),
            listOf(Fixed(150.0, 4.5, 1500.0), Fixed(154.5, 1.5, 1900.0), Fixed(225.0, 4.5, 2300.0), Fixed(229.5, 1.5, 1900.0))))
        fun martin(vis: Int, name: String, scan: Double) {
            val a = 4.862 + 0.572; val step = scan + 0.572
            add(Mode(vis, name, 320, 256, a + 3 * step, 4.862, Kind.Seq, 0.0, 256, listOf(Scan(a, scan, 320, G), Scan(a + step, scan, 320, B), Scan(a + 2 * step, scan, 320, R)), emptyList()))
        }
        martin(44, "Martin M1", 146.432)
        martin(40, "Martin M2", 73.216)
        fun scottie(vis: Int, name: String, scan: Double) {
            add(Mode(vis, name, 320, 256, 3 * scan + 13.5, 9.0, Kind.Seq, 2 * scan + 3.0, 256, listOf(Scan(1.5, scan, 320, G), Scan(scan + 3.0, scan, 320, B), Scan(2 * scan + 13.5, scan, 320, R)), emptyList()))
        }
        scottie(60, "Scottie S1", 138.240)
        scottie(56, "Scottie S2", 88.064)
        scottie(76, "Scottie DX", 345.600)
        fun pd(vis: Int, name: String, w: Int, h: Int, scan: Double) {
            val a = 20.0 + 2.08
            add(Mode(vis, name, w, h, a + 4 * scan, 20.0, Kind.PD, 0.0, h / 2, listOf(Scan(a, scan, w, Y), Scan(a + scan, scan, w, RY), Scan(a + 2 * scan, scan, w, BY), Scan(a + 3 * scan, scan, w, Y2)), emptyList()))
        }
        pd(93, "PD50", 320, 256, 91.520)
        pd(99, "PD90", 320, 256, 170.240)
        pd(95, "PD120", 640, 496, 121.600)
        pd(97, "PD180", 640, 496, 183.040)
        add(Mode(55, "Wraase SC2-180", 320, 256, 5.5225 + 0.5 + 3 * 235.0, 5.5225, Kind.Seq, 0.0, 256, listOf(Scan(6.0225, 235.0, 320, R), Scan(241.0225, 235.0, 320, G), Scan(476.0225, 235.0, 320, B)), emptyList()))
    }

    fun clamp8(v: Double): Int = if (v < 0) 0 else if (v > 255) 255 else (v + 0.5).toInt()

    /** RGB from luma and colour differences (128 = none), as slowrx and the ITU-based modes do; packed as 0xRRGGBB. */
    fun yuv(y: Double, ry: Double, by: Double): Int {
        val r = clamp8(y + 1.4 * (ry - 128)); val g = clamp8(y - 0.71 * (ry - 128) - 0.33 * (by - 128)); val b = clamp8(y + 1.78 * (by - 128))
        return (r shl 16) or (g shl 8) or b
    }

    /** A Kaiser-windowed sinc low-pass, unity gain at DC; [fc] is relative to the sample rate. */
    fun lowpass(n: Int, fc: Double, beta: Double): FloatArray {
        fun i0(x: Double): Double { var s = 1.0; var t = 1.0; for (k in 1..30) { t *= (x / (2 * k)) * (x / (2 * k)); s += t }; return s }
        val m = (n - 1) / 2.0
        val h = DoubleArray(n) { i ->
            val x = i - m
            val sinc = if (x == 0.0) 2 * fc else sin(2 * PI * fc * x) / (PI * x)
            val r = x / m
            sinc * i0(beta * sqrt(maxOf(0.0, 1 - r * r))) / i0(beta)
        }
        val s = h.sum()
        return FloatArray(n) { (h[it] / s).toFloat() }
    }
}

/** A picture being received or finished: 0xRRGGBB pixels row by row. */
class SstvImage(val id: Long, val mode: String, val width: Int, val height: Int, val lines: Int, val complete: Boolean, val vis: Boolean, val slantPpm: Double, val startMs: Long, val rgb: IntArray)

/**
 * The decoder, at 8 kHz audio: the audio is mixed down by 1900 Hz, low-passed and turned into an unwrapped phase kept in a
 * ring, so the mean tone over any stretch is one subtraction. Idle, a state machine on the 4 ms mean tone looks for the
 * VIS header (leader 1900 Hz, break 1200 Hz, leader, start bit, seven data bits at 1100 / 1300 Hz, parity, stop bit) and
 * logs the 1200 Hz pulses: five at the line period of a mode start it without a header. Receiving, each line's sync is
 * searched where the previous ones predict it; a straight line through the line starts gives the sender's clock (slant).
 */
class SstvDecoder(private val onUpdate: (SstvImage) -> Unit) {
    private val fs = 8000.0
    private val centre = 1900.0
    private val ntaps = 63
    private val ring = 1 shl 17
    private val box = 32
    private val taps = Sstv.lowpass(ntaps, 950.0 / fs, 6.0)
    private val oscR = FloatArray(80) { cos(-2 * PI * centre * it / fs).toFloat() }
    private val oscI = FloatArray(80) { sin(-2 * PI * centre * it / fs).toFloat() }
    private val hr = FloatArray(2 * ntaps)
    private val hi = FloatArray(2 * ntaps)
    private var hpos = 0
    private var zr = 0f
    private var zi = 0f
    private var phase = 0.0
    private val ph = DoubleArray(ring)
    private var n = 0L

    private enum class Vs { Idle, Lead1, Break, Lead2, Bits }
    private var vs = Vs.Idle
    private var leadCount = 0
    private var trans = 0
    private var leadSum = 0.0
    private var leadMean = centre
    private var edge1 = 0.0
    private var edge2 = 0.0
    private var edge3 = 0.0
    private var inPulse = false
    private var runStart = 0L
    private val events = ArrayList<Double>()

    private var active = false
    private lateinit var sp: Sstv.Mode
    private var vis = false
    private var c0 = 0.0
    private var off = 0.0
    private var periodS = 0.0
    private var detect = 0
    private var extract = 0
    private var nFit = 0
    private var sk = 0.0; private var sc = 0.0; private var skk = 0.0; private var skc = 0.0
    private var fa = 0.0; private var fb = 0.0
    private var missed = 0
    private var slant = 0.0
    private var rgb = IntArray(0)
    private var rows = 0
    private var yPend = DoubleArray(0)
    private var ryPend = DoubleArray(0)
    private var pendLine = -1
    private var id = 0L
    private var t0 = 0L
    private var dirty = false

    /** The mode of the picture being received, null when idle. */
    val receiving: String? get() = if (active) sp.name else null

    fun feed(x: FloatArray, count: Int) { for (i in 0 until count) sample(x[i]) }

    private fun phaseAt(t: Double): Double {
        val fl = floor(t)
        val i = fl.toLong()
        val a = ph[(i and (ring - 1).toLong()).toInt()]
        val b = ph[((i + 1) and (ring - 1).toLong()).toInt()]
        return a + (b - a) * (t - fl)
    }

    private fun meanF(t0: Double, t1: Double) = centre + (phaseAt(t1) - phaseAt(t0)) * fs / (2 * PI * (t1 - t0))
    private fun boxF(i: Long) = centre + (ph[(i and (ring - 1).toLong()).toInt()] - ph[((i - box) and (ring - 1).toLong()).toInt()]) * fs / (2 * PI * box)

    private fun sample(x: Float) {
        val k = (n % 80).toInt()
        val mr = oscR[k] * x; val mi = oscI[k] * x
        hr[hpos] = mr; hr[hpos + ntaps] = mr; hi[hpos] = mi; hi[hpos + ntaps] = mi
        if (++hpos >= ntaps) hpos = 0
        var sr = 0f; var si = 0f
        for (j in 0 until ntaps) { sr += taps[j] * hr[hpos + j]; si += taps[j] * hi[hpos + j] }
        if (n > 0) phase += atan2((si * zr - sr * zi).toDouble(), (sr * zr + si * zi).toDouble())
        zr = sr; zi = si
        ph[(n and (ring - 1).toLong()).toInt()] = phase
        n++
        if (n < box + 2) return
        if (active) advance() else idle()
    }

    private fun resetLead() { vs = Vs.Idle; leadCount = 0; leadSum = 0.0; leadMean = centre }

    private fun idle() {
        val i = n - 1
        val f = boxF(i)
        pulseLog(i, f)
        val dev = f - leadMean
        when (vs) {
            Vs.Idle -> {
                if (abs(f - centre) < 130) { leadCount++; leadSum += f; leadMean = leadSum / leadCount } else { leadCount = 0; leadSum = 0.0; leadMean = centre }
                if (leadCount >= 800) { vs = Vs.Lead1; trans = 0 }
            }
            Vs.Lead1 -> when {
                abs(dev) < 130 -> { trans = 0; leadCount++; leadSum += f; leadMean = leadSum / leadCount }
                dev < -350 -> { edge1 = i - box / 2.0; vs = Vs.Break }
                dev < -130 -> if (++trans > 48) vs = Vs.Idle
                else -> vs = Vs.Idle
            }
            Vs.Break -> if (dev > -350) {
                edge2 = i - box / 2.0
                val d = edge2 - edge1
                vs = if (d in 56.0..104.0) { trans = 0; Vs.Lead2 } else Vs.Idle
            } else if (i - edge1 > 128) vs = Vs.Idle
            Vs.Lead2 -> {
                val since = i - edge2
                if (since >= 40) {
                    when {
                        abs(dev) < 130 -> trans = 0
                        dev < -350 && since > 960 -> { edge3 = i - box / 2.0; vs = if (edge3 - edge2 <= 4800) Vs.Bits else Vs.Idle }
                        dev < -130 && since > 960 -> if (++trans > 48) vs = Vs.Idle
                        else -> vs = Vs.Idle
                    }
                    if (since > 5600) vs = Vs.Idle
                }
            }
            Vs.Bits -> if (i >= edge3 + 2400 + 8) { decodeVis(); if (!active) resetLead() }
        }
        if (vs == Vs.Idle && leadCount == 0) leadMean = centre
    }

    private fun decodeVis() {
        val o = leadMean - centre
        var code = 0
        var ones = 0
        for (j in 0 until 10) {
            val f = meanF(edge3 + (30 * j + 6) * 8, edge3 + (30 * j + 24) * 8)
            if (j == 0) { if (abs(f - (1200 + o)) > 110) return; continue }
            if (j == 9) break
            val b = if (f < 1200 + o) 1 else 0
            ones += b
            if (j <= 7) code = code or (b shl (j - 1))
        }
        if (ones and 1 != 0) return
        Sstv.modes.firstOrNull { it.vis == code }?.let { start(it, true, edge3 + 2400, o) }
    }

    private fun pulseLog(i: Long, f: Double) {
        if (f < 1330) { if (!inPulse) { inPulse = true; runStart = i }; return }
        if (!inPulse) return
        inPulse = false
        val len = (i - runStart).toDouble()
        if (len < 6 || len > 320) return
        events += (runStart + i) * 0.5 - box / 2.0
        if (events.size > 8) events.removeAt(0)
        if (events.size < 5) return
        val m = events.size
        val d = (events[m - 1] - events[m - 5]) / 4
        for (k in m - 4 until m) if (abs((events[k] - events[k - 1]) - d) > 0.006 * d) return
        for (s in Sstv.modes) if (abs(d - s.lineMs * 8) < 0.01 * d) {
            start(s, false, events[m - 5] - s.syncMs * 4 - s.syncOffMs * 8, 0.0)
            return
        }
    }

    private fun start(s: Sstv.Mode, withVis: Boolean, c: Double, o: Double) {
        sp = s; vis = withVis; c0 = c; off = o
        periodS = s.lineMs * 8
        detect = 0; extract = 0; nFit = 0; sk = 0.0; sc = 0.0; skk = 0.0; skc = 0.0; fa = 0.0; fb = 0.0; missed = 0; slant = 0.0
        rgb = IntArray(s.width * s.height)
        rows = 0; pendLine = -1
        id++
        t0 = System.currentTimeMillis()
        active = true
        resetLead(); events.clear(); inPulse = false
        dirty = true
    }

    private fun ratio() = if (nFit >= 8 && fb > 0) fb / periodS else 1.0

    private fun lineStart(k: Int): Double = when {
        nFit == 0 -> c0 + k * periodS
        nFit >= 8 && fb > 0 -> c0 + fa + fb * k
        else -> c0 + fa + k * periodS
    }

    private fun advance() {
        val now = n - 2.0
        while (true) {
            val r = ratio()
            if (detect < sp.totalLines) {
                val hw = (if (detect == 0 && vis) 40.0 else 10.0) * 8
                val s = lineStart(detect) + sp.syncOffMs * 8 * r
                if (now >= s + hw + sp.syncMs * 8 + 4) { detectSync(detect, s, hw); detect++; if (!active) return; continue }
            }
            if (extract < detect && extract < sp.totalLines && now >= lineStart(extract) + periodS * r + 8) {
                extractLine(extract)
                extract++
                if (extract >= sp.totalLines) { finish(true); return }
                continue
            }
            break
        }
    }

    private fun detectSync(k: Int, sPred: Double, hw: Double) {
        val w = sp.syncMs * 8
        val afterVis = k == 0 && vis
        val lo = if (afterVis) sPred - 32 else sPred - hw
        val steps = (sPred + hw - lo).toInt()
        val m = DoubleArray(steps + 1)
        var best = 0
        for (j in 0..steps) { val t = lo + j; m[j] = meanF(t, t + w); if (m[j] < m[best]) best = j }
        // After a VIS header the stop bit (1200 Hz) runs into the sync pulse: the pulse is the far end of the plateau.
        if (afterVis) for (j in steps downTo best + 1) if (m[j] <= m[best] + 8) { best = j; break }
        var tb = lo + best
        val mv = m[best]
        if (best in 1 until steps && !afterVis) {
            val a = m[best - 1]; val c = m[best + 1]; val d = a - 2 * mv + c
            if (d > 1e-9) tb += (0.5 * (a - c) / d).coerceIn(-0.5, 0.5)
        }
        val cm = tb - sp.syncOffMs * 8 * ratio()
        // Within reach of 1200 Hz: a transient (the start of a tone after silence) must not set the tuning error.
        val found = mv < 1200 + off + 110 && mv > 1200 + off - 150
        val gated = nFit >= 3 && abs(cm - lineStart(k)) > 24
        if (!found || gated) { if (++missed >= 12) finish(false); return }
        missed = 0
        if (!vis) off = if (nFit == 0) mv - 1212 else 0.9 * off + 0.1 * (mv - 1212)
        val cc = cm - c0
        nFit++; sk += k; sc += cc; skk += k.toDouble() * k; skc += k * cc
        val nn = nFit.toDouble()
        val den = nn * skk - sk * sk
        if (nFit >= 8 && den > 1e-9) {
            fb = ((nn * skc - sk * sc) / den).coerceIn(0.98 * periodS, 1.02 * periodS)
            fa = (sc - fb * sk) / nn
            slant = (fb / periodS - 1.0) * 1e6
        } else fa = (sc - periodS * sk) / nn
    }

    private fun value(f: Double) = Sstv.clamp8((f - off - 1500.0) * 255.0 / 800.0).toDouble()

    private fun meanOver(c: Double, r: Double, startMs: Double, lenMs: Double) = meanF(c + startMs * 8 * r, c + (startMs + lenMs) * 8 * r)

    private fun scan(c: Double, r: Double, s: Sstv.Scan): DoubleArray {
        val px = s.lenMs / s.px
        return DoubleArray(s.px) { value(meanOver(c, r, s.startMs + it * px, px)) }
    }

    private fun extractLine(k: Int) {
        val w = sp.width
        val c = lineStart(k); val r = ratio()
        val comp = arrayOfNulls<DoubleArray>(7)
        for (s in sp.scans) comp[s.comp] = scan(c, r, s)
        when (sp.kind) {
            Sstv.Kind.Seq -> { for (x in 0 until w) rgb[k * w + x] = (comp[Sstv.R]!![x].toInt() shl 16) or (comp[Sstv.G]!![x].toInt() shl 8) or comp[Sstv.B]!![x].toInt(); rows = k + 1 }
            Sstv.Kind.Robot72 -> { for (x in 0 until w) rgb[k * w + x] = Sstv.yuv(comp[Sstv.Y]!![x], comp[Sstv.RY]!![x / 2], comp[Sstv.BY]!![x / 2]); rows = k + 1 }
            Sstv.Kind.Robot36 -> {
                val f0 = sp.fixed[0]
                val odd = meanOver(c, r, f0.startMs + 0.5, f0.lenMs - 1.0) - off > centre
                val chroma = comp[Sstv.RY]!!
                val y = comp[Sstv.Y]!!
                if (!odd) {
                    yPend = y; ryPend = chroma; pendLine = k
                    for (x in 0 until w) rgb[k * w + x] = Sstv.yuv(y[x], chroma[x / 2], 128.0)
                } else {
                    for (x in 0 until w) {
                        val ry = if (pendLine >= 0 && pendLine == k - 1) ryPend[x / 2] else 128.0
                        if (pendLine >= 0 && pendLine == k - 1) rgb[(k - 1) * w + x] = Sstv.yuv(yPend[x], ry, chroma[x / 2])
                        rgb[k * w + x] = Sstv.yuv(y[x], ry, chroma[x / 2])
                    }
                    pendLine = -1
                }
                rows = k + 1
            }
            Sstv.Kind.PD -> {
                for (x in 0 until w) {
                    rgb[2 * k * w + x] = Sstv.yuv(comp[Sstv.Y]!![x], comp[Sstv.RY]!![x], comp[Sstv.BY]!![x])
                    rgb[(2 * k + 1) * w + x] = Sstv.yuv(comp[Sstv.Y2]!![x], comp[Sstv.RY]!![x], comp[Sstv.BY]!![x])
                }
                rows = 2 * (k + 1)
            }
        }
        dirty = true
    }

    private fun snapshot(complete: Boolean) = SstvImage(id, sp.name, sp.width, sp.height, rows, complete, vis, slant, t0, rgb.copyOf())

    private fun finish(complete: Boolean) {
        active = false
        if (rows > 4 || complete) onUpdate(snapshot(complete))
        dirty = false
        resetLead(); events.clear(); inPulse = false
    }

    /** The picture being received, when lines were added since the last call. */
    fun progress(): SstvImage? = if (active && dirty) { dirty = false; snapshot(false) } else null
}

/**
 * Narrowband FM to SSTV audio: HackRF bytes at 2 MS/s tuned [SAMPLE_RATE]/4 below the channel, the first stage shifts it
 * to zero at 1 MS/s, then 40 kS/s through a channel filter (±10 kHz, room for the ISS's Doppler), an FM discriminator,
 * a DC block (the carrier offset, Doppler included, is DC) and 8 kHz audio for the decoder.
 */
class SstvReceiver(private val decoder: SstvDecoder) {
    private val d1 = Decimator(2, 450_000.0 / 2_000_000.0, 32)
    private val d2 = FloatDecimator(25, 12_000.0 / 1_000_000.0, 128)
    private val d3 = FloatDecimator(5, 3_400.0 / 40_000.0, 48)
    private val re = FloatArray(131072 + 8)
    private val im = FloatArray(re.size)
    private val cr = FloatArray(re.size)
    private val ci = FloatArray(re.size)
    private val ar = FloatArray(re.size)
    private val ai = FloatArray(re.size)
    private val outR = FloatArray(re.size)
    private val outI = FloatArray(re.size)
    private var pr = 0f
    private var pi = 0f
    private var dc = 0.0
    @Volatile var levelDb = -120.0; private set

    fun feed(buf: ByteArray, len: Int) {
        var off = 0
        while (off < len) {
            val m = minOf(2 * 2 * 65536, len - off)
            feedComplex1M(re, im, d1.process(buf.copyOfRange(off, off + m), m, re, im, 0))
            off += m
        }
    }

    /** Complex samples at 1 MS/s with the channel at zero (tests). */
    fun feedComplex1M(r: FloatArray, i: FloatArray, n: Int) {
        val k = d2.process(r, i, n, cr, ci)
        var pw = 0.0
        for (j in 0 until k) {
            val a = cr[j]; val b = ci[j]
            pw += a * a + b * b
            var f = atan2((b * pr - a * pi).toDouble(), (a * pr + b * pi).toDouble())
            pr = a; pi = b
            // The discriminator in Hz at 40 kS/s, then the carrier offset (a slow DC) taken away.
            f = f * 40_000.0 / (2 * PI)
            dc += 0.0005 * (f - dc)
            ar[j] = (f - dc).toFloat(); ai[j] = 0f
        }
        if (k > 0) levelDb = 10 * kotlin.math.log10(pw / k + 1e-12)
        val a = d3.process(ar, ai, k, outR, outI)
        // Scaled to a few tenths, the level the decoder's filters were set for (it only looks at phase).
        for (j in 0 until a) outR[j] = outR[j] / 5000f
        decoder.feed(outR, a)
    }

    companion object {
        const val SAMPLE_RATE = 2_000_000
    }
}
