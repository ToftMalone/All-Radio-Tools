package com.allnetworktools.data.ultra

import com.allnetworktools.data.sdr.Fft
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Spectra of the microphone, half-overlapping Hann windows, in dB below full scale. Ultrasound for a phone is the top
 * of what its microphone hears: 15 kHz to half the sample rate (24 kHz at 48 kHz, 48 kHz at 96 kHz).
 */
class UltraSpectrum(val rate: Int, val size: Int = if (rate >= 96_000) 4096 else 2048, private val onFrame: (FloatArray) -> Unit) {
    private val fft = Fft(size)
    private val window = FloatArray(size) { (0.5 - 0.5 * cos(2 * PI * it / (size - 1))).toFloat() }
    private val gain = window.sum().let { it * it } / 4
    private val buf = FloatArray(size)
    private var fill = 0
    private val re = FloatArray(size)
    private val im = FloatArray(size)

    val binHz: Double get() = rate.toDouble() / size
    fun binOf(hz: Double) = (hz / binHz).toInt().coerceIn(0, size / 2 - 1)

    /** Samples between -1 and 1. */
    fun feed(x: FloatArray, n: Int) {
        for (i in 0 until n) {
            buf[fill++] = x[i]
            if (fill == size) {
                frame()
                // Half overlap: the second half becomes the first.
                System.arraycopy(buf, size / 2, buf, 0, size / 2)
                fill = size / 2
            }
        }
    }

    private fun frame() {
        for (i in 0 until size) { re[i] = buf[i] * window[i]; im[i] = 0f }
        fft.forward(re, im)
        val out = FloatArray(size / 2) { k -> (10 * log10(max((re[k] * re[k] + im[k] * im[k]) / gain, 1e-14f))).toFloat() }
        onFrame(out)
    }
}

/** What the detector recognised. */
enum class UltraKind { TrackingBeacon, Repellent, Sweep, Jammer, ScreenWhine, Whine }

/** One finding, with the frequency it sits at and how far it stands above the noise. */
data class UltraFinding(val kind: UltraKind, val hz: Double, val loHz: Double, val hiHz: Double, val snrDb: Float, val firstMs: Long, val lastMs: Long)

/**
 * Recognises what sounds in the ultrasound band, from consecutive spectra. Tones are tracked over time (a tone is a
 * peak 15 dB over the band's noise); then:
 *
 * - a tone that moves over more than [SWEEP_HZ] is a sweeping emitter (pest repellers sweep to keep animals from
 *   getting used to them);
 * - a steady strong tone held for [STEADY_MS] is a repellent ("mosquito" anti-loitering boxes, around 17.4 kHz) or a
 *   whining power supply when weak; 15.6 to 15.8 kHz is the line frequency of old televisions and monitors;
 * - short bursts between 17.5 and 20.5 kHz, at several frequencies or repeated, are the pattern of audio beacons that
 *   advertising apps listen for (data sent as tones);
 * - the top of the band raised as a whole, far over the floor below it, is broadband ultrasonic noise, the trace of a
 *   microphone jammer.
 */
class UltraDetector(private val binHz: Double, private val topHz: Double) {
    private class Track(var hz: Double, var lo: Double, var hi: Double, val firstMs: Long, var lastMs: Long, var snr: Float, var frames: Int)

    private val live = ArrayList<Track>()
    private val ended = ArrayDeque<Track>()
    private var broadbandSinceMs = -1L
    private var broadbandLastMs = -1L
    private var broadbandSnr = 0f
    var floorDb = -120f; private set

