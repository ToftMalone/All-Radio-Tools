package com.allnetworktools.data.sdr

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * DECT cordless phones (EN 300 175): what every base station broadcasts in the clear. Carriers 1.728 MHz apart,
 * 1.152 Mbit/s GFSK, 10 ms frames of 24 slots; a slot opens with 16 preamble bits and the sync word (0xE98A from a base,
 * 0x1675 from a handset), then the 64-bit A-field: header, 40 tail bits (identity, system information, paging…) and a
 * CRC. The B-field (voice, encrypted once a call has keys) is never touched. Ported from OnAir (core/src/dect, GPL-3.0).
 */
object Dect {
    const val BIT_RATE = 1_152_000
    const val SYNC_RFP = 0xAAAAE98AL
    const val SYNC_PP = 0x55551675L
    const val FRAME_S = 0.010
    const val SLOTS = 24

    /** A regional band: carrier 0's centre, which way the carriers go, how many. */
    enum class Band(val firstHz: Long, val dir: Int, val count: Int) {
        Europe(1_897_344_000L, -1, 10),
        Us(1_881_792_000L + 23 * 1_728_000L, 1, 5),
        ;
        fun carrierHz(n: Int) = firstHz + dir * 1_728_000L * n
    }

    /** The A-field CRC: x^16 + x^10 + x^8 + x^7 + x^3 + 1 over the 48 bits, register from zero. */
    fun crc(v48: Long): Int {
        var reg = 0
        for (i in 47 downTo 0) {
            val fb = ((reg shr 15) and 1) xor ((v48 shr i).toInt() and 1)
            reg = (reg shl 1) and 0xFFFF
            if (fb != 0) reg = reg xor 0x0589
        }
        return reg
    }

    /** Tail types: identity (Nt), system information (Qt), MAC control (Mt), paging (Pt), data (Ct). */
    const val TA_NT = 2
    const val TA_NT_CL = 3
    const val TA_QT = 4

    class AField(val raw: Long) {
        val ta get() = ((raw ushr 61) and 7).toInt()
        val tail get() = (raw ushr 16) and 0xFFFFFFFFFFL
        val crcOk: Boolean get() { val c = crc(raw ushr 16); val got = (raw and 0xFFFF).toInt(); return c == got || (c.inv() and 0xFFFF) == got }
    }

    fun makeAField(ta: Int, ba: Int, tail40: Long): Long {
        val head = (((ta and 7) shl 5) or ((ba and 7) shl 1)).toLong()
        val v48 = (head shl 40) or (tail40 and 0xFFFFFFFFFFL)
        return (v48 shl 16) or crc(v48).toLong()
    }

    /** RFPI, the base's 40-bit identity: access rights class; for class A the manufacturer (EMC) and fixed part numbers. */
    data class Rfpi(val value: Long) {
        val arc get() = ((value shr 35) and 7).toInt()
        val emc get() = if (arc == 0) ((value shr 19) and 0xFFFF).toInt() else null
        val fpn get() = if (arc == 0) ((value shr 2) and 0x1FFFF).toInt() else null
        val hex get() = "%010X".format(value and 0xFFFFFFFFFFL)
        val arcLetter get() = ('A' + arc)
    }

    fun rfpiA(emc: Int, fpn: Int) = Rfpi(((emc.toLong() and 0xFFFF) shl 19) or ((fpn.toLong() and 0x1FFFF) shl 2))
}

/** A burst whose A-field CRC passed: from a base or a handset, its tail type and bits, its level and time. */
class DectBurst(val fromBase: Boolean, val a: Dect.AField, val rssiDb: Double, val sec: Double)

/**
 * One carrier at 4.608 MS/s (4 samples a bit), centred: an FM discriminator, the bit sum, and a burst detector on
 * the power over 32 bits. The S-field ends about 22 bits after the power rises, so every alignment within ±20 bits is
 * tried with both sync patterns, best eye first; the first whose A-field CRC passes, with its sync matching too, wins.
 */
class DectDemodulator(private val onBurst: (DectBurst) -> Unit) {
    private val fb = FloatArray(RING)
    private val pb = FloatArray(RING)
    private var g = -1L
    private var pr = 0f
    private var pi = 0f
    private val box = FloatArray(SPS)
    private var boxPos = 0
    private var boxSum = 0f
    private val pwBuf = FloatArray(MEAN)
    private var pwPos = 0
    private var pwSum = 0.0
    private var floor = 1e30
    private var burstOn = false
    private val cands = ArrayDeque<Long>()
    private var skipUntil = 0L

