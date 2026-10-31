package com.allnetworktools.data.sdr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** An analogue FPV channel: one of the six 5.8 GHz bands of 8 channels, or a 2.4 GHz frequency (band 'G'). */
data class FpvChannel(val band: Char, val number: Int, val mhz: Int) {
    val name: String get() = if (band == 'G') "$mhz" else "$band$number"
}

object FpvChannels {
    private val table = mapOf(
        'A' to intArrayOf(5865, 5845, 5825, 5805, 5785, 5765, 5745, 5725),
        'B' to intArrayOf(5733, 5752, 5771, 5790, 5809, 5828, 5847, 5866),
        'E' to intArrayOf(5705, 5685, 5665, 5645, 5885, 5905, 5925, 5945),
        'F' to intArrayOf(5740, 5760, 5780, 5800, 5820, 5840, 5860, 5880),
        'R' to intArrayOf(5658, 5695, 5732, 5769, 5806, 5843, 5880, 5917),
        'L' to intArrayOf(5362, 5399, 5436, 5473, 5510, 5547, 5584, 5621),
    )

    val bands: List<Char> = listOf('R', 'F', 'E', 'A', 'B', 'L')

    val all: List<FpvChannel> = bands.flatMap { b -> table.getValue(b).mapIndexed { i, f -> FpvChannel(b, i + 1, f) } }

    fun byName(name: String): FpvChannel? = all.firstOrNull { it.name == name }

    /** Channels sorted by frequency, for the scan order. */
    val byFrequency: List<FpvChannel> = all.sortedBy { it.mhz }

    /**
     * 2.4 GHz analogue video transmitters have no common channel plan (the usual 4- and 8-channel sets sit on 2370–2510 MHz in steps of
     * 18–20 MHz), so the scan walks 2360–2520 MHz in 10 MHz steps: an FM picture is about 17 MHz wide and is seen from two neighbours.
     */
    val ghz24: List<FpvChannel> = (0..16).map { FpvChannel('G', it + 1, 2360 + 10 * it) }

    /** What a sweep covers: 58 = the 48 channels at 5.8 GHz, 24 = the 2.4 GHz grid, anything else = both. */
    fun scan(band: Int): List<FpvChannel> = when (band) {
        58 -> byFrequency
        24 -> ghz24
        else -> ghz24 + byFrequency
    }
}

/** Atan2 to about 1e-4 rad: the discriminator runs at every sample of a 16 MS/s stream. */
internal fun fastAtan2(y: Float, x: Float): Float {
    val ax = abs(x)
    val ay = abs(y)
    val mx = max(ax, ay)
    if (mx < 1e-20f) return 0f
    val a = min(ax, ay) / mx
    val s = a * a
    var r = ((-0.0464964749f * s + 0.15931422f) * s - 0.327622764f) * s * a + a
    if (ay > ax) r = 1.57079637f - r
    if (x < 0) r = 3.14159274f - r
    return if (y < 0) -r else r
}

/** What the analogue video decoder has seen so far. */
class VideoStats {
    var hPulses = 0
        internal set
    var hSpaced = 0 // pulses whose spacing is a line period
        internal set
    var vSyncs = 0
        internal set
    var fields = 0
        internal set
    var lineUs = 0.0
        internal set
    var linesPerField = 0
        internal set
    var polarity = 0 // +1: sync is the lowest frequency, −1: the highest, 0: unknown
        internal set

    /** Share of sync pulses that came one line after the previous one: close to 1 for real video, low for anything else. */
    val syncQuality: Double get() = if (hPulses < 8) 0.0 else hSpaced.toDouble() / hPulses

    val standard: String? get() = when {
        lineUs <= 0 -> null
        lineUs > 63.78 && lineUs < 64.4 -> "PAL"
        lineUs > 63.1 && lineUs <= 63.78 -> "NTSC"
        else -> null
    }
}

/**
 * Analogue FPV video: the signal is frequency-modulated, with the picture as the baseband. From complex I/Q (signed
 * bytes) at 8 or 16 MS/s, centred on the channel: FM discriminator, 3.2 MHz low-pass (drops the colour subcarrier and keeps
 * luminance), decimation to 8 MS/s, then line and field synchronisation from the sync pulses (4.7 µs each line, broad
 * pulses at the field). Colour is not decoded; the picture is the luminance, [WIDTH] pixels per line.
 */
