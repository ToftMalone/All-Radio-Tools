package com.allnetworktools.data.sdr

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * What a tool's signals ask of the front end. The figures follow the reasoning of OnAir's auto-gain guide
 * (github.com/adswill/OnAir, wiki "Auto gain"): a narrow weak-signal mode wants its noise well above the converter's own,
 * a wide channel wants converter headroom, pulses are judged on their peaks, broadcast FM is strong and spares the LNA.
 */
enum class GainProfile(
    /** RMS level aimed for, in dBFS, and the dead band around it where nothing changes. */
    val targetRms: Double, val window: Double,
    /** Peaks stay under this share of full scale. */
    val maxPeak: Double,
    /** The noise floor wants to be this many dB over the 8-bit converter's quantisation noise. */
    val marginGoal: Double,
    /** Highest LNA gain allowed (strong signals overload the LNA first). */
    val lnaCap: Int,
) {
    Narrow(-26.0, 4.0, 0.6, 14.0, 40),
    Wideband(-16.0, 4.0, 0.8, 10.0, 40),
    Pulsed(-26.0, 4.0, 0.7, 12.0, 40),
    Strong(-20.0, 3.0, 0.7, 6.0, 24),
}

/** One look at the converter: RMS without the DC offset, peak, clipped share and the noise floor's margin over quantisation noise. */
data class GainMeasure(val rmsDbfs: Double, val peak: Double, val clip: Double, val marginDb: Double?)

/**
 * Measures the 8-bit I/Q stream over windows of [windowNs]: RMS with the DC offset taken away (the HackRF's offset is
 * not signal), the peak and the clipped share (on one pair in three), and the noise floor of a short averaged spectrum
 * (the quarter-quantile bin away from DC and the filter's roll-off) against the converter's quantisation noise.
 */
class GainMeter(private val windowNs: Long = 200_000_000L) {
    private var start = Long.MIN_VALUE
    private var n = 0L
    private var sumI = 0.0
    private var sumQ = 0.0
    private var sumSq = 0.0
    private var peak = 0
    private var clipped = 0L
    private val fft = Fft(FFT)
    private val win = FloatArray(FFT) { (0.5 - 0.5 * cos(2 * PI * it / (FFT - 1))).toFloat() }
    private val winPow = win.sumOf { (it * it).toDouble() }
    private val re = FloatArray(FFT)
    private val im = FloatArray(FFT)
    private val acc = DoubleArray(FFT)
    private var blocks = 0

    fun reset() { start = Long.MIN_VALUE; clear() }

    private fun clear() {
        n = 0; sumI = 0.0; sumQ = 0.0; sumSq = 0.0; peak = 0; clipped = 0
        acc.fill(0.0); blocks = 0
    }

    /** Interleaved signed 8-bit I/Q; a measurement once a window is full. */
    fun feed(buf: ByteArray, len: Int, nowNs: Long): GainMeasure? {
        if (start == Long.MIN_VALUE) start = nowNs
        var i = 0
        while (i + 1 < len) {
            val a = buf[i].toInt()
            val b = buf[i + 1].toInt()
            sumI += a; sumQ += b
            sumSq += a * a + b * b
            val m = max(abs(a), abs(b))
            if (m > peak) peak = m
            if (m >= 127) clipped++
            n++
            // One pair in three: plenty for the statistics, and light on the USB thread at 20 MS/s.
            i += 2 * STRIDE
        }
        // A few spectra per window are enough for the floor.
        if (blocks < MAX_BLOCKS && len >= 2 * FFT) {
            var mi = 0f
            var mq = 0f
            for (k in 0 until FFT) { mi += buf[2 * k]; mq += buf[2 * k + 1] }
            mi /= FFT; mq /= FFT
            for (k in 0 until FFT) { re[k] = (buf[2 * k] - mi) / 128f * win[k]; im[k] = (buf[2 * k + 1] - mq) / 128f * win[k] }
            fft.forward(re, im)
            for (k in 0 until FFT) acc[k] += re[k].toDouble() * re[k] + im[k].toDouble() * im[k]
            blocks++
        }
        if (nowNs - start < windowNs || n == 0L) return null
        val mI = sumI / n
        val mQ = sumQ / n
        val ac = max(sumSq / n - mI * mI - mQ * mQ, 1e-6)
        val rms = 10 * log10(ac / (128.0 * 128.0))
        val margin = if (blocks > 0) floorMargin() else null
        val out = GainMeasure(rms, peak / 128.0, clipped.toDouble() / n, margin)
        start = nowNs
        clear()
        return out
    }

