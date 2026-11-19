package com.allnetworktools.data.sdr

import com.allnetworktools.model.Tool
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * What a signal seen on the spectrum probably is: its frequency against the band plans (European first, the common
 * worldwide ones too), and its measured width against the width each kind of transmission takes. A guess with a
 * confidence, never a certainty: anyone may transmit anything anywhere.
 */
object SignalId {
    /** A known use of a piece of spectrum. [key] names its description; [tool] is the app's decoder for it, if any. */
    class Kind(
        val key: String,
        val name: String,
        val ranges: List<LongRange>,
        val bwMin: Double,
        val bwMax: Double,
        /** How specific the entry is: a single channel weighs more than a whole band. */
        val weight: Double,
        val tool: Tool? = null,
    )

    private const val k = 1_000L
    private const val M = 1_000_000L
    private fun band(lo: Double, hi: Double) = listOf((lo * M).toLong()..(hi * M).toLong())
    private fun spots(halfHz: Long, vararg mhz: Double) = mhz.map { val c = (it * M).toLong(); (c - halfHz)..(c + halfHz) }

    val Kinds: List<Kind> = listOf(
        // Broadcast
        Kind("fm", "FM broadcast", band(87.5, 108.0), 80e3, 300e3, 1.5, Tool.Listen),
        Kind("dab", "DAB+", band(174.0, 240.0), 1.3e6, 1.7e6, 1.5),
        Kind("dvbt", "DVB-T (TNT)", band(470.0, 694.0), 6.5e6, 8.5e6, 1.5),
        Kind("sw", "Shortwave broadcast", band(5.9, 6.2) + band(7.2, 7.45) + band(9.4, 9.9) + band(11.6, 12.1) + band(13.57, 13.87) + band(15.1, 15.8) + band(17.48, 17.9) + band(21.45, 21.85), 4e3, 12e3, 1.5, Tool.Listen),
        // Aviation
        Kind("air", "Airband (AM voice)", band(118.0, 137.0), 3e3, 12e3, 1.0, Tool.Listen),
        Kind("acars", "ACARS", spots(10 * k, 129.125, 130.025, 130.425, 130.45, 131.125, 131.475, 131.525, 131.55, 131.725, 131.825, 131.85), 2e3, 10e3, 3.0, Tool.Acars),
        Kind("vdl2", "VDL Mode 2", spots(12 * k, 136.725, 136.775, 136.825, 136.875, 136.975), 6e3, 20e3, 3.0, Tool.Vdl2),
        Kind("adsb", "ADS-B / Mode S", spots(1_200 * k, 1090.0), 0.8e6, 3e6, 3.0, Tool.Adsb),
        Kind("modes_up", "Mode S interrogation", spots(1_200 * k, 1030.0), 0.8e6, 3e6, 2.0),
        Kind("milair", "Military airband (UHF)", band(225.0, 400.0), 3e3, 25e3, 0.6, Tool.Listen),
        Kind("flarm", "FLARM", spots(40 * k, 868.2, 868.4), 40e3, 160e3, 2.5, Tool.Flarm),
        // Sea
        Kind("marine", "Marine VHF", band(156.0, 162.05), 8e3, 20e3, 1.0, Tool.Listen),
        Kind("dsc", "Marine DSC", spots(8 * k, 156.525), 4e3, 20e3, 3.0, Tool.Dsc),
        Kind("ais", "AIS", spots(13 * k, 161.975, 162.025), 8e3, 30e3, 3.0, Tool.Ais),
        Kind("sarsat", "COSPAS-SARSAT beacon", band(406.0, 406.1), 1e3, 6e3, 3.0, Tool.Sarsat),
        // Satellites and weather
        Kind("meteor", "Meteor-M LRPT", spots(80 * k, 137.1, 137.9), 90e3, 180e3, 2.5, Tool.Meteor),
        Kind("wxsat", "Weather satellite", band(137.0, 138.0), 20e3, 60e3, 1.5),
        Kind("sonde", "Radiosonde", band(400.15, 406.0), 3e3, 20e3, 2.0, Tool.Sonde),
        Kind("gnss", "GPS / Galileo L1", spots(2_000 * k, 1575.42), 1e6, 25e6, 2.0),
        Kind("iridium", "Iridium", band(1616.0, 1626.5), 20e3, 50e3, 2.0),
        Kind("inmarsat", "Inmarsat", band(1525.0, 1559.0), 1e3, 100e3, 1.0),
        Kind("amsat", "Amateur satellite / ISS", spots(15 * k, 145.8, 145.825, 437.8), 2e3, 20e3, 2.0, Tool.Listen),
        // Amateur radio
        Kind("ham_hf", "Amateur HF", band(1.81, 2.0) + band(3.5, 3.8) + band(7.0, 7.2) + band(10.1, 10.15) + band(14.0, 14.35) + band(18.068, 18.168) + band(21.0, 21.45) + band(24.89, 24.99) + band(28.0, 29.7) + band(50.0, 52.0), 50.0, 3.2e3, 1.2, Tool.Listen),
        Kind("ft8", "FT8 / FT4", spots(3 * k, 3.573, 7.074, 10.136, 14.074, 18.1, 21.074, 24.915, 28.074, 50.313), 50.0, 3.2e3, 3.0, Tool.Listen),
        Kind("cb", "CB (27 MHz)", band(26.965, 27.405), 4e3, 12e3, 2.0, Tool.Listen),
        Kind("ham2m", "Amateur 2 m", band(144.0, 146.0), 2e3, 16e3, 1.0, Tool.Listen),
        Kind("aprs", "APRS", spots(9 * k, 144.8, 144.39), 8e3, 20e3, 3.0, Tool.Aprs),
        Kind("ham70", "Amateur 70 cm", band(430.0, 440.0), 2e3, 20e3, 1.0, Tool.Listen),
        Kind("lora_aprs", "LoRa APRS", spots(130 * k, 433.775), 100e3, 150e3, 3.0, Tool.LoraAprs),
        Kind("ham23", "Amateur 23 cm", band(1240.0, 1300.0), 2e3, 20e6, 0.8),
        // Short range, licence free
        Kind("pmr446", "PMR446 walkie-talkie", band(446.0, 446.2), 4e3, 16e3, 2.0, Tool.Pmr),
        Kind("ism433", "433 MHz devices (remotes, sensors)", band(433.05, 434.79), 1e3, 300e3, 1.5, Tool.Ism),
        Kind("tpms", "Tyre pressure sensor", spots(100 * k, 433.92, 315.0), 20e3, 120e3, 1.2, Tool.Tpms),
        Kind("srd868", "868 MHz devices", band(863.0, 870.0), 1e3, 600e3, 1.0, Tool.Ism),
        Kind("audio863", "Wireless audio (863–865 MHz)", band(863.0, 865.0), 100e3, 300e3, 1.5),
        Kind("lorawan", "LoRaWAN", spots(70 * k, 867.1, 867.3, 867.5, 867.7, 867.9, 868.1, 868.3, 868.5, 869.525), 100e3, 150e3, 2.5, Tool.Lpwan),
        Kind("sigfox", "Sigfox", band(868.034, 868.226), 50.0, 1e3, 2.5, Tool.Lpwan),
        Kind("meshtastic", "Meshtastic", spots(130 * k, 869.525, 433.875) + spots(130 * k, 906.875), 200e3, 270e3, 3.0, Tool.Meshtastic),
        Kind("meshcore", "MeshCore", spots(40 * k, 869.618), 50e3, 75e3, 3.0, Tool.MeshCore),
        Kind("zwave", "Z-Wave", spots(50 * k, 868.42, 869.85, 908.42), 20e3, 120e3, 3.0, Tool.Zwave),
        Kind("wmbus", "Wireless M-Bus (meters)", spots(100 * k, 868.95, 868.3) + band(169.4, 169.475), 2e3, 300e3, 2.5, Tool.Wmbus),
        Kind("pocsag", "Pagers (POCSAG)", band(466.025, 466.23) + band(169.6, 169.8125), 4e3, 25e3, 2.0, Tool.Pocsag),
        Kind("ism915", "915 MHz devices (Americas)", band(902.0, 928.0), 1e3, 600e3, 1.0, Tool.Ism),
        Kind("dect", "DECT cordless phone", band(1880.0, 1900.0), 1e6, 2e6, 2.0, Tool.Dect),
        Kind("wifi24", "Wi-Fi 2.4 GHz", band(2400.0, 2483.5), 15e6, 45e6, 1.5),
        Kind("ble", "Bluetooth LE advertising", spots(1_200 * k, 2402.0, 2426.0, 2480.0), 0.8e6, 2.5e6, 2.0),
        Kind("zigbee", "Zigbee / Thread", (11..26).flatMap { spots(1_200 * k, 2405.0 + 5 * (it - 11)) }, 1.5e6, 3e6, 1.5, Tool.Zigbee),
        Kind("ant", "ANT+ (sport sensors)", spots(1_100 * k, 2457.0), 0.5e6, 2e6, 2.5, Tool.AntPlus),
        Kind("wifi5", "Wi-Fi 5 GHz", band(5150.0, 5850.0), 15e6, 170e6, 1.0),
        Kind("fpv", "Drone FPV video", band(5645.0, 5945.0), 8e6, 30e6, 2.0, Tool.Fpv),
        // Mobile networks (base stations, then phones)
        Kind("gsmr", "GSM-R (railways)", band(921.0, 925.0), 150e3, 250e3, 2.0),
        Kind("dl900", "Mobile base stations 900 MHz (2G/3G/4G)", band(925.0, 960.0), 150e3, 11e6, 1.5),
        Kind("dl800", "Mobile base stations 800 MHz (4G/5G)", band(791.0, 821.0), 4e6, 11e6, 1.5),
        Kind("dl700", "Mobile base stations 700 MHz (4G/5G)", band(758.0, 788.0), 4e6, 11e6, 1.5),
        Kind("dl1800", "Mobile base stations 1800 MHz (2G/4G)", band(1805.0, 1880.0), 150e3, 21e6, 1.5),
        Kind("dl2100", "Mobile base stations 2100 MHz (3G/4G/5G)", band(2110.0, 2170.0), 4e6, 21e6, 1.5),
        Kind("dl2600", "Mobile base stations 2600 MHz (4G)", band(2620.0, 2690.0), 4e6, 21e6, 1.5),
        Kind("nr3500", "5G 3.5 GHz", band(3400.0, 3800.0), 20e6, 100e6, 1.5),
        Kind("ul", "Phones transmitting (uplink)", band(832.0, 862.0) + band(703.0, 733.0) + band(880.0, 915.0) + band(1710.0, 1785.0) + band(1920.0, 1980.0) + band(2500.0, 2570.0), 150e3, 21e6, 1.0),
        Kind("tetra", "TETRA (emergency services)", band(380.0, 400.0), 18e3, 30e3, 1.5),
        Kind("pmse", "Wireless microphones", band(470.0, 694.0), 100e3, 250e3, 1.0),
    )

