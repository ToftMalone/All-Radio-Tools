package com.allnetworktools.data.sdr

import com.allnetworktools.R
import com.allnetworktools.util.S

/**
 * Soft QPSK symbols in, 1020-byte frames out. The carrier phase is only known up to a quarter turn, and an I/Q swap is
 * possible too, so eight combinations are decoded side by side until one of them finds the frame marker twice one frame
 * apart; from then on only that one runs. If its marker disappears the search starts again.
 *
 * With [m2x] (Meteor-M2-2 and later, as SatDump's M2-x pipeline): the bits are NRZ-M coded after the convolutional code,
 * so they are decoded back after Viterbi, which also makes a half turn harmless (it inverts every bit). Offset QPSK
 * brings another ambiguity instead: which Q goes with which I, so the eight branches are two phases, the I/Q swap, and Q
 * paired with the next I or not.
 */
class LrptLink(private val m2x: Boolean = false, private val onFrame: (ByteArray) -> Unit) {
    private inner class Branch(val swap: Boolean, val rotation: Int, val shift: Boolean) {
        val sync = FrameSync { f -> if (active === this) onFrame(f) }
        private var lastBit = 0
        val viterbi = ViterbiK7 { b -> if (m2x) { sync.push(b xor lastBit); lastBit = b } else sync.push(b) }
        private var prevQ = 0
        private var primed = false

        fun symbol(i0: Int, q0: Int) {
            var i = i0
            var q = q0
            if (shift) {
                val pq = prevQ; prevQ = q0
                if (!primed) { primed = true; return }
                i = pq; q = i0
            }
            var a = if (swap) q else i
            var b = if (swap) i else q
            repeat(rotation) { val na = -b; b = a; a = na } // a quarter turn: (a, b) → (−b, a)
            viterbi.push(a, b)
        }

        fun reset() { viterbi.reset(); sync.reset(); lastBit = 0; prevQ = 0; primed = false }
        val label: String get() = "${rotation * 90}°" + (if (swap) S(R.string.i_q_swapped) else "") + if (shift) " · Q+1" else ""
    }

    private val branches = if (m2x) (0 until 8).map { Branch((it shr 1) and 1 == 1, it and 1, it >= 4) }
        else (0 until 8).map { Branch(it >= 4, it % 4, false) }
    @Volatile private var active: Branch? = null

    val locked: Boolean get() = active != null
    val hypothesis: String? get() = active?.label

    /** Times the frame marker was lost after having been found. */
    var losses = 0
        private set

    fun symbol(i: Int, q: Int) {
        val a = active
        if (a != null) {
            a.symbol(i, q)
            if (!a.sync.locked) { active = null; losses++; branches.forEach { it.reset() } }
            return
        }
        for (b in branches) b.symbol(i, q)
        for (b in branches) if (b.sync.locked) {
            active = b
            for (o in branches) if (o !== b) o.reset()
            break
        }
    }

    fun reset() {
        active = null
        branches.forEach { it.reset() }
    }
}

/** Counters of every stage of the chain, for the screen. */
data class LrptStatus(
    val carrier: Boolean = false,
    /** Strongest fourth-power line and symbol-rate line at the last search, against the noise: what the search sees. */
    val lineRatio: Double = 0.0,
    val symbolLineRatio: Double = 0.0,
    val snrDb: Double = 0.0,
    val cfoHz: Double = 0.0,
    val locked: Boolean = false,
    val hypothesis: String? = null,
    val frames: Int = 0,
    val rsOk: Int = 0,
    val rsFail: Int = 0,
    val corrected: Long = 0,
    val basis: String? = null,
    val zoneStart: Int = 10,
    val packets: Int = 0,
    val segmentsOk: Int = 0,
    val segmentsBad: Int = 0,
    val layout: Int? = null,
    val apids: Map<Int, Int> = emptyMap(),
    val strips: Map<Int, Int> = emptyMap(),
)

