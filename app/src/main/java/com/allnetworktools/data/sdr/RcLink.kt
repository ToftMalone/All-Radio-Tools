package com.allnetworktools.data.sdr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import com.allnetworktools.R
import com.allnetworktools.util.S

/** How a burst is modulated, from the shape of its short-time spectrum. */
enum class RcModulation(val label: String) {
    Chirp("LoRa (chirps)"),
    Fsk("FSK / FLRC"),
    Dsss(S(R.string.spread_spectrum_dsss)),
}

/** One packet of a frequency-hopping link: where and when, how wide, and how it is modulated. */
class RcBurst(
    /** Start, in seconds from the start of the capture. */
    val timeS: Double,
    val durationUs: Double,
    /** Absolute centre frequency. */
    val centreHz: Double,
    val bandwidthHz: Double,
    /** Peak level above the noise floor. */
    val levelDb: Double,
    val modulation: RcModulation,
)

/**
 * Cuts complex I/Q (signed bytes) into short packets: a 256-point short-time spectrum, a noise floor tracked per bin,
 * and bins rising [THRESHOLD_DB] above it grouped into bursts that may wander in frequency (a LoRa chirp sweeps its whole
 * band). Each burst gets its span, duration and modulation: an upward sweep that wraps around is LoRa, one narrow tone
 * that jumps is FSK or FLRC, the whole span lit at once is DSSS. Anything wider than [MAX_BW_HZ] (Wi-Fi, video) or longer
 * than [MAX_S] (a carrier) is not a remote-control packet and is dropped.
 */