    /** What the spectrum says about the signal under the marker. */
    data class Measure(
        /** Centre of the signal found near the marker, Hz; the marker itself when no signal stands out. */
        val hz: Long,
        val snrDb: Float,
        /** Occupied width at 10 dB under the signal's top, Hz; null when no signal stands out or it is too narrow to measure. */
        val bwHz: Double?,
        /** One point of the spectrum, Hz: nothing narrower than a few of them can be measured. */
        val resolutionHz: Double,
        /** The signal runs past the edge of the view: its width is at least [bwHz]. */
        val truncated: Boolean,
    ) {
        val present get() = snrDb >= MIN_SNR_DB
        /** Too narrow for this view: zooming in measures it. */
        val tooNarrow get() = present && bwHz == null
    }

    const val MIN_SNR_DB = 6f
    /** Bins the spectrum is smoothed over before measuring a width (odd). */
    private const val SMOOTH_MIN = 5

    /** The signal at [at] (0 to 1 along [db]) on a view of [spanHz] centred on [centerHz]. */
    fun measure(db: FloatArray, at: Float, centerHz: Double, spanHz: Double): Measure {
        val n = db.size
        val res = spanHz / n
        fun hzOf(i: Double) = (centerHz - spanHz / 2 + (i + 0.5) * res).toLong()
        val markerIdx = (at * (n - 1)).roundToInt().coerceIn(0, n - 1)
        // The noise floor: a low percentile, so a signal filling most of the view still stands out of it.
        val floor = db.sortedArray()[n / 10]
        // Lightly smoothed (in power): an FSK signal's spectrum is humps and dips (4FSK has four), and the FFT ripples.
        val w = max(SMOOTH_MIN, n / 256) or 1
        val half = w / 2
        val lin = DoubleArray(n) { Math.pow(10.0, db[it] / 10.0) }
        val sm = FloatArray(n) { i ->
            var acc = 0.0
            var cnt = 0
            for (j in max(0, i - half)..minOf(n - 1, i + half)) { acc += lin[j]; cnt++ }
            (10 * log10(acc / cnt)).toFloat()
        }
        val reach = max(3, n / 100)
        var near = markerIdx
        for (i in max(0, markerIdx - reach)..minOf(n - 1, markerIdx + reach)) if (sm[i] > sm[near]) near = i
        val snr = sm[near] - floor
        if (snr < MIN_SNR_DB) return Measure(hzOf(markerIdx.toDouble()), snr, null, res, false)
        // Grows the run of bins above [thr] around [from], stepping over dips shorter than [gap] bins that stay above
        // [dip]: a gap down to the noise is between two signals, not inside one.
        fun grow(from: Int, thr: Float, gap: Int, dip: Float = Float.NEGATIVE_INFINITY): IntArray {
            fun across(i: Int, dir: Int): Int {
                var k = 1
                while (k <= gap) {
                    val j = i + dir * k
                    if (j !in 0 until n) return 0
                    if (sm[j] > thr) return k
                    if (sm[j] <= dip) return 0
                    k++
                }
                return 0
            }
            var l = from
            var r = from
            while (true) { val k = across(l, -1); if (k == 0) break; l -= k }
            while (true) { val k = across(r, 1); if (k == 0) break; r += k }
            return intArrayOf(l, r)
        }
        // First the whole signal, roughly (half way up from the floor), so that where the marker sits in it does not
        // matter; then its width 10 dB under its own top, as the signal is usually described.
        val coarse = grow(near, floor + snr / 2, max(2, w))
        var top = coarse[0]
        for (i in coarse[0]..coarse[1]) if (sm[i] > sm[top]) top = i
        val span0 = coarse[1] - coarse[0] + 1
        val (l, r) = grow(top, max(sm[top] - 10f, floor + 4f), max(max(2, w), span0 / 8), floor + (sm[top] - floor) / 4).let { it[0] to it[1] }
        val truncated = l == 0 || r == n - 1
        // Smoothing widened the run by w - 1 bins.
        val bins = (r - l + 1) - (w - 1)
        val bw = if (bins <= 2) null else bins * res
        return Measure(hzOf((l + r) / 2.0), snr, bw, res, truncated)
    }