    var bad = 0
        private set

    /** Complex samples at 4.608 MS/s centred on the carrier. */
    fun feed(re: FloatArray, im: FloatArray, n: Int) {
        for (i in 0 until n) {
            step(re[i], im[i])
            // Well before the ring wraps over a waiting burst.
            if (i and 4095 == 4095) process()
        }
        process()
    }

    private fun step(yr: Float, yi: Float) {
        // y · conj(prev): its angle is the frequency; im/|z| (the sine) is monotonic over the ±0.4 rad a bit swings.
        val zr = yr * pr + yi * pi
        val zi = yi * pr - yr * pi
        pr = yr; pi = yi
        val d = zi / (sqrt(zr * zr + zi * zi) + 1e-12f)
        boxSum += d - box[boxPos]; box[boxPos] = d; boxPos = (boxPos + 1) % SPS
        val pw = yr * yr + yi * yi
        pwSum += pw - pwBuf[pwPos]; pwBuf[pwPos] = pw; pwPos = (pwPos + 1) % MEAN
        val pAvg = pwSum / MEAN
        g++
        val k = (g and MASK).toInt()
        fb[k] = boxSum / SPS
        pb[k] = pw
        if (g >= MEAN) {
            if (pAvg < floor) floor = pAvg else floor += (pAvg - floor) * 2e-7
            if (!burstOn && pAvg > 6.0 * floor) { burstOn = true; cands.addLast(g + 22 * SPS) }
            else if (burstOn && pAvg < 3.0 * floor) burstOn = false
        }
    }

    private fun at(k: Long) = fb[(k and MASK).toInt()]

    private fun process() {
        while (cands.isNotEmpty() && cands.first() + NEED < g) {
            val c = cands.removeFirst()
            if (c < skipUntil) continue
            if (!decode(c, true)) decode(c, false)
        }
    }

    private fun decode(c0: Long, fromBase: Boolean): Boolean {
        val known = if (fromBase) Dect.SYNC_RFP else Dect.SYNC_PP
        class Ph(val c: Long, val eye: Double, val m1: Double, val m0: Double, val errs: Int)
        val ph = ArrayList<Ph>()
        for (d in -SEARCH..SEARCH) {
            val c = c0 + d
            if (c - (SYNC_BITS - 1) * SPS < g - RING + 8 || c + NEED - 8 > g) continue
            var m1 = 0.0; var m0 = 0.0; var n1 = 0; var n0 = 0
            for (k in 0 until SYNC_BITS) {
                val v = at(c - (SYNC_BITS - 1 - k) * SPS)
                if ((known shr (SYNC_BITS - 1 - k)) and 1L == 1L) { m1 += v; n1++ } else { m0 += v; n0++ }
            }
            m1 /= n1; m0 /= n0
            var errs = 0
            for (k in 0 until SYNC_BITS) {
                val bit = if (at(c - (SYNC_BITS - 1 - k) * SPS) > (m1 + m0) / 2) 1L else 0L
                if (bit != (known shr (SYNC_BITS - 1 - k)) and 1L) errs++
            }
            ph += Ph(c, m1 - m0, m1, m0, errs)
        }
        if (ph.isEmpty()) return false
        ph.sortByDescending { it.eye }
        val bestErrs = ph.minOf { it.errs }
        for (p in ph) {
            if (p.errs > 3) continue
            val thr = (p.m1 + p.m0) / 2
            var raw = 0L
            for (j in 0 until 64) raw = (raw shl 1) or (if (at(p.c + (j + 1) * SPS) > thr) 1L else 0L)
            val a = Dect.AField(raw)
            if (!a.crcOk) continue
            val end = p.c + 65 * SPS
            skipUntil = end
            var pw = 0.0
            var np = 0
            for (k in p.c - SYNC_BITS * SPS until end) { pw += pb[(k and MASK).toInt()]; np++ }
            onBurst(DectBurst(fromBase, a, 10 * log10(pw / maxOf(1, np) + 1e-20), (p.c - SYNC_BITS * SPS).toDouble() / RATE))
            return true
        }
        if (bestErrs <= 1) { bad++; return true }
        return false
    }