    fun feed(db: FloatArray, nowMs: Long) {
        val lo = (LOW_HZ / binHz).toInt()
        val hi = min(db.size - 2, (topHz / binHz).toInt())
        if (hi <= lo + 4) return
        val band = db.copyOfRange(lo, hi).sortedArray()
        val floor = band[band.size / 2]
        floorDb = floor
        // Peaks: local maxima 15 dB over the floor, the strongest of neighbours within 150 Hz.
        val peaks = ArrayList<Pair<Double, Float>>()
        val reach = max(1, (150 / binHz).toInt())
        var k = lo + 1
        while (k < hi) {
            val v = db[k]
            if (v - floor >= TONE_DB && v >= db[k - 1] && v >= db[k + 1]) {
                var best = k
                for (j in k..min(hi, k + reach)) if (db[j] > db[best]) best = j
                peaks += best * binHz to (db[best] - floor)
                k = best + reach
            } else k++
        }
        // A strong tone leaks into its neighbours (the window's side lobes): those are not tones of their own.
        val masked = peaks.filter { p -> peaks.none { q -> q !== p && abs(q.first - p.first) < MASK_HZ && q.second - p.second >= MASK_DB } }
        peaks.clear(); peaks.addAll(masked)
        // Broadband: the top two kilohertz far over the floor of 14 to 16 kHz, on most of their width.
        val refLo = (14_000 / binHz).toInt()
        val refHi = (16_000 / binHz).toInt()
        val ref = db.copyOfRange(refLo, refHi).sortedArray().let { it[it.size / 2] }
        val topLo = max(lo, ((topHz - 2_000) / binHz).toInt())
        val raised = (topLo until hi).count { db[it] - ref >= BROADBAND_DB }
        if (hi > topLo && raised > (hi - topLo) / 2) {
            if (broadbandSinceMs < 0) broadbandSinceMs = nowMs
            broadbandLastMs = nowMs
            broadbandSnr = (topLo until hi).map { db[it] - ref }.average().toFloat()
        } else if (broadbandLastMs >= 0 && nowMs - broadbandLastMs > 2_000) broadbandSinceMs = -1
        // Tones follow their nearest peak within 400 Hz from one spectrum to the next.
        val used = BooleanArray(peaks.size)
        for (t in live) {
            var bestI = -1
            var bestD = 400.0
            peaks.forEachIndexed { i, p -> val d = abs(p.first - t.hz); if (!used[i] && d < bestD) { bestD = d; bestI = i } }
            if (bestI >= 0) {
                used[bestI] = true
                val (hz, snr) = peaks[bestI]
                t.hz = hz; t.lo = min(t.lo, hz); t.hi = max(t.hi, hz); t.lastMs = nowMs; t.snr = max(t.snr, snr); t.frames++
            }
        }
        peaks.forEachIndexed { i, p -> if (!used[i]) live += Track(p.first, p.first, p.first, nowMs, nowMs, p.second, 1) }
        val gone = live.filter { nowMs - it.lastMs > GAP_MS }
        live.removeAll(gone)
        // A single spectrum is a click, not a tone.
        gone.filter { it.frames >= 2 }.forEach { ended.addLast(it) }
        while (ended.isNotEmpty() && nowMs - ended.first().lastMs > MEMORY_MS) ended.removeFirst()
    }

    /** What sounds now, or did in the last [MEMORY_MS]. */
    fun findings(nowMs: Long): List<UltraFinding> {
        val out = ArrayList<UltraFinding>()
        val all = (ended + live.filter { it.frames >= 2 })
        for (t in all) {
            val dur = t.lastMs - t.firstMs
            val kind = when {
                t.hi - t.lo >= SWEEP_HZ -> UltraKind.Sweep
                dur >= STEADY_MS && t.hz in 15_600.0..15_800.0 -> UltraKind.ScreenWhine
                dur >= STEADY_MS && t.snr >= REPELLENT_DB && t.hz >= 16_500 -> UltraKind.Repellent
                dur >= STEADY_MS -> UltraKind.Whine
                else -> null
            } ?: continue
            out += UltraFinding(kind, t.hz, t.lo, t.hi, t.snr, t.firstMs, t.lastMs)
        }
        // Beacons: short tones in their band, either several frequencies close in time or one repeated.
        val bursts = all.filter { it.lastMs - it.firstMs < STEADY_MS && it.hz in BEACON_LO..BEACON_HI && it.hi - it.lo < SWEEP_HZ }
        if (bursts.size >= 2) {
            val distinct = bursts.map { (it.hz / 100).toInt() }.distinct().size
            val repeated = bursts.groupBy { (it.hz / 100).toInt() }.values.any { it.size >= 2 }
            if (distinct >= 2 || repeated) out += UltraFinding(
                UltraKind.TrackingBeacon, bursts.map { it.hz }.average(), bursts.minOf { it.lo }, bursts.maxOf { it.hi },
                bursts.maxOf { it.snr }, bursts.minOf { it.firstMs }, bursts.maxOf { it.lastMs },
            )
        }
        if (broadbandSinceMs >= 0 && broadbandLastMs - broadbandSinceMs >= 1_000) {
            out += UltraFinding(UltraKind.Jammer, topHz - 1_000, topHz - 2_000, topHz, broadbandSnr, broadbandSinceMs, broadbandLastMs)
        }
        return out.sortedByDescending { it.lastMs }
    }

    fun clear() { live.clear(); ended.clear(); broadbandSinceMs = -1; broadbandLastMs = -1 }

    companion object {
        const val LOW_HZ = 15_000.0
        const val TONE_DB = 15f
        const val REPELLENT_DB = 30f
        const val BROADBAND_DB = 20f
        const val SWEEP_HZ = 800.0
        const val STEADY_MS = 4_000L
        const val GAP_MS = 400L
        const val MEMORY_MS = 30_000L
        const val BEACON_LO = 17_500.0
        const val BEACON_HI = 20_500.0
        const val MASK_HZ = 1_500.0
        const val MASK_DB = 20f
    }
}