    /** A candidate and how likely it is, 0 to 1 among those found. */
    data class Guess(val kind: Kind, val confidence: Double, val bandwidthFits: Boolean?)

    /** The most likely kinds for [m], best first, at most [limit]. */
    fun identify(m: Measure, limit: Int = 3): List<Guess> {
        val scored = Kinds.mapNotNull { kd ->
            if (kd.ranges.none { m.hz in it }) return@mapNotNull null
            var score = kd.weight
            var fits: Boolean? = null
            val bw = m.bwHz
            if (bw != null) {
                // How far outside the expected widths, in factors of two; the view's resolution blurs narrow ones.
                val lo = kd.bwMin / 1.6
                val hi = max(kd.bwMax * 1.6, m.resolutionHz * 3)
                val off = when {
                    bw < lo -> if (m.truncated) 0.0 else ln(lo / bw) / ln(2.0)
                    bw > hi -> ln(bw / hi) / ln(2.0)
                    else -> 0.0
                }
                fits = off == 0.0
                score *= if (off == 0.0) 2.5 else max(0.08, 1.0 / (1 + off * off))
            } else if (m.tooNarrow) {
                // Narrower than a few points: only the kinds narrower than that are likely.
                fits = kd.bwMin <= m.resolutionHz * 3
                score *= if (fits) 1.6 else 0.35
            }
            kd to (score to fits)
        }
        val total = scored.sumOf { it.second.first }
        if (total <= 0) return emptyList()
        return scored.sortedByDescending { it.second.first }.take(limit).map { (kd, sf) -> Guess(kd, sf.first / total, sf.second) }
    }

    /** A width as people write it: "12.5 kHz", "1.5 MHz". */
    fun widthLabel(hz: Double): String = when {
        hz >= 1e6 -> "%.1f MHz".format(java.util.Locale.ROOT, hz / 1e6).replace(".0 ", " ")
        hz >= 1e3 -> (if (hz >= 1e5) "%.0f kHz" else "%.1f kHz").format(java.util.Locale.ROOT, hz / 1e3).replace(".0 ", " ")
        else -> "%.0f Hz".format(java.util.Locale.ROOT, hz)
    }
}