    private fun floorMargin(): Double {
        val bins = ArrayList<Double>(FFT)
        val edge = (FFT * 0.08).toInt()
        for (k in 0 until FFT) {
            // FFT order: 0 is DC, the top and bottom of the band meet at FFT / 2.
            val f = if (k < FFT / 2) k else k - FFT
            if (abs(f) <= 3 || abs(f) >= FFT / 2 - edge) continue
            bins += acc[k] / blocks
        }
        bins.sort()
        val floor = bins[bins.size / 4]
        // Quantisation noise of an ideal 8-bit converter, both components, through the same window.
        val quant = 2.0 / 12.0 / (128.0 * 128.0) * winPow
        return 10 * log10(max(floor, 1e-20) / quant)
    }

    private companion object {
        const val FFT = 1024
        const val MAX_BLOCKS = 4
        const val STRIDE = 3
    }
}

/**
 * The control loop, on one overall gain (LNA + VGA in dB, the amplifier left as the user set it). Clock-free: the
 * caller passes the time, so it is tested against a simulated front end.
 *
 * - Overload (clipping or a full-scale peak) cuts at once, by 6 to 20 dB depending on how bad, then a ceiling keeps the
 *   gain from climbing straight back (10 s, or 90 s after three overloads in 30 s).
 * - The level and the noise margin decide otherwise: up when the RMS is under the dead band or the noise is too close
 *   to the converter's own (gain then buys sensitivity), down when the RMS or the peaks are too high, or a little when
 *   the margin is far more than needed.
 * - Slow up, fast down: up steps at most 6 dB every 2.5 s, down steps up to 10 dB every 0.8 s; 0.8 s of settling after
 *   every change; a reversal within 8 s needs a clearly larger error, so steady input brings no hunting.
 * - The first 8 s after a start are an acquisition with faster, bigger steps (up to 24 dB).
 * - After a burst, the gain climbs back to where it stood before (4 dB per 1.2 s).
 */
class GainLoop(val profile: GainProfile, private val minTotal: Int = 0, private val maxTotal: Int = 102, private val step: Int = 2) {
    var total = 0
        private set
    var reason = "start"
        private set
    private var changedAt = -1e9
    private var movedAt = -1e9
    private var acquireUntil = 0.0
    private var dir = 0
    private var init = false
    private var last = 0.0
    private var rms = -120.0
    private var peak = 0.0
    private var clip = 0.0
    private var margin: Double? = null
    private var ceil = Int.MAX_VALUE
    private var ceilUntil = 0.0
    private var lastOverload = -1e9
    private val overloads = ArrayDeque<Double>()
    private var preAttack = -1
    private var steady = -1

    fun begin(now: Double, current: Int) {
        total = snap(current)
        changedAt = now - 10
        movedAt = -1e9
        acquireUntil = now + 8
        dir = 0
        init = false
        ceilUntil = 0.0
        preAttack = -1
        overloads.clear()
        reason = "acquire"
    }

    private fun floorStep(db: Double): Int = (minTotal + Math.floor((db - minTotal) / step).toInt() * step).coerceIn(minTotal, maxTotal)

    private fun snap(db: Int): Int = (minTotal + Math.round((db - minTotal).toDouble() / step).toInt() * step).coerceIn(minTotal, maxTotal)

    private fun set(now: Double, t: Int, why: String): Int {
        total = t; changedAt = now; init = false; reason = why
        return t
    }