class RcBurstDetector(private val rate: Double, private val centreHz: Double, private val onBurst: (RcBurst) -> Unit) {
    private val n = NFFT
    private val fft = Fft(n)
    private val win = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / n)).toFloat() }
    private val re = FloatArray(n)
    private val im = FloatArray(n)
    private val pow = FloatArray(n)
    private val prev = FloatArray(n)
    private val floor = FloatArray(n) // mean noise power per bin, linear
    private val above = BooleanArray(n)
    private val binHz = rate / n
    private val frameS = n / rate
    private var fill = 0
    private var frame = 0L

    private class Open(var lo: Int, var hi: Int, val start: Long, n: Int) {
        var last = start
        var widthSum = 0
        var frames = 0
        val peaks = FloatArray(MAX_PEAKS)
        var np = 0
        /** Strongest power seen in each bin, relative to the floor. */
        val maxRatio = FloatArray(n)
        /** This frame's strongest bin among the runs that joined, or −1. */
        var framePeak = -1
    }

    private val open = ArrayList<Open>()

    fun feed(buf: ByteArray, len: Int) {
        var i = 0
        while (i + 1 < len) {
            re[fill] = buf[i] / 128f
            im[fill] = buf[i + 1] / 128f
            i += 2
            if (++fill == n) { fill = 0; frame() }
        }
    }

    private fun frame() {
        for (k in 0 until n) { re[k] *= win[k]; im[k] *= win[k] }
        fft.forward(re, im)
        // Natural order: bin 0 is the lowest frequency.
        frame++
        for (k in 0 until n) {
            val j = (k + n / 2) % n
            val now = re[j] * re[j] + im[j] * im[j]
            // Two frames averaged: noise in a single bin swings too much to threshold on its own.
            val p = 0.5f * (now + prev[k])
            prev[k] = now
            pow[k] = p
            val f = floor[k]
            // Mean noise power: quiet frames move it, packets (well above it) barely do.
            floor[k] = when {
                f == 0f -> p
                frame < WARMUP -> f + (p - f) * 0.05f
                p < 4 * f -> f + (p - f) * 0.01f
                else -> f + (p - f) * 0.0002f
            }
        }
        // Lit bins: well above the noise, and within 30 dB of the frame's strongest so a strong packet's spectral
        // skirts do not light the whole capture.
        var strongest = 0f
        for (k in 0 until n) strongest = max(strongest, pow[k])
        for (k in 0 until n) {
            val dc = abs(k - n / 2) <= 1 // the HackRF's DC spike
            above[k] = !dc && frame > WARMUP && pow[k] > floor[k] * THRESHOLD && pow[k] > strongest * 1e-3f
        }
        if (frame <= WARMUP) return
        // Runs of lit bins; gaps of a few bins are bridged, since a strong packet's sidelobes are broken by nulls.
        var k = 0
        while (k < n) {
            if (!above[k]) { k++; continue }
            var e = k
            var g = k + 1
            while (g < n && g - e <= GAP_BINS) { if (above[g]) e = g; g++ }
            run(k, e)
            k = e + 1
        }
        val it = open.iterator()
        while (it.hasNext()) {
            val o = it.next()
            if (o.framePeak >= 0) {
                o.frames++
                // Width of this frame: bins within 20 dB of its peak, so sidelobes of a strong tone do not count.
                val cut = max(THRESHOLD, pow[o.framePeak] / floor[o.framePeak] / 100)
                var w = 0
                for (b in o.lo..o.hi) if (pow[b] / floor[b] >= cut) w++
                o.widthSum += w
                if (o.np < MAX_PEAKS) o.peaks[o.np++] = peakPosition(o.framePeak)
                o.framePeak = -1
            } else if (frame - o.last > GAP_FRAMES) { it.remove(); close(o) }
        }
    }

    /** Peak bin refined by parabolic interpolation, for sub-bin chirp tracking. */
    private fun peakPosition(pk: Int): Float {
        if (pk !in 1 until n - 1) return pk.toFloat()
        fun db(k: Int) = (10 * log10(pow[k] + 1e-20)).toFloat()
        val a = db(pk - 1); val b = db(pk); val c = db(pk + 1)
        val d = a - 2 * b + c
        return pk + if (d < 0) (0.5f * (a - c) / d).coerceIn(-0.5f, 0.5f) else 0f
    }

    private fun run(lo: Int, hi: Int) {
        var pk = lo
        for (k in lo..hi) if (pow[k] / floor[k] > pow[pk] / floor[pk]) pk = k
        val o = open.firstOrNull { lo <= it.hi + SLACK && hi >= it.lo - SLACK }
            ?: Open(lo, hi, frame, n).also { open += it }
        o.lo = min(o.lo, lo); o.hi = max(o.hi, hi); o.last = frame
        if (o.framePeak < 0 || pow[pk] / floor[pk] > pow[o.framePeak] / floor[o.framePeak]) o.framePeak = pk
        for (k in lo..hi) o.maxRatio[k] = max(o.maxRatio[k], pow[k] / floor[k])
    }

    private fun close(o: Open) {
        val frames = (o.last - o.start + 1).toInt()
        val dur = frames * frameS
        val top = o.maxRatio.max()
        var lo = o.lo
        var hi = o.hi
        // Occupied width: bins within 10 dB of the strongest, less one bin for the window's own spread.
        while (lo < hi && o.maxRatio[lo] < top / 10) lo++
        while (hi > lo && o.maxRatio[hi] < top / 10) hi--
        val span = hi - lo + 1
        val bw = max(1, span - 1) * binHz
        if (o.frames < MIN_FRAMES || dur > MAX_S || bw > MAX_BW_HZ) return
        val fill = (o.widthSum.toDouble() / o.frames / span).coerceAtMost(1.0)
        val mod = when {
            span >= 4 && chirpScore(o, span) >= CHIRP_SCORE && fill < 0.6 -> RcModulation.Chirp
            span >= 6 && fill >= 0.7 -> RcModulation.Dsss
            else -> RcModulation.Fsk
        }
        val centre = centreHz + ((lo + hi) / 2.0 - n / 2) * binHz
        onBurst(RcBurst(o.start * frameS, dur * 1e6, centre, bw, 10 * log10(top.toDouble()), mod))
    }

    /**
     * Share of upward moves of the tone among the moves, at the lag that sees the most movement: a chirp sweeps up (and
     * wraps from the top of its band to the bottom, which is skipped), FSK and DSSS move up and down alike.
     */
    private fun chirpScore(o: Open, span: Int): Double {
        var best = 0.0
        for (lag in intArrayOf(2, 6, 16)) {
            var up = 0
            var down = 0
            for (i in 0 until o.np - lag) {
                val d = o.peaks[i + lag] - o.peaks[i]
                if (d < -span / 2f) continue
                if (d > 0.3f) up++ else if (d < -0.3f) down++
            }
            if (up + down >= 6) best = max(best, up.toDouble() / (up + down))
        }
        return best
    }

    companion object {
        const val NFFT = 256
        private const val WARMUP = 1000
        private const val THRESHOLD = 10f // 10 dB
        private const val SLACK = 4
        private const val GAP_FRAMES = 3
        private const val GAP_BINS = 6
        private const val MIN_FRAMES = 3
        private const val MAX_PEAKS = 4096
        private const val MAX_S = 0.03
        private const val MAX_BW_HZ = 3.5e6
        private const val CHIRP_SCORE = 0.7
    }
}