/**
 * HackRF bytes at 2.304 MS/s (the channel fs/4 above the tuned frequency) to image strips: decimation to 288 kS/s, QPSK
 * or OQPSK demodulation, Viterbi (then NRZ-M for the M2-x satellites), frame sync, Reed-Solomon, packets, JPEG blocks.
 */
class LrptReceiver(
    /** Meteor-M2-2 and later (all that transmit now): offset QPSK and NRZ-M; false for the original Meteor-M2. */
    val m2x: Boolean = true,
    private val onStrip: (apid: Int, row: Int, strip: ByteArray) -> Unit = { _, _, _ -> },
) {
    private val decimator = Decimator(8, 0.036, taps = 96)
    private val aRe = FloatArray(131072 / 2 / 8 + 16)
    private val aIm = FloatArray(aRe.size)

    private val frameDecoder = FrameDecoder()
    private val segments = SegmentDecoder()
    private val images = HashMap<Int, ChannelImage>()
    private var zoneStart = 10
    private var assembler = newAssembler()
    private var score10 = 0
    private var score8 = 0
    private var frames = 0
    private var rsOk = 0
    private var rsFail = 0
    private var packets = 0
    private val apidCounts = HashMap<Int, Int>()

    private val link = LrptLink(m2x) { onFrame(it) }
    private val qpsk = QpskDemodulator(offsetQ = m2x) { i, q -> link.symbol(i, q) }
    private var wasTracking = false
    private var lastSync = 0L

    /** A real Meteor-M signal gives frame sync within seconds; without it the lock was a false one. */
    private val syncTimeoutSymbols = 72_000L * 15

    private fun newAssembler() = PacketAssembler(zoneStart) { onPacket(it) }

    fun feed(buf: ByteArray, len: Int) {
        val n = decimator.process(buf, len, aRe, aIm, 0)
        qpsk.feed(aRe, aIm, n)
        if (qpsk.tracking) {
            if (!wasTracking || link.locked || qpsk.symbols < lastSync) lastSync = qpsk.symbols
            if (qpsk.symbols - lastSync > syncTimeoutSymbols) { qpsk.restart(); link.reset() }
        }
        if (wasTracking && !qpsk.tracking) link.reset()
        wasTracking = qpsk.tracking
    }

    private fun onFrame(frame: ByteArray) {
        frames++
        val r = frameDecoder.decode(frame)
        if (r == null) { rsFail++; return }
        rsOk++
        // The packet zone starts at byte 10 when the frame has an insert zone, 8 otherwise: see which one looks right.
        if (PacketAssembler.looksRight(r.data, 10)) score10++
        if (PacketAssembler.looksRight(r.data, 8)) score8++
        if (zoneStart == 10 && score8 >= 4 && score8 > 2 * score10 + 2) { zoneStart = 8; assembler = newAssembler() }
        assembler.frame(r.data)
    }

    private fun onPacket(p: LrptPacket) {
        packets++
        apidCounts.merge(p.apid, 1, Int::plus)
        if (p.apid !in 64..69) return
        val seg = segments.decode(p) ?: return
        val img = images.getOrPut(p.apid) { ChannelImage(p.apid) }
        val row = img.add(seg) ?: return
        onStrip(p.apid, row, img.strips[row]!!.copyOf())
    }

    fun status() = LrptStatus(
        carrier = qpsk.confirmed, lineRatio = qpsk.lineRatio, symbolLineRatio = qpsk.symbolLineRatio, snrDb = qpsk.snrDb, cfoHz = qpsk.cfoHz, locked = link.locked, hypothesis = link.hypothesis,
        frames = frames, rsOk = rsOk, rsFail = rsFail, corrected = frameDecoder.corrected,
        basis = frameDecoder.dualBasis?.let { if (it) S(R.string.dual_basis) else S(R.string.conventional_basis) },
        zoneStart = zoneStart, packets = packets, segmentsOk = segments.good, segmentsBad = segments.bad, layout = segments.layout,
        apids = apidCounts.toMap(), strips = images.mapValues { (_, v) -> v.strips.count { it != null } },
    )
}
