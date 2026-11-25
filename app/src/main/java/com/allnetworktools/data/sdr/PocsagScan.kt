package com.allnetworktools.data.sdr

import kotlin.math.abs
import kotlin.math.roundToLong

/** A paging carrier found by the scan: where, how strong, and the pages decoded on it at each bit rate. */
data class PagerCarrier(
    val hz: Long,
    val peakDb: Float,
    val firstHeardMs: Long,
    val lastHeardMs: Long,
    /** Pages decoded, by bit rate (512, 1200, 2400). */
    val pagesByBaud: Map<Int, Int>,
) {
    val pages: Int get() = pagesByBaud.values.sum()
    /** The rate that decoded most pages here; null until one did. */
    val bestBaud: Int? get() = pagesByBaud.filterValues { it > 0 }.maxByOrNull { it.value }?.key
}

/**
 * Pagers anywhere in the 2 MHz captured: carriers that key up are found on the spectrum, snapped to the 6.25 kHz
 * raster, and each one gets its own POCSAG decoder at the three bit rates. The list says which rate decodes most pages
 * on each carrier (OnAir's "scan the span for pagers").
 */
class PocsagScanner(
    val centerHz: Long,
    private val onMessage: (PocsagMessage) -> Unit,
    private val onCarriers: (List<PagerCarrier>) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Tracked(val hz: Long, var peakDb: Float, val first: Long, var last: Long, val byBaud: HashMap<Int, Int> = HashMap(), var rx: PocsagReceiver? = null)

    private val analyzer = SpectrumAnalyzer(FFT)
    private val tracked = LinkedHashMap<Long, Tracked>()
    private val recent = HashMap<String, Long>()
    private var lastLookMs = 0L
    private var lastReportMs = 0L

    fun feed(buf: ByteArray, len: Int) {
        analyzer.accumulate(buf, len, 2)
        val now = clock()
        if (now - lastLookMs >= LOOK_MS) {
            lastLookMs = now
            analyzer.frame()?.let { look(it, now) }
        }
        for (t in tracked.values) t.rx?.feed(buf, len)
        if (now - lastReportMs >= 1_000) { lastReportMs = now; report() }
    }

    /** Carriers in one averaged frame: local peaks [MIN_SNR_DB] over the median floor, away from the DC spike. */
    internal fun look(db: FloatArray, now: Long) {
        for (hz in carriers(db, centerHz)) {
            // A centroid that wobbles by one raster step is the same carrier.
            val t = tracked.values.firstOrNull { abs(it.hz - hz) <= RASTER_HZ } ?: Tracked(hz, -200f, now, now).also { tracked[hz] = it }
            t.last = now
            t.peakDb = maxOf(t.peakDb, db[binOf(hz, centerHz)])
            if (t.rx == null) attach(t)
        }
    }

    private fun attach(t: Tracked) {
        // At most MAX_DECODERS at once: the carrier heard longest ago without a page gives its decoder up.
        val live = tracked.values.filter { it.rx != null }
        if (live.size >= MAX_DECODERS) {
            val drop = live.filter { it.byBaud.values.sum() == 0 }.minByOrNull { it.last } ?: return
            drop.rx = null
        }
        t.rx = PocsagReceiver(listOf(t.hz), centerHz) { m ->
            t.byBaud[m.baud] = (t.byBaud[m.baud] ?: 0) + 1
            // The same page from several transmitters of the network: shown once.
            val key = "${m.ric}/${m.function}/${m.text}"
            val now = clock()
            val seen = recent.put(key, now)
            if (recent.size > 512) recent.entries.removeAll { now - it.value > 120_000 }
            if (seen == null || now - seen > 60_000) onMessage(m)
        }
    }

    private fun report() {
        onCarriers(tracked.values.map { PagerCarrier(it.hz, it.peakDb, it.first, it.last, HashMap(it.byBaud)) }.sortedBy { it.hz })
    }

    companion object {
        const val FFT = 1024
        const val SAMPLE_RATE = PocsagReceiver.SAMPLE_RATE
        private const val LOOK_MS = 250L
        const val MIN_SNR_DB = 12f
        const val MAX_DECODERS = 8
        /** The DC spike and the band edges, where nothing is trusted. */
        private const val DC_GUARD_HZ = 20_000
        private const val EDGE_HZ = 900_000
        const val RASTER_HZ = 6_250L
        /** Wider than this is not a pager (broadcast, digital TV, a wideband burst). */
        const val MAX_WIDTH_HZ = 30_000.0

        internal fun binOf(hz: Long, centerHz: Long): Int =
            ((hz - centerHz).toDouble() / SAMPLE_RATE * FFT + FFT / 2).toInt().coerceIn(0, FFT - 1)

        /**
         * The carriers of one frame (lowest frequency first), on the paging raster: each run of bins [MIN_SNR_DB] over
         * the median floor is one carrier at its power centroid (an FSK pager shows two peaks, one per tone), if it is
         * no wider than [MAX_WIDTH_HZ].
         */
        fun carriers(db: FloatArray, centerHz: Long): List<Long> {
            val floor = db.sorted()[db.size / 2]
            val binHz = SAMPLE_RATE.toDouble() / db.size
            val out = LinkedHashSet<Long>()
            var i = 0
            while (i < db.size) {
                if (db[i] < floor + MIN_SNR_DB) { i++; continue }
                val start = i
                var sumP = 0.0
                var sumPi = 0.0
                // A gap of one bin between the two tones of an FSK carrier does not end the run.
                while (i < db.size && (db[i] >= floor + MIN_SNR_DB || (i + 1 < db.size && db[i + 1] >= floor + MIN_SNR_DB && i > start))) {
                    val p = Math.pow(10.0, (db[i] - floor) / 10.0)
                    sumP += p; sumPi += p * i
                    i++
                }
                val width = (i - start) * binHz
                if (width > MAX_WIDTH_HZ) continue
                val off = (sumPi / sumP - db.size / 2) * binHz
                if (abs(off) < DC_GUARD_HZ || abs(off) > EDGE_HZ) continue
                out += ((centerHz + off) / RASTER_HZ).roundToLong() * RASTER_HZ
            }
            return out.toList()
        }
    }
}