/** Where to listen for remote-control links: 20 MS/s captures over each band. */
object RcBands {
    class Window(val centreMhz: Double, val band: Int)

    /** 24: 2400–2480 MHz, 868: 858–878, 915: 900–930; 0 for all. */
    fun windows(band: Int): List<Window> = when (band) {
        24 -> listOf(2410.0, 2430.0, 2450.0, 2470.0).map { Window(it, 24) }
        868 -> listOf(Window(868.0, 868))
        915 -> listOf(910.0, 920.0).map { Window(it, 915) }
        else -> windows(24) + windows(868) + windows(915)
    }

    fun label(band: Int) = when (band) { 24 -> S(R.string.n_2_4_ghz); 868 -> "868 MHz"; 915 -> "915 MHz"; else -> "" }
}

/** A remote-control link the tracker has recognised. */
data class RcLink(
    val key: String,
    val protocol: String,
    /** Mode of the protocol the rate points to, e.g. "250 Hz" or "F1000". */
    val variant: String,
    val band: Int,
    val modulation: RcModulation,
    val rateHz: Double,
    val bandwidthKHz: Double,
    val packetUs: Double,
    val channels: Int,
    val levelDb: Double,
    val levels: List<Double>,
    val bursts: Int,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    /** What was checked against the protocol's signature, in plain words. */
    val checks: List<String>,
)

/** A protocol's radio signature, from its published parameters. */
private class Signature(
    val protocol: String,
    val bands: Set<Int>,
    /** Chirp for LoRa; FSK, FLRC and DSSS links are told apart by rate and width, not by this. */
    val modulation: RcModulation,
    val bwKHz: ClosedFloatingPointRange<Double>,
    /** Packet rates and the name of each mode. */
    val rates: List<Pair<Double, String>>,
    /** Hop channel spacing when the protocol uses a fixed grid, checked on the frequencies heard. */
    val spacingKHz: Double? = null,
)

private fun hz(vararg r: Double) = r.map { it to "${Math.round(it)} Hz" }