class AnalogVideoDecoder(
    private val inputRate: Double,
    private val onLine: (field: Int, line: Int, pixels: ByteArray) -> Unit = { _, _, _ -> },
    private val onField: () -> Unit = {},
) {
    private val dec = (inputRate / RATE).toInt().coerceAtLeast(1)
    private val taps = if (dec == 2) 31 else 15
    private val h: FloatArray = FloatArray(taps).also { c ->
        val fc = 3.2e6 / inputRate
        val m = (taps - 1) / 2.0
        var sum = 0.0
        for (i in 0 until taps) {
            val x = i - m
            val sinc = if (x == 0.0) 2 * fc else sin(2 * PI * fc * x) / (PI * x)
            val w = 0.42 - 0.5 * cos(2 * PI * i / (taps - 1)) + 0.08 * cos(4 * PI * i / (taps - 1))
            c[i] = (sinc * w).toFloat(); sum += sinc * w
        }
        for (i in 0 until taps) c[i] = (c[i] / sum).toFloat()
    }
    private val hist = FloatArray(2 * taps)
    private var hPos = 0
    private var phase = 0

    private var dcI = 0f
    private var dcQ = 0f
    private var prevI = 0f
    private var prevQ = 0f

    val stats = VideoStats()

    // The 8 MS/s demodulated stream is analysed in blocks; the tail is kept so a line is never cut.
    private val block = FloatArray(BLOCK + KEEP + 1024)
    private var fill = 0
    private var scanPos = 0
    private var absStart = 0L // absolute index of block[0]
    private var blocks = 0

    private var polarity = 0
    private var lastHStart = -1L
    /** Start of the last pulse taken as a line sync: half-line pulses and dark specks do not move it. */
    private var lastLineStart = -1L
    private var broadCount = 0
    private var lastBroadStart = -1L
    private var firstBroadStart = -1L
    private var fieldLine = -1
    private var field = 0
    private val pixels = ByteArray(WIDTH)
    private val sortBuf = FloatArray(BLOCK / 8 + 8)

    fun feed(buf: ByteArray, len: Int) {
        var i = 0
        while (i + 1 < len) {
            var re = buf[i].toFloat() / 128f
            var im = buf[i + 1].toFloat() / 128f
            i += 2
            dcI += (re - dcI) * 1e-4f; dcQ += (im - dcQ) * 1e-4f
            re -= dcI; im -= dcQ
            // Instantaneous frequency: the phase step between consecutive samples.
            val d = fastAtan2(im * prevI - re * prevQ, re * prevI + im * prevQ)
            prevI = re; prevQ = im
            hist[hPos] = d; hist[hPos + taps] = d
            hPos++
            if (hPos == taps) hPos = 0
            if (++phase == dec) {
                phase = 0
                var acc = 0f
                for (k in 0 until taps) acc += h[k] * hist[hPos + k]
                block[fill++] = acc
                if (fill >= BLOCK + KEEP) analyse()
            }
        }
    }

    private fun analyse() {
        val n = fill
        var k = 0
        var j = 0
        while (j < n && k < sortBuf.size) { sortBuf[k++] = block[j]; j += 8 }
        java.util.Arrays.sort(sortBuf, 0, k)
        val p2 = sortBuf[(k * 0.02).toInt()]
        val p98 = sortBuf[(k * 0.98).toInt().coerceAtMost(k - 1)]
        if (p98 - p2 < 1e-3f) { carry(n, n); return } // a carrier or noise, nothing modulated
        if (polarity == 0 || blocks % 100 == 0) polarity = choosePolarity(n, p2, p98)
        blocks++
        val sgn = if (polarity == 0) 1f else polarity.toFloat()
        stats.polarity = polarity
        val sLo = if (sgn > 0) p2 else -p98
        val sHi = if (sgn > 0) p98 else -p2
        val thr = sLo + 0.25f * (sHi - sLo)
        var idx = scanPos
        val limit = n - KEEP // the pixels of a line are read up to 600 samples after its pulse
        while (idx < limit) {
            if (sgn * block[idx] < thr) {
                var e = idx
                while (e < n && sgn * block[e] < thr) e++
                if (e >= n) break
                pulse(idx, e - idx, sgn, sHi)
                idx = e
            } else idx++
        }
        carry(n, idx)
    }

    private fun carry(n: Int, scanned: Int) {
        val from = maxOf(0, n - KEEP)
        System.arraycopy(block, from, block, 0, n - from)
        absStart += from
        fill = n - from
        scanPos = maxOf(0, scanned - from)
    }

    private fun choosePolarity(n: Int, p2: Float, p98: Float): Int {
        var best = 0
        var bestScore = 0
        for (sg in intArrayOf(1, -1)) {
            val sLo = if (sg > 0) p2 else -p98
            val sHi = if (sg > 0) p98 else -p2
            val thr = sLo + 0.25f * (sHi - sLo)
            var count = 0
            var idx = 0
            while (idx < n) {
                if (sg * block[idx] < thr) {
                    var e = idx
                    while (e < n && sg * block[e] < thr) e++
                    if ((e - idx) / 8.0 in 3.3..6.5) count++
                    idx = e
                } else idx++
            }
            if (count > bestScore) { bestScore = count; best = sg }
        }
        return if (bestScore >= 8) best else polarity
    }

    private fun pulse(start: Int, width: Int, sgn: Float, sHi: Float) {
        val us = width / 8.0
        val s = absStart + start
        when {
            us in 3.3..6.5 -> hSync(start, s, sgn, sHi)
            us in 20.0..31.0 -> {
                // The broad pulses of the field sync come every half line. A dark patch of the picture can be as wide,
                // but it comes back a whole line later: counting it would start the field in the middle of the picture
                // and roll the image.
                val sinceUs = (s - lastBroadStart) / 8.0
                if (lastBroadStart >= 0 && sinceUs in 28.0..36.0) broadCount++ else { broadCount = 1; firstBroadStart = s }
                lastBroadStart = s
                if (broadCount == 3) {
                    stats.vSyncs++
                    if (fieldLine > 100) { stats.linesPerField = fieldLine; stats.fields++; onField() }
                    // Which field: the broad pulses start on a line boundary in one, half a line later in the other.
                    val lineUs = if (stats.lineUs > 0) stats.lineUs else 64.0
                    field = if (lastLineStart >= 0) {
                        val lines = (firstBroadStart - lastLineStart) / 8.0 / lineUs
                        val frac = lines - kotlin.math.floor(lines)
                        if (frac in 0.25..0.75) 1 else 0
                    } else field xor 1
                    fieldLine = -1
                }
            }
        }
    }

    private fun hSync(start: Int, s: Long, sgn: Float, sHi: Float) {
        stats.hPulses++
        val gap = if (lastHStart >= 0) (s - lastHStart) / 8.0 else 0.0
        lastHStart = s
        if (gap in 62.0..66.0) {
            stats.hSpaced++
            stats.lineUs += (gap - stats.lineUs) * (if (stats.lineUs == 0.0) 1.0 else 0.02)
        }
        // Pulses closer than a line to the last line sync belong to the field sync (half-line pulses), or are a dark
        // speck of the picture: neither starts a line, and neither moves the line timing.
        val previous = lastLineStart
        val lineGap = if (previous >= 0) (s - previous) / 8.0 else 0.0
        if (previous >= 0 && lineGap < 62.0) return
        lastLineStart = s
        // A lost pulse leaves a hole of a whole number of lines.
        val missed = if (previous >= 0 && lineGap > 66.0 && lineGap < 330.0) Math.round(lineGap / 64.0).toInt() - 1 else 0
        fieldLine = if (fieldLine < 0) 0 else fieldLine + 1 + missed
        val first = start + (10.5 * 8).toInt()
        if (first + WIDTH > fill) return
        // Black level from the back porch, white from the top of the demodulated range.
        val bpStart = start + (6.5 * 8).toInt()
        var bp = 0f
        for (q in 0 until 20) bp += sgn * block[bpStart + q]
        val black = bp / 20
        val white = maxOf(sHi, black + 1e-3f)
        for (x in 0 until WIDTH) {
            val v = (sgn * block[first + x] - black) / (white - black)
            pixels[x] = (v.coerceIn(0f, 1f) * 255f).toInt().toByte()
        }
        onLine(field, fieldLine, pixels)
    }

    companion object {
        const val RATE = 8e6
        const val WIDTH = 416 // 52 µs of picture at 8 MS/s
        private const val KEEP = 1024
        private const val BLOCK = 32768 // 4 ms
    }
}