    /** The newest measurement; the new total gain when it must change. */
    fun update(now: Double, m: GainMeasure): Int? {
        if (!init) { rms = m.rmsDbfs; peak = m.peak; clip = m.clip; margin = m.marginDb; init = true; last = now }
        val dt = max(0.0, now - last)
        last = now
        val kf = 1 - exp(-dt / 0.35)
        val ks = 1 - exp(-dt / 1.5)
        rms += kf * (m.rmsDbfs - rms)
        // Overload is judged on the worst of the recent past: up at once, down slowly.
        peak = if (m.peak > peak) m.peak else peak + ks * (m.peak - peak)
        clip = if (m.clip > clip) m.clip else clip + ks * (m.clip - clip)
        m.marginDb?.let { v -> margin = margin?.let { it + kf * (v - it) } ?: v }
        val acquiring = now < acquireUntil
        if (now - changedAt > 5) steady = total
        val p = profile

        // 1. Overload: fast attack, whatever the settle time or rate limits.
        if ((clip > 0.002 || peak >= 0.99) && now - changedAt >= 0.25) {
            val amount = when { clip > 0.2 || rms > -4 -> 20; clip > 0.05 || rms > -8 -> 12; clip > 0.01 -> 8; else -> 6 }
            if (now - lastOverload > 20) preAttack = if (!acquiring && now - changedAt > 5 && total == steady) total else -1
            // One burst cuts several times in a row: that is one episode, not several.
            val episode = now - lastOverload > 2
            lastOverload = now
            if (episode) overloads.addLast(now)
            while (overloads.isNotEmpty() && now - overloads.first() > 30) overloads.removeFirst()
            val repeated = overloads.size >= 3
            ceil = if (episode || now >= ceilUntil) total - (if (repeated) 6 else 2) else min(ceil, total - 2)
            ceilUntil = now + if (repeated) 90 else 10
            val t = snap(total - amount)
            if (t >= total) { reason = "at the limit"; return null }
            dir = -1; movedAt = now
            // Judge the new level afresh, and forget the clipping that caused the cut.
            peak = 0.0; clip = 0.0
            return set(now, t, "overload")
        }
        // 2. Settling after a change.
        if (now - changedAt < if (acquiring) 0.45 else 0.8) { reason = "settling"; return null }

        // 3. What the level, the peaks and the noise margin ask for.
        val lo = p.targetRms - p.window
        val hi = p.targetRms + p.window
        val pkDb = 20 * log10(max(peak, 1e-6))
        val pkMax = 20 * log10(p.maxPeak)
        val mg = margin
        var down = 0.0
        var why = "hold"
        if (rms > hi) { down = rms - p.targetRms; why = "too hot" }
        if (pkDb > pkMax && pkDb - pkMax + 2 > down) { down = pkDb - pkMax + 2; why = "peaks" }
        // Far more margin than needed: give a little back, never down into the low side of the dead band.
        if (down <= 0 && mg != null && mg > p.marginGoal + 14 && rms > lo + 1) { down = min(min(4.0, mg - (p.marginGoal + 8)), rms - (lo + 1)); why = "gain not needed" }
        var up = 0.0
        if (down <= 0) {
            if (rms < lo) { up = p.targetRms - rms; why = "too low" }
            if (mg != null && mg < p.marginGoal - 1.5 && rms < hi - 2 && p.marginGoal - mg > up) { up = p.marginGoal - mg; why = "noise near the converter's" }
            if (up > 0) {
                // Never into a limit: the peaks, the top of the dead band, the ceiling after an overload.
                var room = hi - rms
                if (pkDb < pkMax) room = min(room, pkMax - pkDb - 1) else room = 0.0
                if (now < ceilUntil) room = min(room, (ceil - total).toDouble())
                if (room < 1) { up = 0.0; why = if (now < ceilUntil) "recovering" else "hold" } else up = min(up, room)
            }
        }
        // After a burst, back to where the gain stood before it.
        val recovering = preAttack >= 0 && total < preAttack && now - lastOverload < 90
        if (down <= 0 && up <= 0 && recovering && now - lastOverload > 2.5 && rms < p.targetRms - 1 && !(now < ceilUntil && total >= ceil)) {
            up = min(min(4.0, (preAttack - total).toDouble()), p.targetRms - rms)
            why = "recovering"
        }
        if (down <= 0 && up < 1) {
            if (preAttack >= 0 && (total >= preAttack || now - lastOverload > 90)) preAttack = -1
            reason = why
            return null
        }
        val goingDown = down > 0
        if (goingDown && down < 1) { reason = "hold"; return null }

        // 4. Rate limits and hysteresis.
        val gap = if (goingDown) (if (acquiring) 0.3 else 0.8) else (if (acquiring) 0.5 else if (recovering) 1.2 else 2.5)
        if (now - movedAt < gap) { reason = "settling"; return null }
        if (!acquiring && dir != 0 && (if (goingDown) -1 else 1) != dir && now - movedAt < 8 && (if (goingDown) down else up) < p.window * 1.5) {
            reason = "hold"; return null
        }
        val maxStep = if (acquiring) 24.0 else if (goingDown) 10.0 else 6.0
        val delta = (if (goingDown) -1 else 1) * min(maxStep, if (goingDown) down else up)
        var t = if (goingDown) snap(Math.round(total + delta).toInt()) else floorStep(total + delta)
        if (t == total && abs(delta) >= step / 2.0) t = snap(total + if (goingDown) -step else step)
        // A step up must not carry the level past the top of the dead band, or the next look brings it straight down.
        if (!goingDown && why != "recovering" && rms + (t - total) > hi - 0.5) t = maxOf(total, floorStep(total + (hi - 0.5 - rms)))
        if (t == total) { reason = if (goingDown) "at the limit" else "hold"; return null }
        dir = if (goingDown) -1 else 1
        movedAt = now
        return set(now, t, why)
    }
}