private val SIGNATURES = listOf(
    Signature(S(R.string.expresslrs_2_4_ghz), setOf(24), RcModulation.Chirp, 650.0..1300.0, hz(50.0, 100.0, 150.0, 250.0, 333.0, 500.0), 1000.0),
    Signature(S(R.string.expresslrs_2_4_ghz), setOf(24), RcModulation.Fsk, 900.0..3200.0, listOf(500.0 to "F500 (FLRC)", 1000.0 to "F1000 (FLRC)"), 1000.0),
    Signature(S(R.string.frsky_accst_access_2_4_ghz), setOf(24), RcModulation.Fsk, 150.0..900.0, listOf(1000.0 / 9 to S(R.string.n_9_ms_frame))),
    Signature("FlySky AFHDS 2A", setOf(24), RcModulation.Fsk, 150.0..900.0, listOf(1000.0 / 3.85 to S(R.string.n_3_85_ms_frame))),
    Signature("Spektrum DSMX", setOf(24), RcModulation.Dsss, 500.0..3200.0, listOf(1000.0 / 22 to S(R.string.n_22_ms_frame), 1000.0 / 11 to S(R.string.n_11_ms_frame))),
    Signature("ExpressLRS 868 MHz", setOf(868), RcModulation.Chirp, 400.0..850.0, hz(25.0, 50.0, 100.0, 200.0), 525.0),
    Signature("ExpressLRS 915 MHz", setOf(915), RcModulation.Chirp, 400.0..850.0, hz(25.0, 50.0, 100.0, 200.0), 600.0),
    Signature("TBS Crossfire", setOf(868, 915), RcModulation.Fsk, 100.0..600.0, listOf(150.0 to "150 Hz (FSK)")),
)

/**
 * Groups bursts into links and names them. A remote control sends packets of one fixed length at a fixed rate, hopping
 * over many frequencies, so bursts are grouped by band and airtime (within [DURATION_TOLERANCE]). Intervals are measured
 * inside one listening window only, so they are whole multiples of the packet period even when the link hops out of
 * view. A group is shown only when it matches a protocol's signature on every point.
 */
class RcLinkTracker {
    private class Hit(val atMs: Long, val timeS: Double, val dwell: Int, val b: RcBurst)

    private class Group(val band: Int, val refUs: Double, val firstMs: Long) {
        val hits = ArrayList<Hit>()
        val key = "$band-${Math.round(refUs)}-$firstMs"
    }

    private val groups = ArrayList<Group>()

    /** [b]'s time counts within listening window [dwell], which is used for intervals. */
    fun add(band: Int, dwell: Int, b: RcBurst, atMs: Long) {
        if (b.durationUs < MIN_PACKET_US) return
        val g = groups.firstOrNull { it.band == band && abs(b.durationUs - it.refUs) <= DURATION_TOLERANCE * it.refUs }
            ?: Group(band, b.durationUs, atMs).also { groups += it }
        g.hits += Hit(atMs, b.timeS, dwell, b)
        if (g.hits.size > MAX_HITS) g.hits.removeAt(0)
    }

    fun links(nowMs: Long): List<RcLink> {
        groups.forEach { g -> g.hits.removeAll { nowMs - it.atMs > FORGET_MS } }
        groups.removeAll { it.hits.isEmpty() }
        return groups.mapNotNull { classify(it) }.sortedByDescending { it.lastSeenMs }
    }

    private fun intervals(hits: List<Hit>): List<Double> {
        val d = ArrayList<Double>()
        for (i in 1 until hits.size) {
            if (hits[i].dwell == hits[i - 1].dwell) {
                val x = hits[i].timeS - hits[i - 1].timeS
                if (x > 2e-4) d += x
            }
        }
        return d
    }

    /** Share of intervals that are a whole number (at least one) of [period], to within [tol] of it. */
    private fun fit(d: List<Double>, period: Double, tol: Double = 0.08): Double = d.count { x ->
        val m = Math.round(x / period)
        m >= 1 && abs(x - m * period) < max(tol * period, 60e-6)
    }.toDouble() / d.size

    /** Distinct frequencies, packets closer than 400 kHz counting as one channel. */
    private fun channels(hits: List<Hit>): Int {
        val f = hits.map { it.b.centreHz }.sorted()
        var n = 0
        var last = Double.NEGATIVE_INFINITY
        for (x in f) if (x - last >= 400e3) { n++; last = x }
        return n
    }