/** Power of the channel in dBFS over [len] bytes of I/Q. */
fun iqPowerDb(buf: ByteArray, len: Int): Double {
    var sum = 0.0
    var i = 0
    while (i + 1 < len) { val a = buf[i] / 128.0; val b = buf[i + 1] / 128.0; sum += a * a + b * b; i += 2 }
    val n = len / 2
    return if (n == 0) -120.0 else 10 * log10(sum / n + 1e-12)
}

/** What a pass over one analogue channel found. */
data class FpvResult(val channel: FpvChannel, val powerDb: Double, val syncQuality: Double, val hPulses: Int, val standard: String?, val atMs: Long) {
    /** Line and field sync pulses at the right spacing: that is video, not just a transmitter. */
    val isVideo: Boolean get() = hPulses >= 40 && syncQuality >= 0.5

    /** Some sync at the right spacing but not enough to call: worth a longer listen. */
    val isCandidate: Boolean get() = !isVideo && hPulses >= 10 && syncQuality >= 0.2
}

/** One analogue FPV drone: a video transmitter, followed from sweep to sweep. */
data class AnalogDrone(
    val channel: FpvChannel,
    val standard: String?,
    val syncQuality: Double,
    val levelDb: Double,
    /** Recent levels, oldest first, for the trend. */
    val levels: List<Double>,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    /** Sweeps since the video was last seen: 0 while it is on the air. */
    val missedSweeps: Int,
) {
    val lost: Boolean get() = missedSweeps > 0
}