/**
 * The ultrasound level in a band, for the leak finder: the energy between [loHz] and the top of what the microphone
 * hears, in dB, smoothed, with the strongest frequency.
 */
class UltraLevel(private val binHz: Double, private val loHz: Double, private val topHz: Double) {
    var levelDb = -120f; private set
    var peakHz = 0.0; private set

    fun feed(db: FloatArray) {
        val lo = (loHz / binHz).toInt()
        val hi = min(db.size - 1, (topHz / binHz).toInt())
        if (hi <= lo) return
        var sum = 0.0
        var best = lo
        for (k in lo until hi) {
            sum += Math.pow(10.0, db[k] / 10.0)
            if (db[k] > db[best]) best = k
        }
        val now = (10 * log10(max(sum, 1e-14))).toFloat()
        levelDb = if (levelDb < -119f) now else levelDb * 0.7f + now * 0.3f
        peakHz = best * binHz
    }
}

/**
 * The sonar: a short chirp from the loudspeaker, and its echo in the microphone. The chirp is found by correlating the
 * recording with it (a matched filter): the first and strongest arrival is the direct path inside the phone, the next
 * clear one is the echo off what the phone points at; their delay is the round trip at the speed of sound.
 */
object Sonar {
    const val SPEED_OF_SOUND = 343.0
    /** Closer than this, the echo hides in the tail of the direct sound. */
    const val MIN_M = 0.35
    const val MAX_M = 4.0

    /** A [ms] long linear chirp from [f0] to [f1] Hz at [rate], with soft edges. */
    fun chirp(rate: Int, f0: Double = 17_500.0, f1: Double = 21_500.0, ms: Double = 5.0): FloatArray {
        val n = (rate * ms / 1000).toInt()
        val k = (f1 - f0) / (n.toDouble() / rate)
        return FloatArray(n) { i ->
            val t = i.toDouble() / rate
            val edge = min(1.0, min(i, n - 1 - i) / (n * 0.1))
            (sin(2 * PI * (f0 * t + k * t * t / 2)) * edge).toFloat()
        }
    }

    /** The envelope of the recording correlated with [chirp], one value per sample (the chirp's length shorter). */
    fun correlate(rec: FloatArray, chirp: FloatArray): FloatArray {
        val m = chirp.size
        val n = rec.size - m
        if (n <= 0) return FloatArray(0)
        val c = FloatArray(n)
        for (i in 0 until n) {
            var s = 0f
            for (j in 0 until m) s += rec[i + j] * chirp[j]
            c[i] = abs(s)
        }
        // Peak envelope over a quarter period of the chirp's centre frequency, so the carrier's ripple goes.
        val w = 3
        return FloatArray(n) { i -> var mx = 0f; for (j in max(0, i - w)..min(n - 1, i + w)) mx = max(mx, c[j]); mx }
    }

    /** A measured distance: metres, and how far the echo stands over the background (a quality, 1 is barely). */
    data class Echo(val distanceM: Double, val strength: Double, val directIndex: Int)

    /** The echo in [rec] (recorded at [rate]); null when no direct sound or no clear echo is found. */
    fun measure(rec: FloatArray, rate: Int, chirp: FloatArray): Echo? {
        val env = correlate(rec, chirp)
        if (env.isEmpty()) return null
        var d = 0
        for (i in env.indices) if (env[i] > env[d]) d = i
        val noise = env.copyOf().also { it.sort() }[env.size / 2].toDouble().coerceAtLeast(1e-9)
        if (env[d] < noise * 8) return null
        val from = d + (2 * MIN_M / SPEED_OF_SOUND * rate).toInt()
        val to = min(env.size - 1, d + (2 * MAX_M / SPEED_OF_SOUND * rate).toInt())
        if (from >= to) return null
        // The direct sound fades: what is left of it at each delay is taken away (a decay fitted on its own tail).
        var e = from
        var bestScore = 0.0
        for (i in from..to) {
            val tail = env[d] * Math.exp(-(i - d).toDouble() / (rate * 0.0012))
            val score = env[i] - tail
            if (score > bestScore) { bestScore = score; e = i }
        }
        val strength = bestScore / noise
        if (strength < 4) return null
        val dt = (e - d).toDouble() / rate
        return Echo(dt * SPEED_OF_SOUND / 2, strength, d)
    }

    /** The RMS level of [x], dBFS. */
    fun rmsDb(x: FloatArray): Double = 20 * log10(max(1e-9, sqrt(x.sumOf { (it * it).toDouble() } / max(1, x.size))))
}