/**
 * Automatic gain for a tool: measures the samples, runs the [GainLoop] and turns its overall gain into the HackRF's
 * stages, LNA first (the best noise figure) up to the profile's limit, the VGA (2 dB steps) taking the rest. The other
 * radios get the same LNA + VGA pair, which their drivers turn into their own gain.
 */
class AutoGain(profile: GainProfile, lna: Int, vga: Int) {
    private val meter = GainMeter()
    var loop = GainLoop(profile, 0, profile.lnaCap / 8 * 8 + 62)
        private set
    private var begun = false
    private var t0 = Long.MIN_VALUE
    private var startTotal = lna + vga
    /** The stages last handed to the radio. */
    private var applied = lna to vga

    /** The last measurement, for the screen. */
    var last: GainMeasure? = null
        private set

    val current: Pair<Int, Int> get() = split(loop.total, loop.profile.lnaCap)

    /** A new profile (the tool's mode changed): a fresh acquisition from the gain in use. */
    fun reprofile(p: GainProfile) {
        if (p == loop.profile) return
        val now = loop.total
        loop = GainLoop(p, 0, p.lnaCap / 8 * 8 + 62)
        startTotal = now
        begun = false
        meter.reset()
    }

    /** Interleaved 8-bit I/Q; the new LNA and VGA gains when they must change. */
    fun feed(buf: ByteArray, len: Int, nowNs: Long): Pair<Int, Int>? {
        if (t0 == Long.MIN_VALUE) t0 = nowNs
        val now = (nowNs - t0) / 1e9
        if (!begun) {
            loop.begin(now, startTotal)
            begun = true
            // The stages for the start gain under this profile (another LNA limit splits it differently).
            val s = split(loop.total, loop.profile.lnaCap)
            if (s != applied) { applied = s; meter.reset(); return s }
        }
        val m = meter.feed(buf, len, nowNs) ?: return null
        last = m
        val before = loop.total
        val t = loop.update(now, m) ?: return null
        if (t == before) return null
        // What arrives next still carries the old gain: measure afresh.
        meter.reset()
        return split(t, loop.profile.lnaCap).also { applied = it }
    }

    companion object {
        /** LNA first in 8 dB steps (keeping at least 8 dB for the VGA to trim with), the VGA the rest in 2 dB steps. */
        fun split(total: Int, lnaCap: Int): Pair<Int, Int> {
            val cap = (lnaCap.coerceIn(0, 40) / 8) * 8
            var lna = min(cap, max(0, (total - 8) / 8 * 8))
            var vga = total - lna
            if (vga > 62) { lna = min(cap, lna + (vga - 62 + 7) / 8 * 8); vga = total - lna }
            return lna to (vga.coerceIn(0, 62) and 1.inv())
        }
    }
}