    /**
     * Whether the gaps between the frequencies heard are whole numbers of [spacing]: a fixed hop grid. The grid's offset
     * does not matter, so the HackRF's own frequency error does not either.
     */
    private fun onGrid(hits: List<Hit>, spacing: Double): Boolean {
        val f = ArrayList<Double>()
        for (x in hits.map { it.b.centreHz }.sorted()) if (f.isEmpty() || x - f.last() >= 0.6 * spacing) f += x
        if (f.size < 5) return false
        var ok = 0
        for (i in 1 until f.size) {
            val q = (f[i] - f[i - 1]) / spacing
            if (abs(q - Math.round(q)) < 0.25) ok++
        }
        return ok >= 0.75 * (f.size - 1)
    }

    /**
     * Names the group only when everything matches one protocol: band, modulation, width, a packet rate that the
     * intervals follow tightly, an airtime that fits inside the period, enough hop channels and, where the protocol has
     * one, its channel grid. Anything else (Meshtastic, LoRaWAN, sensors, Bluetooth, Wi-Fi) stays unnamed and hidden.
     */
    private fun classify(g: Group): RcLink? {
        val hits = g.hits
        if (hits.size < MIN_BURSTS) return null
        val band = g.band
        // Most common modulation: FSK packets can look like DSSS when bits change inside one FFT frame.
        val mod = hits.groupingBy { it.b.modulation }.eachCount().maxBy { it.value }.key
        val chirp = mod == RcModulation.Chirp
        val bw = hits.map { it.b.bandwidthHz }.sorted()[hits.size * 3 / 4] / 1e3 // packets cut by the window edge are narrower
        val dur = hits.map { it.b.durationUs }.sorted()[hits.size / 2]
        val channels = channels(hits)
        if (channels < MIN_CHANNELS) return null // fixed frequency: Meshtastic, LoRaWAN gateways, sensors
        val d = intervals(hits)
        if (d.size < MIN_INTERVALS) return null
        // Bluetooth sits on its 625 µs slot grid, which no remote-control rate follows.
        if (band == 24 && !chirp && fit(d, 625e-6) > 0.9 && fit(d, 1e-3) < 0.95) return null
        for (s in SIGNATURES) {
            if (band !in s.bands || (s.modulation == RcModulation.Chirp) != chirp || bw !in s.bwKHz) continue
            // The longest period that fits: shorter ones (higher rates) fit any multiple of it too.
            val (rate, variant) = s.rates.sortedBy { it.first }.firstOrNull { fit(d, 1 / it.first) >= FIT } ?: continue
            if (dur * 1e-6 > AIRTIME_SHARE / rate) continue // a packet longer than its slot is not this protocol
            if (s.spacingKHz != null && !onGrid(hits, s.spacingKHz * 1e3)) continue
            val checks = buildList {
                add("Modulation ${s.modulation.label}")
                add(S(R.string.width_khz, Math.round(bw)))
                add(S(R.string.rate_packets_s_of_gaps_as, Math.round(rate), Math.round(fit(d, 1 / rate) * 100)))
                add(S(R.string.s_packet_within_its_slot, Math.round(dur)))
                add(S(R.string.hops_over_frequencies, channels))
                if (s.spacingKHz != null) add(S(R.string.khz_channel_grid, Math.round(s.spacingKHz)))
            }
            return RcLink(
                g.key, s.protocol, variant, band, s.modulation, rate, bw, dur, channels,
                hits.last().b.levelDb, hits.takeLast(40).map { it.b.levelDb }, hits.size, g.firstMs, hits.last().atMs, checks,
            )
        }
        return null
    }

    fun clear() = groups.clear()

    private companion object {
        const val MAX_HITS = 600
        const val FORGET_MS = 45_000L
        const val MIN_PACKET_US = 60.0
        const val DURATION_TOLERANCE = 0.2
        const val MIN_BURSTS = 12
        const val MIN_CHANNELS = 5
        const val MIN_INTERVALS = 10
        const val FIT = 0.85
        const val AIRTIME_SHARE = 0.8
    }
}