    companion object {
        const val RATE = 4_608_000.0
        private const val SPS = 4
        private const val SYNC_BITS = 32
        private const val MEAN = SYNC_BITS * SPS
        private const val SEARCH = 80
        private const val NEED = (64 + 2) * SPS + 8 + SEARCH
        private const val RING = 1 shl 15
        private const val MASK = (RING - 1).toLong()
    }
}

/** A base station heard: its identity, the carriers and slots it uses, its level. */
data class DectBase(
    val rfpi: Dect.Rfpi,
    val carriers: Set<Int>,
    val slots: Int,
    val bursts: Int,
    val qt: Int,
    val rssiDb: Double,
    val lastSeenMs: Long,
)

/**
 * Bursts to base stations. Nt tails carry the RFPI; the other tails are given to the base whose identity bursts
 * fall at the same place in the 10 ms frame. Handsets are only counted.
 */
class DectTracker {
    private class Rec(val rfpi: Dect.Rfpi) {
        val carriers = HashSet<Int>()
        var slots = 0
        var bursts = 0
        var qt = 0
        var rssi = -99.0
        var last = 0L
        var phase = 0.0
        var first = 0.0
    }
    private val recs = LinkedHashMap<Long, Rec>()
    var handsetBursts = 0
        private set

    fun update(b: DectBurst, carrier: Int, nowMs: Long) {
        if (!b.fromBase) { handsetBursts++; return }
        val ph = b.sec % Dect.FRAME_S
        val r: Rec = if (b.a.ta == Dect.TA_NT || b.a.ta == Dect.TA_NT_CL) {
            val id = Dect.Rfpi(b.a.tail)
            recs.getOrPut(id.value) { Rec(id).also { it.first = b.sec } }.also { it.phase = ph }
        } else {
            recs.values.firstOrNull { val d = abs(ph - it.phase); minOf(d, Dect.FRAME_S - d) < 150e-6 } ?: return
        }
        r.bursts++
        if (b.a.ta == Dect.TA_QT) r.qt++
        r.carriers += carrier
        r.rssi = b.rssiDb
        r.last = nowMs
        val rel = (((b.sec - r.first).coerceAtLeast(0.0) + Dect.FRAME_S * 0.5 / Dect.SLOTS) % Dect.FRAME_S) / (Dect.FRAME_S / Dect.SLOTS)
        r.slots = r.slots or (1 shl (rel.toInt() % Dect.SLOTS))
    }

    fun prune(nowMs: Long) { recs.values.removeAll { nowMs - it.last > 600_000 } }

    fun bases() = recs.values.map { DectBase(it.rfpi, it.carriers.toSet(), it.slots, it.bursts, it.qt, it.rssi, it.last) }
}

/** HackRF bytes at 4.608 MS/s tuned fs/4 below the carrier: mixed to 0 Hz, a 3-tap box against the next carriers. */
class DectReceiver(onBurst: (DectBurst) -> Unit) {
    private var mix = 0
    private val h1 = FloatArray(2)
    private val h2 = FloatArray(2)
    private val re = FloatArray(65536)
    private val im = FloatArray(65536)
    val demod = DectDemodulator(onBurst)
    /** Samples to drop after a retune, while the radio settles. */
    @Volatile var skip = 0

    fun feed(buf: ByteArray, len: Int) {
        var n = 0
        var i = 0
        while (i + 1 < len) {
            val a = buf[i].toFloat()
            val b = buf[i + 1].toFloat()
            i += 2
            if (skip > 0) { skip--; continue }
            // × e^{-jπn/2}: 1, -j, -1, +j.
            val mr: Float
            val mi: Float
            when (mix) { 0 -> { mr = a; mi = b }; 1 -> { mr = b; mi = -a }; 2 -> { mr = -a; mi = -b }; else -> { mr = -b; mi = a } }
            mix = (mix + 1) and 3
            re[n] = (mr + h1[0] + h1[1]) / 384f
            im[n] = (mi + h2[0] + h2[1]) / 384f
            h1[1] = h1[0]; h1[0] = mr
            h2[1] = h2[0]; h2[0] = mi
            n++
            if (n == re.size) { demod.feed(re, im, n); n = 0 }
        }
        if (n > 0) demod.feed(re, im, n)
    }

    companion object {
        const val SAMPLE_RATE = 4_608_000
    }
}
