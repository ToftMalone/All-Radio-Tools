package com.allnetworktools.data.sdr

import kotlin.math.abs
import kotlin.math.roundToLong

/** One aviation channel the scanner has heard. */
data class AirChannel(
    val hz: Long,
    /** Carrier on right now. */
    val keyed: Boolean,
    val firstHeardMs: Long,
    val lastHeardMs: Long,
    /** Time with the carrier on, summed. */
    val talkMs: Long,
    val peakDb: Float,
    /** Being played. */
    val playing: Boolean,
)

/**
 * Airband without a channel list (OnAir's automatic airband): every carrier that keys up on the aviation raster (8.33
 * and 25 kHz steps from 118 MHz) in the 1.8 MHz around [centerHz] opens a channel, which closes after [IDLE_MS] of
 * silence. The strongest channel on air is played in AM, and kept while its carrier stays up (and a moment after), so a
 * reply on another channel does not cut a sentence; a [pinned] channel is the only one played.
 */
class AirbandScanner(
    val centerHz: Long,
    private val sampleRate: Int = 2_000_000,
    onAudio: (FloatArray, Int) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class State(val hz: Long, val first: Long) {
        var keyedFrames = 0
        var keyed = false
        var last = 0L
        var talk = 0L
        var peak = -200f
        var keyedSince = 0L
    }

    private val analyzer = SpectrumAnalyzer(FFT)
    private val raster: LongArray = channelsAround(centerHz, sampleRate)
    private val states = HashMap<Long, State>()
    private var playing: Long? = null
    private var playingUntil = 0L
    private var lastFrameMs = 0L
    @Volatile var pinned: Long? = null
    /** Called when a channel opens (first carrier after a silence): the sound cue. */
    var onOpen: ((Long) -> Unit)? = null

    private val rx = ListenReceiver(sampleRate) { b, n -> if (playing != null) onAudio(b, n) }.also {
        it.mode = ListenMode.Am
        it.squelchDb = null
    }

    fun feed(buf: ByteArray, len: Int) {
        analyzer.accumulate(buf, len, 2)
        val now = clock()
        if (now - lastFrameMs >= FRAME_MS) {
            val dt = if (lastFrameMs == 0L) 0L else now - lastFrameMs
            lastFrameMs = now
            analyzer.frame()?.let { look(it, now, dt) }
        }
        playing?.let { rx.offsetHz = (it - centerHz).toDouble() }
        rx.feed(buf, len)
    }

    /** One averaged spectrum: which raster channels carry a carrier [OPEN_DB] over the floor, and which one to play. */
    internal fun look(db: FloatArray, now: Long, dtMs: Long) {
        val floor = db.sorted()[db.size / 2]
        val binHz = sampleRate.toDouble() / db.size
        for (hz in raster) {
            val c = ((hz - centerHz) / binHz + db.size / 2).roundToLong().toInt()
            var p = -200f
            for (k in (c - SPREAD_BINS)..(c + SPREAD_BINS)) if (k in db.indices && db[k] > p) p = db[k]
            val on = p > floor + OPEN_DB
            val st = states[hz]
            if (!on) { if (st != null) { st.keyed = false; st.keyedFrames = 0 }; continue }
            val s = st ?: State(hz, now).also { states[hz] = it }
            s.keyedFrames++
            // Two frames in a row: a carrier, not a click.
            if (s.keyedFrames >= 2) {
                if (!s.keyed) {
                    if (now - s.last > IDLE_MS || s.talk == 0L) onOpen?.invoke(hz)
                    s.keyedSince = now
                }
                s.keyed = true
                s.last = now
                s.talk += dtMs
                s.peak = maxOf(s.peak, p)
            }
        }
        // Channels quiet for longer than IDLE_MS close.
        states.values.removeAll { !it.keyed && now - it.last > IDLE_MS && it.last != 0L }
        choose(now)
    }

    private fun choose(now: Long) {
        val pin = pinned
        if (pin != null) { playing = pin.takeIf { states[it]?.keyed == true }; return }
        val cur = playing?.let { states[it] }
        if (cur != null && cur.keyed) { playingUntil = now + HANG_MS; return }
        if (cur != null && now < playingUntil) return
        playing = states.values.filter { it.keyed }.maxByOrNull { it.peak }?.hz
        if (playing != null) playingUntil = now + HANG_MS
    }

    /** The open channels, most recently heard first. */
    fun snapshot(): List<AirChannel> = states.values.filter { it.last != 0L }
        .map { AirChannel(it.hz, it.keyed, it.first, it.last, it.talk, it.peak, it.hz == playing) }
        .sortedByDescending { it.lastHeardMs }

    val levelDb: Double get() = rx.levelDb

    companion object {
        const val FFT = 2048
        private const val FRAME_MS = 100L
        const val OPEN_DB = 12f
        const val IDLE_MS = 30_000L
        /** A reply comes within this after the carrier drops: the channel is held. */
        const val HANG_MS = 2_000L
        /** About ±2 kHz: the carrier of a channel, not its neighbour 8.33 kHz away. */
        private const val SPREAD_BINS = 2
        const val LOW_HZ = 118_000_000L
        const val HIGH_HZ = 137_000_000L
        /** Half the window scanned; the DC spike in the middle is left out. */
        const val HALF_SPAN_HZ = 900_000L
        private const val DC_GUARD_HZ = 6_000L

        /** The aviation raster: 8.33 kHz steps (three per 25 kHz) from 118 MHz, as published frequencies are written. */
        fun channelHz(n: Long): Long = LOW_HZ + n * 25_000 / 3

        fun channelsAround(centerHz: Long, sampleRate: Int = 2_000_000): LongArray {
            val half = minOf(HALF_SPAN_HZ, sampleRate / 2L - 50_000)
            val lo = maxOf(LOW_HZ, centerHz - half)
            val hi = minOf(HIGH_HZ, centerHz + half)
            val first = ((lo - LOW_HZ) * 3 + 24_999) / 25_000
            val out = ArrayList<Long>()
            var n = first
            while (channelHz(n) <= hi) {
                val hz = channelHz(n)
                if (abs(hz - centerHz) > DC_GUARD_HZ) out += hz
                n++
            }
            return out.toLongArray()
        }
    }
}
