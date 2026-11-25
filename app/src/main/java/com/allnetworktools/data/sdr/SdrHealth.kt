package com.allnetworktools.data.sdr

import java.util.concurrent.atomic.AtomicLong

/** A reception problem worth a word to the user, most pressing first. */
enum class SdrAdvice {
    /** The radio sends fewer samples than asked: USB cable, hub or port. */
    SamplesLostUsb,
    /** The phone cannot keep up: buffers are dropped before the decoder. */
    SamplesLostPhone,
    /** Frames are caught (their sync is clean) but none passes its check. */
    NothingPasses,
    /** Many caught frames fail their check: reception on the edge. */
    Damaged,
    /** ADS-B: not one aircraft in two minutes. */
    NoAircraft,
}

/**
 * How reception goes, for the advice shown in the SDR tools. One tool holds the radio at a time, so the counts are
 * global: samples delivered by the radio (the radio layer counts them) and dropped on the way to the decoder (the
 * tools' buffer pools), frames that passed their check ([good]) and frames whose sync was clean but whose check failed
 * ([bad]: only decoders that can tell a real frame from noise report those).
 */
object SdrHealth {
    private val goodN = AtomicLong()
    private val badN = AtomicLong()
    private val deliveredB = AtomicLong()
    private val droppedB = AtomicLong()
    @Volatile private var rate = 0
    @Volatile private var rxSinceNs = 0L
    @Volatile private var session = 0
    /** The tool that took the radio last (a [com.allnetworktools.model.Tool] name). */
    @Volatile var owner: String? = null

    fun good() { goodN.incrementAndGet() }
    fun bad() { badN.incrementAndGet() }
    fun delivered(bytes: Int) { deliveredB.addAndGet(bytes.toLong()) }
    fun dropped(bytes: Int) { droppedB.addAndGet(bytes.toLong()) }

    /** Reception starts at [rate] samples/s; [fresh] for a new start (not the return after a transmission). */
    fun rxStarted(rate: Int, fresh: Boolean) {
        this.rate = rate
        if (fresh) session++
        rxSinceNs = System.nanoTime()
    }

    fun rxStopped() { rxSinceNs = 0L }

    fun snapshot(nowNs: Long = System.nanoTime()) = HealthSample(
        nowNs, session, rxSinceNs, rate, goodN.get(), badN.get(), deliveredB.get(), droppedB.get(),
    )
}

/** The counters at one moment. */
data class HealthSample(
    val atNs: Long, val session: Int, val rxSinceNs: Long, val rate: Int,
    val good: Long, val bad: Long, val delivered: Long, val dropped: Long,
)

/**
 * Turns samples of the counters, about one a second, into advice. In 3 of the last 5 seconds: under 85 % of the
 * samples asked for is the USB link; more than 2 % dropped is the phone. Over the last minute: 5 frames caught and none good is
 * "nothing passes", 10 or more caught with as many bad as good is "damaged". A verdict must come twice in a row to
 * show, or to go, so a passing hiccup does not flash on screen.
 */
class HealthJudge(private val adsb: Boolean = false) {
    private val history = ArrayDeque<HealthSample>()
    private var shown: SdrAdvice? = null
    private var pending: SdrAdvice? = null

    fun judge(s: HealthSample): SdrAdvice? {
        if (s.rxSinceNs == 0L || (history.isNotEmpty() && history.last().session != s.session)) {
            history.clear(); shown = null; pending = null
            if (s.rxSinceNs == 0L) return null
        }
        history.addLast(s)
        while (history.isNotEmpty() && s.atNs - history.first().atNs > 125 * SEC) history.removeFirst()
        val raw = verdict(s)
        if (raw == pending) shown = raw
        pending = raw
        return shown
    }

    private fun at(s: HealthSample, back: Long): HealthSample? = history.firstOrNull { s.atNs - it.atNs <= back }

    private fun verdict(s: HealthSample): SdrAdvice? {
        val rx = s.atNs - s.rxSinceNs
        // Samples, second by second over the last 5 s of the same stretch of reception, from 3 s after it started (the
        // radio settles): 3 bad seconds of 5 at least, so one stall does not count.
        val steps = history.filter { s.atNs - it.atNs <= 5 * SEC && it.rxSinceNs == s.rxSinceNs && it.atNs - it.rxSinceNs >= 3 * SEC }
        if (steps.size >= 5) {
            var short = 0
            var dropping = 0
            for (k in 1 until steps.size) {
                val a = steps[k - 1]; val b = steps[k]
                val dt = (b.atNs - a.atNs) / 1e9
                val got = b.delivered - a.delivered
                if (s.rate > 0 && got < 0.85 * 2.0 * s.rate * dt) short++
                if (got > 0 && b.dropped - a.dropped > 0.02 * got) dropping++
            }
            if (short >= 3) return SdrAdvice.SamplesLostUsb
            if (dropping >= 3) return SdrAdvice.SamplesLostPhone
        }
        val m = at(s, 60 * SEC) ?: return null
        val good = s.good - m.good
        val bad = s.bad - m.bad
        if (rx >= 30 * SEC && bad >= 5 && good == 0L) return SdrAdvice.NothingPasses
        if (bad >= 10 && bad >= good) return SdrAdvice.Damaged
        if (adsb && rx >= 120 * SEC) {
            val a = at(s, 120 * SEC)
            if (a != null && s.atNs - a.atNs >= 115 * SEC && s.good == a.good) return SdrAdvice.NoAircraft
        }
        return null
    }

    private companion object { const val SEC = 1_000_000_000L }
}