/** Rising, falling or steady signal: compares the mean of the last three levels with the three before. */
fun levelTrend(levels: List<Double>): Int {
    if (levels.size < 4) return 0
    val n = minOf(3, levels.size / 2)
    val recent = levels.takeLast(n).average()
    val before = levels.dropLast(n).takeLast(n).average()
    return when {
        recent - before > 3 -> 1
        before - recent > 3 -> -1
        else -> 0
    }
}

/**
 * Turns sweep results into drones. A transmitter spills onto neighbouring channels (R7 and F8 share 5880 MHz, E5 is
 * 5 MHz away), so videos closer than [MERGE_MHZ] are one drone, named after its strongest channel. A drone that stops
 * showing video is kept, marked lost, for [KEEP_SWEEPS] sweeps.
 */
class AnalogDroneTracker {
    private val drones = LinkedHashMap<Int, AnalogDrone>() // by frequency in MHz
    private val seen = HashSet<Int>() // drones that showed video during the current sweep

    /** One channel measured during the sweep; only video counts. Returns true when it is a drone not known before. */
    fun found(r: FpvResult, nowMs: Long): Boolean {
        if (!r.isVideo) return false
        val key = drones.keys.firstOrNull { kotlin.math.abs(it - r.channel.mhz) < MERGE_MHZ }
        val old = key?.let { drones.getValue(it) }
        // The same transmitter seen on a neighbouring channel this sweep, but weaker: not a second drone.
        if (old != null && key in seen && old.levelDb >= r.powerDb) return false
        if (key != null) { drones.remove(key); seen.remove(key) }
        drones[r.channel.mhz] = AnalogDrone(
            r.channel, r.standard ?: old?.standard, r.syncQuality, r.powerDb,
            ((old?.levels ?: emptyList()) + r.powerDb).takeLast(MAX_LEVELS),
            old?.firstSeenMs ?: nowMs, nowMs, 0,
        )
        seen += r.channel.mhz
        return old == null
    }

    /** Closes a sweep: drones that showed no video are marked lost, then forgotten after [KEEP_SWEEPS]. */
    fun endSweep() {
        val missed = drones.filterKeys { it !in seen }.mapValues { (_, d) -> d.copy(missedSweeps = d.missedSweeps + 1) }
        missed.forEach { (k, d) -> if (d.missedSweeps > KEEP_SWEEPS) drones.remove(k) else drones[k] = d }
        seen.clear()
    }

    fun sweep(results: List<FpvResult>, nowMs: Long) {
        results.forEach { found(it, nowMs) }
        endSweep()
    }

    /** Level of the drone being watched, measured continuously instead of once a sweep. */
    fun watched(ch: FpvChannel, levelDb: Double, standard: String?, quality: Double, nowMs: Long) {
        val key = drones.keys.firstOrNull { kotlin.math.abs(it - ch.mhz) < MERGE_MHZ } ?: return
        val d = drones.getValue(key)
        val video = quality >= 0.5
        drones[key] = d.copy(
            levelDb = levelDb, standard = standard ?: d.standard, syncQuality = quality,
            levels = (d.levels + levelDb).takeLast(MAX_LEVELS),
            lastSeenMs = if (video) nowMs else d.lastSeenMs, missedSweeps = if (video) 0 else d.missedSweeps,
        )
    }

    /** On the air first, strongest first. */
    fun list(): List<AnalogDrone> = drones.values.sortedWith(compareBy<AnalogDrone> { it.lost }.thenByDescending { it.levelDb })

    fun clear() { drones.clear(); seen.clear() }

    private companion object {
        const val MERGE_MHZ = 12
        const val KEEP_SWEEPS = 3
        const val MAX_LEVELS = 40
    }
}
