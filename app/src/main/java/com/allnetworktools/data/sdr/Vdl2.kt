package com.allnetworktools.data.sdr

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * VDL Mode 2, the digital datalink of airliners on 136.725 to 136.975 MHz: D8PSK at 10.5 ksymbol/s, bursts with a
 * training sequence, a (25,20)-coded length header, Reed-Solomon (255,249) blocks interleaved column by column, an
 * x^15 + x + 1 scrambler, then HDLC frames (AVLC) carrying ACARS, XID link management and ATN (X.25) packets. Ported
 * from OnAir (core/src/vdl2 and rust/onair_vdl2, GPL-3.0; facts from ICAO Annex 10 Vol III and Doc 9776).
 */
object Vdl2 {
    const val SYMBOL_RATE = 10_500.0
    const val PREAMBLE = 16
    const val HEADER_BITS = 25
    const val RS_N = 255
    const val RS_K = 249
    /** Training symbols' phases relative to the first, in units of π/4. */
    val preamblePhase = intArrayOf(0, 3, -3, 1, 1, 2, 0, 4, -3, 4, -2, 3, 1, -2, -3, 0)
    /** Phase step (π/4 units) → its three bits, first sent most significant. */
    val grayBits = intArrayOf(0, 1, 3, 2, 6, 7, 5, 4)
    fun stepForBits(b: Int) = grayBits.indexOf(b and 7)

    /** Europe's channels; 136.975 MHz is the common signalling channel. */
    val EuropeHz = listOf(136_725_000L, 136_775_000L, 136_825_000L, 136_875_000L, 136_900_000L, 136_975_000L)
    const val CENTRE_HZ = 136_850_000L

    /** XORs the scrambler sequence over [bits] (one pass scrambles, the same pass descrambles). */
    fun scramble(bits: IntArray, from: Int = 0, to: Int = bits.size) {
        var s = 0x6959
        for (i in from until to) {
            val x = (s xor (s shr 14)) and 1
            s = (s shr 1) or (x shl 14)
            bits[i] = bits[i] xor x
        }
    }

    private val rows = arrayOf("0000000011111111111110000", "0011111100001111111101000", "1100011100110000111100100", "1101101101010011001100010", "0110100111100101010100001").map { it.toInt(2) }
    private val synd = arrayOf(
        "0000000000000000000000000", "0000000000000000000000001", "0000000000000000000000010", "0100000000000000000000100",
        "0000000000000000000000100", "0100000000000000000000010", "1000000000000000000000000", "0100000000000000000000000",
        "0000000000000000000001000", "0010000000000000000000000", "0001000000000000000000000", "0000100000000000000000000",
        "0000010000000000000000000", "1000100000000000000000000", "0000001000000000000000000", "0000000100000000000000000",
        "0000000000000000000010000", "0000000010000000000000000", "0100000000100000000000000", "0000000001000000000000000",
        "0100000001000000000000000", "0000000000100000000000000", "0000000000010000000000000", "1000000010000000000000000",
        "0000000000001000000000000", "0000000000000100000000000", "0000000000000010000000000", "0000000000000001000000000",
        "0000000000000000100000000", "0000000000000000010000000", "0000000000000000001000000", "0000000000000000000100000",
    ).map { it.toInt(2) }

    fun rev(v0: Int, n: Int): Int { var v = v0; var r = 0; repeat(n) { r = (r shl 1) or (v and 1); v = v ushr 1 }; return r }
    private fun par(v: Int) = Integer.bitCount(v) and 1

    fun headerWord(lenBits: Int): Int {
        var w = rev(lenBits and 0x1FFFF, 17) shl 5
        rows.forEachIndexed { i, r -> w = w or (par(w and r) shl (4 - i)) }
        return w
    }

    /** The data length in bits, or -1 when the header is not one. */
    fun headerDecode(word0: Int): Int {
        var word = word0 and ((1 shl 22) - 1)
        var s = 0
        rows.forEachIndexed { i, r -> s = s or (par(word and r) shl (4 - i)) }
        word = word xor synd[s]
        if (word ushr 22 != 0) return -1
        val len = rev((word shr 5) and 0x1FFFF, 17)
        return if (len != 0 && len <= (if (s != 0) 0x1FFF else 0x3FFF)) len else -1
    }

    fun lastFec(n: Int) = when { n < 3 -> 0; n <= 30 -> 2; n <= 67 -> 4; else -> 6 }

    class Layout(val dataOct: Int, val fecOct: Int, val blocks: Int, val lastLen: Int, val lastFec: Int)

    fun layout(lenBits: Int): Layout? {
        val dataOct = (lenBits + 7) / 8
        var blocks = dataOct / RS_K
        var fec = blocks * (RS_N - RS_K)
        var lastLen = dataOct % RS_K
        var lf = 6
        if (lastLen != 0) { blocks++; lf = lastFec(lastLen); fec += lf } else lastLen = RS_K
        return if (dataOct > 0 && fec > 0) Layout(dataOct, fec, blocks, lastLen, lf) else null
    }

    // ---- Reed-Solomon (255,249), field 0x187, first root α^120

    private val exp = IntArray(512)
    private val log = IntArray(256)
    init {
        var x = 1
        for (i in 0 until 255) { exp[i] = x; log[x] = i; x = x shl 1; if (x and 0x100 != 0) x = x xor 0x187 }
        for (i in 255 until 512) exp[i] = exp[i - 255]
    }
    private fun mul(a: Int, b: Int) = if (a != 0 && b != 0) exp[log[a] + log[b]] else 0
    private fun div(a: Int, b: Int) = if (a != 0) exp[(log[a] + 255 - log[b]) % 255] else 0
    private fun pw(e0: Int): Int { var e = e0 % 255; if (e < 0) e += 255; return exp[e] }
    private const val FCR = 120
    private const val ROOTS = 6
    private val gen: IntArray = run {
        var p = IntArray(ROOTS + 1).also { it[0] = 1 }
        for (deg in 0 until ROOTS) {
            val r = pw(FCR + deg)
            val q = IntArray(ROOTS + 1)
            for (k in 0..deg) { q[k] = q[k] xor p[k]; q[k + 1] = q[k + 1] xor mul(p[k], r) }
            p = q
        }
        p
    }

    fun rsEncode(data: IntArray): IntArray {
        val r = IntArray(ROOTS)
        for (d in data.take(RS_K)) {
            val fb = d xor r[0]
            for (k in 0 until ROOTS - 1) r[k] = r[k + 1] xor mul(fb, gen[k + 1])
            r[ROOTS - 1] = mul(fb, gen[ROOTS])
        }
        return r
    }

    /** Corrects [cw] in place with [nParity] parity octets present (the rest erased): octets fixed, or -1. */
    fun rsDecode(cw: IntArray, nParity0: Int): Int {
        val nParity = nParity0.coerceIn(0, ROOTS)
        val n = RS_N
        val s = IntArray(ROOTS)
        var zero = true
        for (i in 0 until ROOTS) {
            val a = pw(FCR + i)
            var v = 0
            for (c in cw) v = mul(v, a) xor c
            s[i] = v
            if (v != 0) zero = false
        }
        val nEr = ROOTS - nParity
        if (zero && nEr == 0) return 0
        var lam = IntArray(ROOTS + 1).also { it[0] = 1 }
        for (e in 0 until nEr) {
            val x = pw(n - 1 - (RS_K + nParity + e))
            for (k in ROOTS downTo 1) lam[k] = lam[k] xor mul(x, lam[k - 1])
        }
        var b = lam.copyOf()
        var l = nEr
        for (r in nEr + 1..ROOTS) {
            var d = 0
            for (i in 0 until r) d = d xor mul(lam[i], s[r - 1 - i])
            val xb = IntArray(ROOTS + 1)
            for (k in ROOTS downTo 1) xb[k] = b[k - 1]
            if (d == 0) { b = xb; continue }
            val t = IntArray(ROOTS + 1) { lam[it] xor mul(d, xb[it]) }
            if (2 * l < r + nEr) { l = r + nEr - l; b = IntArray(ROOTS + 1) { div(lam[it], d) } } else b = xb
            lam = t
        }
        var deg = 0
        for (k in lam.indices) if (lam[k] != 0) deg = k
        if (deg == 0) return if (zero) 0 else -1
        val pos = IntArray(ROOTS)
        var nr = 0
        for (j in 0 until RS_N) {
            val e = n - 1 - j
            var v = 0
            for (k in 0..deg) v = v xor mul(lam[k], pw(-e * k))
            if (v == 0) { if (nr == ROOTS) return -1; pos[nr++] = j }
        }
        if (nr != deg) return -1
        val om = IntArray(ROOTS)
        for (i in 0 until ROOTS) for (k in 0..minOf(i, deg)) om[i] = om[i] xor mul(lam[k], s[i - k])
        var fixed = 0
        for (q in 0 until nr) {
            val j = pos[q]
            val e = n - 1 - j
            val xinv = pw(-e)
            var o = 0
            var xp = 1
            for (omi in om) { o = o xor mul(omi, xp); xp = mul(xp, xinv) }
            var dl = 0
            var k = 1
            while (k <= deg) { dl = dl xor mul(lam[k], pw(-e * (k - 1))); k += 2 }
            if (dl == 0) return -1
            val v = mul(div(o, dl), pw(e * (1 - FCR)))
            if (v != 0 && j < RS_K + nParity) fixed++
            cw[j] = cw[j] xor v
        }
        if (2 * fixed + nEr > ROOTS) return -1
        return fixed
    }

    // ---- burst body

    /** The bits after the training sequence for [dataBits]: header, data and FEC octets (LSB first), scrambled. */
    fun burstBits(dataBits: IntArray): IntArray {
        val l = layout(dataBits.size) ?: return IntArray(0)
        val data = IntArray(l.dataOct)
        for (i in dataBits.indices) data[i / 8] = data[i / 8] or ((dataBits[i] and 1) shl (i % 8))
        val rows = Array(l.blocks) { IntArray(RS_N) }
        for (i in 0 until l.dataOct) rows[i / RS_K][i % RS_K] = data[i]
        for (r in rows) rsEncode(r).copyInto(r, RS_K)
        val out = ArrayList<Int>()
        val hw = headerWord(dataBits.size)
        for (b in 24 downTo 0) out += (hw shr b) and 1
        fun octet(v: Int) { for (b in 0 until 8) out += (v shr b) and 1 }
        for (c in 0 until RS_K) for (r in 0 until l.blocks) { if (r == l.blocks - 1 && c >= l.lastLen) continue; octet(rows[r][c]) }
        val fecRows = if (l.lastFec != 0) l.blocks else l.blocks - 1
        for (c in 0 until RS_N - RS_K) for (r in 0 until fecRows) { if (r == l.blocks - 1 && c >= l.lastFec) continue; octet(rows[r][RS_K + c]) }
        return out.toIntArray().also { scramble(it) }
    }

    /** Descrambled bits after the header → the data bits, every block corrected; null when one cannot be. */
    fun burstData(bits: IntArray, from: Int, l: Layout, lenBits: Int): IntArray? {
        if (l.blocks > 70) return null
        fun octet(at: Int): Int { var v = 0; for (b in 0 until 8) v = v or ((bits.getOrElse(from + at + b) { 0 } and 1) shl b); return v }
        val rows = Array(l.blocks) { IntArray(RS_N) }
        var k = 0
        for (c in 0 until RS_K) for (r in 0 until l.blocks) { if (r == l.blocks - 1 && c >= l.lastLen) continue; rows[r][c] = octet(8 * k++) }
        val fecRows = if (l.lastFec != 0) l.blocks else l.blocks - 1
        for (c in 0 until RS_N - RS_K) for (r in 0 until fecRows) { if (r == l.blocks - 1 && c >= l.lastFec) continue; rows[r][RS_K + c] = octet(8 * k++) }
        for (r in 0 until l.blocks) {
            val np = if (r == l.blocks - 1) l.lastFec else 6
            if (np == 0) continue
            if (rsDecode(rows[r], np) < 0) return null
        }
        val out = IntArray(lenBits)
        var n = 0
        for (r in 0 until l.blocks) {
            val cnt = if (r == l.blocks - 1) l.lastLen else RS_K
            for (c in 0 until cnt) for (b in 0 until 8) if (n < lenBits) out[n++] = (rows[r][c] shr b) and 1
        }
        return out
    }

    // ---- HDLC and AVLC

    private fun crcReg(p: IntArray, n: Int): Int {
        var c = 0xFFFF
        for (i in 0 until n) { c = c xor p[i]; repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0x8408 else c ushr 1 } }
        return c
    }
    fun fcs(p: IntArray) = crcReg(p, p.size).inv() and 0xFFFF
    fun fcsOk(p: IntArray) = p.size >= 2 && crcReg(p, p.size) == 0xF0B8

    fun hdlcBits(frames: List<IntArray>): IntArray {
        val out = ArrayList<Int>()
        fun flag() { for (b in 0 until 8) out += (0x7E shr b) and 1 }
        flag()
        for (f in frames) {
            var ones = 0
            for (v in f) for (b in 0 until 8) {
                val bit = (v shr b) and 1
                out += bit
                if (bit == 1) { if (++ones == 5) { out += 0; ones = 0 } } else ones = 0
            }
            flag()
        }
        return out.toIntArray()
    }

    /** The octets between flags: whole octets only, at least two, none aborted by seven ones. */
    fun hdlcFrames(bits: IntArray): List<IntArray> {
        val res = ArrayList<IntArray>()
        val cur = ArrayList<Int>()
        var ones = 0
        var sync = false
        var abort = false
        for (bit in bits) {
            if (bit != 0) {
                ones++
                if (ones > 6) { abort = true; cur.clear() } else if (!abort) cur += 1
                continue
            }
            if (ones == 5) { ones = 0; continue }
            if (ones == 6) {
                repeat(minOf(7, cur.size)) { cur.removeAt(cur.size - 1) }
                if (sync && !abort && cur.size >= 16 && cur.size % 8 == 0) {
                    res += IntArray(cur.size / 8) { o -> var v = 0; for (b in 0 until 8) v = v or (cur[8 * o + b] shl b); v }
                }
                cur.clear(); abort = false; sync = true; ones = 0
                continue
            }
            if (!abort) cur += 0
            ones = 0
        }
        return res
    }

    /** A 24-bit address with its type (1 aircraft, 4 and 5 ground station, 7 all) and status bit. */
    data class Addr(val addr: Int, val type: Int, val status: Boolean = false)

    fun putAddr(a: Addr, last: Boolean): IntArray {
        val v = ((if (a.status) 1 else 0) shl 27) or ((a.type and 7) shl 24) or (a.addr and 0xFFFFFF)
        val t = rev(v, 28)
        return IntArray(4) { i -> (((t shr (7 * i)) and 0x7F) shl 1) or (if (last && i == 3) 1 else 0) }
    }

    fun getAddr(o: IntArray, at: Int): Addr {
        var t = 0
        for (i in 0 until 4) t = t or (((o[at + i] shr 1) and 0x7F) shl (7 * i))
        val v = rev(t, 28)
        return Addr(v and 0xFFFFFF, (v shr 24) and 7, (v shr 27) and 1 == 1)
    }

    fun frame(dst: Addr, src: Addr, control: Int, info: IntArray): IntArray {
        val f = putAddr(dst, false) + putAddr(src, true) + intArrayOf(control) + info
        val c = fcs(f)
        return f + intArrayOf(c and 0xFF, c ushr 8)
    }

    /** ACARS in an information field: the ARINC 618 block after 0xFF 0xFF, from SOH to the check sequence. */
    fun acarsInfo(reg: String, label: String, block: Char, text: String, mode: Char = '2'): IntArray {
        val f = Acars.frame(mode, reg, 0x15.toChar(), label, block, text)
        // Acars.frame starts with "+*" SYN SYN: VDL Mode 2 sends 0xFF 0xFF then SOH.
        return intArrayOf(0xFF, 0xFF) + IntArray(f.size - 4) { f[it + 4].toInt() and 0xFF }
    }

    fun xidGroundStation(lat: Double, lon: Double, airports: String): IntArray {
        val g = ArrayList<Int>()
        fun param(id: Int, v: List<Int>) { g += id; g += v.size; g += v }
        param(0x00, listOf('V'.code, '2'.code))
        param(0xC8, loc(lat, lon))
        param(0xC1, airports.map { it.code })
        return intArrayOf(0x82, 0x80, 0x00, 0x03, 0x01, 0x01, 0x00, 0xF0, g.size shr 8, g.size and 0xFF) + g.toIntArray()
    }

    private fun loc(lat: Double, lon: Double): List<Int> {
        val la = (lat * 10).roundToInt() and 0xFFF
        val lo = (lon * 10).roundToInt() and 0xFFF
        return listOf(la shr 4, ((la and 0xF) shl 4) or (lo shr 8), lo and 0xFF)
    }

    private fun signed12(v: Int) = if (v and 0x800 != 0) v - 0x1000 else v

    /** Reads a frame whose FCS was good ([f] without the FCS). */
    fun parse(f: IntArray, freqHz: Long, nowMs: Long, snrDb: Double): Vdl2Message? {
        if (f.size < 9) return null
        val d = getAddr(f, 0)
        val s = getAddr(f, 4)
        val c = f[8]
        val info = f.copyOfRange(9, f.size)
        var kind = Vdl2Message.Kind.Other
        var name: String
        var acars: AcarsMessage? = null
        var pos: Pair<Double, Double>? = null
        var altFt: Int? = null
        var airports: String? = null
        var dest: String? = null
        fun isAcars() = info.size > 3 && info[0] == 0xFF && info[1] == 0xFF && info[2] == 0x01
        fun readAcars() {
            kind = Vdl2Message.Kind.Acars
            var end = info.size
            if (end > 0 && info[end - 1] == 0x7F) end--
            if (end < 3 + 13 + 2) return
            val blk = ByteArray(end - 3) { info[3 + it].toByte() }
            if (Acars.crc(blk, 0, blk.size) != 0) return
            val body = ByteArray(blk.size - 2) { Acars.withParity(blk[it].toInt()).toByte() }
            acars = Acars.parse(body, body.size, freqHz / 1e6, nowMs)
        }
        when {
            c and 1 == 0 -> {
                name = "I"
                if (isAcars()) readAcars() else if (info.isNotEmpty()) kind = Vdl2Message.Kind.X25
            }
            c and 3 == 1 -> name = arrayOf("RR", "RNR", "REJ", "SREJ")[(c shr 2) and 3]
            else -> {
                val mf = (c shr 2) and 0x3B
                name = when (mf) { 0x00 -> "UI"; 0x03 -> "DM"; 0x10 -> "DISC"; 0x18 -> "UA"; 0x21 -> "FRMR"; 0x2B -> "XID"; 0x38 -> "TEST"; else -> "U" }
                if (mf == 0x2B) {
                    kind = Vdl2Message.Kind.Xid
                    // The VDL parameter group 0xF0 after the public group: locations, airports.
                    var o = 1
                    if (info.size >= 3 && info[0] == 0x82) while (o + 3 <= info.size) {
                        val gid = info[o]
                        val glen = (info[o + 1] shl 8) or info[o + 2]
                        o += 3
                        if (o + glen > info.size) break
                        var k = o
                        while (gid == 0xF0 && k + 2 <= o + glen) {
                            val id = info[k]
                            val len = info[k + 1]
                            if (k + 2 + len > o + glen) break
                            val v = info.copyOfRange(k + 2, k + 2 + len)
                            when (id) {
                                0x84, 0xC8 -> if (len >= 3) {
                                    pos = signed12((v[0] shl 4) or (v[1] shr 4)) / 10.0 to signed12(((v[1] and 0xF) shl 8) or v[2]) / 10.0
                                    if (id == 0x84 && len >= 4) altFt = v[3] * 1000
                                }
                                0xC1 -> airports = String(CharArray(len) { v[it].toChar() }).trim()
                                0x83 -> dest = String(CharArray(len) { v[it].toChar() }).trim()
                            }
                            k += 2 + len
                        }
                        o += glen
                    }
                } else if (mf == 0x00 && isAcars()) readAcars()
            }
        }
        return Vdl2Message(nowMs, freqHz, s, d, name, kind, acars, pos, altFt, airports, dest, snrDb)
    }
}

data class Vdl2Message(
    val timeMs: Long,
    val freqHz: Long,
    val src: Vdl2.Addr,
    val dst: Vdl2.Addr,
    /** The AVLC frame: I, UI, XID, RR… */
    val frame: String,
    val kind: Kind,
    val acars: AcarsMessage?,
    /** From XID: an aircraft's or a ground station's location, to 0.1°. */
    val position: Pair<Double, Double>?,
    val altFt: Int?,
    /** A ground station's airports (XID GSIF). */
    val airports: String?,
    /** An aircraft's destination (XID link establishment). */
    val destination: String?,
    val snrDb: Double,
) {
    enum class Kind { Acars, Xid, X25, Other }
    val fromAircraft get() = src.type == 1
    val fromGround get() = src.type == 4 || src.type == 5
    /** A ground station's own address is often its own, an aircraft's is its ICAO 24-bit address. */
    val srcHex get() = "%06X".format(src.addr)
    val dstHex get() = "%06X".format(dst.addr)
}

/**
 * One channel at 50 kS/s: the training sequence is hunted by its differential phases (which a frequency error only
 * turns), the burst is then timed on it, carrier and timing loops run over the symbols, and the header says how many
 * to read. The CRCs (Reed-Solomon, then each frame's FCS) decide.
 */
class Vdl2Channel(val freqHz: Long, private val fs: Double, private val onMessage: (Vdl2Message) -> Unit) {
    private val t = fs / Vdl2.SYMBOL_RATE
    private val x1r = FloatArray(RING)
    private val x1i = FloatArray(RING)
    private val x2r = FloatArray(RING)
    private val x2i = FloatArray(RING)
    private val h2 = lowpass(ceil(6.6 * fs / 6e3).toInt(), 15e3 / fs)
    private val h3 = lowpass(ceil(6.6 * fs / 4e3).toInt(), 7.5e3 / fs)
    private val gd2 = h2.size / 2
    private var n1 = 0L
    private var n2 = 0L
    private var floor = 0f
    private var blk = 0.0
    private var blkN = 0
    private var searchAt = 0L
    private var peakOn = false
    private var peakM = 0f
    private var peakAt = 0L
    private var peakEnd = 0L
    private var peakDr = 0.0
    private var peakDi = 0.0
    private var state = 0
    private var bAt = 0L
    private var bCfo = 0.0
    private var bNeed = 0L
    private var bSyms = 0

    var bursts = 0
        private set
    var good = 0
        private set

    fun push(re: Float, im: Float) {
        val i1 = (n1 and MASK).toInt()
        x1r[i1] = re; x1i[i1] = im
        n1++
        if (n1 >= h2.size) {
            val i = n1 - 1 - gd2
            var ar = 0f
            var ai = 0f
            val b0 = n1 - h2.size
            for (j in h2.indices) { val k = ((b0 + j) and MASK).toInt(); ar += x1r[k] * h2[j]; ai += x1i[k] * h2[j] }
            val i2 = (i and MASK).toInt()
            x2r[i2] = ar; x2i[i2] = ai
            n2 = i + 1
            blk += ar * ar + ai * ai
            if (++blkN == 512) {
                val m = (blk / 512).toFloat()
                floor = if (floor <= 0) m else if (m < floor) 0.7f * floor + 0.3f * m else floor * 1.003f
                blk = 0.0; blkN = 0
            }
        }
    }

    fun process() {
        search()
        if (state == 1 && n1 >= bNeed) {
            val o = demod(9)
            val len = o?.let { header(it.bits) } ?: -1
            val l = if (len > 0) Vdl2.layout(len) else null
            if (l == null) { fail(); return }
            bSyms = (Vdl2.HEADER_BITS + 8 * (l.dataOct + l.fecOct) + 2) / 3
            bNeed = bAt + ceil((Vdl2.PREAMBLE + bSyms + 4) * t).toLong() + h3.size + 8
            if (bNeed - bAt > RING - 8192) { fail(); return }
            state = 2
        }
        if (state == 2 && n1 >= bNeed) {
            val o = demod(bSyms) ?: run { fail(); return }
            val bits = o.bits.copyOf()
            Vdl2.scramble(bits)
            var w = 0
            for (i in 0 until Vdl2.HEADER_BITS) w = (w shl 1) or bits[i]
            val len = Vdl2.headerDecode(w)
            val l = if (len > 0) Vdl2.layout(len) else null
            if (l == null || bits.size < Vdl2.HEADER_BITS + 8 * (l.dataOct + l.fecOct)) { fail(); return }
            val data = Vdl2.burstData(bits, Vdl2.HEADER_BITS, l, len) ?: run { fail(); return }
            var any = false
            val now = System.currentTimeMillis()
            for (f in Vdl2.hdlcFrames(data)) {
                if (f.size < 11 || !Vdl2.fcsOk(f)) continue
                Vdl2.parse(f.copyOf(f.size - 2), freqHz, now, o.snrDb)?.let { any = true; onMessage(it) }
            }
            if (any) good++
            state = 0
            searchAt = bAt + floor((Vdl2.PREAMBLE + bSyms) * t).toLong()
            peakOn = false
        }
    }

    private fun header(bits0: IntArray): Int {
        val b = bits0.copyOf(Vdl2.HEADER_BITS)
        Vdl2.scramble(b)
        var w = 0
        for (i in 0 until Vdl2.HEADER_BITS) w = (w shl 1) or b[i]
        return Vdl2.headerDecode(w)
    }

    private fun fail() {
        state = 0
        searchAt = maxOf(searchAt, bAt + ceil((Vdl2.PREAMBLE + 4) * t).toLong())
        peakOn = false
    }

    private fun search() {
        val span = ceil(15 * t).toInt() + 1
        val sr = FloatArray(Vdl2.PREAMBLE)
        val si = FloatArray(Vdl2.PREAMBLE)
        while (state == 0 && searchAt + span < n2) {
            val at = searchAt
            if (at < 10 * t + h3.size) { searchAt++; continue }
            if (n2 - at > RING - 4096) { searchAt = n2 - 1024; peakOn = false; continue }
            var e = 0.0
            for (q in 0 until Vdl2.PREAMBLE) {
                val k = ((at + (q * t).roundToLong()) and MASK).toInt()
                sr[q] = x2r[k]; si[q] = x2i[k]
                e += sr[q] * sr[q] + si[q] * si[q]
            }
            e /= Vdl2.PREAMBLE
            var eh = 0.0
            var et = 0.0
            for (q in 0 until 4) { eh += sr[q] * sr[q] + si[q] * si[q]; val z = Vdl2.PREAMBLE - 1 - q; et += sr[z] * sr[z] + si[z] * si[z] }
            val ener = floor > 0 && e > 3.0 * floor && minOf(eh, et) > 4 * 0.3 * e
            var m = 0f
            var dr = 0.0
            var di = 0.0
            if (ener) {
                for (q in 0 until Vdl2.PREAMBLE - 1) {
                    val dp = (Vdl2.preamblePhase[q + 1] - Vdl2.preamblePhase[q]) * PI / 4
                    // s[q+1] · conj(s[q]) · e^{-j dp}
                    val pr = sr[q + 1] * sr[q] + si[q + 1] * si[q]
                    val pi = si[q + 1] * sr[q] - sr[q + 1] * si[q]
                    val c = cos(-dp); val s = sin(-dp)
                    dr += pr * c - pi * s; di += pr * s + pi * c
                }
                m = (hypot(dr, di) / (e * (Vdl2.PREAMBLE - 1))).toFloat()
            }
            if (ener && m >= DET) {
                if (!peakOn || m >= peakM) {
                    if (!peakOn) peakEnd = at + ceil((Vdl2.PREAMBLE + 2) * t).toLong()
                    peakOn = true; peakM = m; peakAt = at; peakDr = dr; peakDi = di
                }
            }
            if (peakOn && (at + 1 >= peakEnd || (m < peakM * DROP && m < DET))) {
                peakOn = false
                if (peakM >= DET) {
                    state = 1
                    bAt = peakAt
                    bCfo = atan2(peakDi, peakDr) * Vdl2.SYMBOL_RATE / (2 * PI)
                    bNeed = bAt + ceil((Vdl2.PREAMBLE + 10) * t).toLong() + h3.size + 8
                    bursts++
                }
            }
            searchAt++
        }
    }

    private class Demod(val bits: IntArray, val snrDb: Double)

    /** [nSym] symbols after the training sequence, carrier- and timing-tracked; null when the training does not fit. */
    private fun demod(nSym: Int): Demod? {
        val pre = 6
        val a = bAt - ceil(pre * t).toLong()
        val n = ceil((pre + Vdl2.PREAMBLE + nSym + 3) * t).toInt()
        val L = h3.size
        val m = L / 2
        val zr = FloatArray(n + L)
        val zi = FloatArray(n + L)
        val w = -2 * PI * bCfo / fs
        for (i in 0 until n + L) {
            val k = ((a + i - m) and MASK).toInt()
            val ph = w * (i - m)
            val c = cos(ph).toFloat(); val s = sin(ph).toFloat()
            zr[i] = x1r[k] * c - x1i[k] * s; zi[i] = x1r[k] * s + x1i[k] * c
        }
        val yr = FloatArray(n)
        val yi = FloatArray(n)
        for (i in 0 until n) { var ar = 0f; var ai = 0f; for (j in 0 until L) { ar += zr[i + j] * h3[j]; ai += zi[i + j] * h3[j] }; yr[i] = ar; yi[i] = ai }
        fun interp(tt: Double): Pair<Double, Double> {
            val i = floor(tt).toInt()
            val f = tt - i
            if (i < 1 || i + 2 >= n) return 0.0 to 0.0
            val c0 = -f * (f - 1) * (f - 2) / 6; val c1 = (f + 1) * (f - 1) * (f - 2) / 2
            val c2 = -(f + 1) * f * (f - 2) / 2; val c3 = (f + 1) * f * (f - 1) / 6
            return (yr[i - 1] * c0 + yr[i] * c1 + yr[i + 1] * c2 + yr[i + 2] * c3) to (yi[i - 1] * c0 + yi[i] * c1 + yi[i + 1] * c2 + yi[i + 2] * c3)
        }
        val t0 = (bAt - a).toDouble()
        val refR = DoubleArray(Vdl2.PREAMBLE) { cos(Vdl2.preamblePhase[it] * PI / 4) }
        val refI = DoubleArray(Vdl2.PREAMBLE) { -sin(Vdl2.preamblePhase[it] * PI / 4) }
        var bestT = t0
        var bestV = -1.0
        var d = -3.0
        while (d <= 3.0001) {
            var ar = 0.0; var ai = 0.0
            for (q in 0 until Vdl2.PREAMBLE) { val (r, i) = interp(t0 + d + q * t); ar += r * refR[q] - i * refI[q]; ai += r * refI[q] + i * refR[q] }
            val v = ar * ar + ai * ai
            if (v > bestV) { bestV = v; bestT = t0 + d }
            d += 0.125
        }
        val zR = DoubleArray(Vdl2.PREAMBLE)
        val zI = DoubleArray(Vdl2.PREAMBLE)
        var e = 0.0
        for (q in 0 until Vdl2.PREAMBLE) {
            val (r, i) = interp(bestT + q * t)
            zR[q] = r * refR[q] - i * refI[q]; zI[q] = r * refI[q] + i * refR[q]
            e += zR[q] * zR[q] + zI[q] * zI[q]
        }
        var dR = 0.0; var dI = 0.0
        for (q in 0 until Vdl2.PREAMBLE - 1) { dR += zR[q + 1] * zR[q] + zI[q + 1] * zI[q]; dI += zI[q + 1] * zR[q] - zR[q + 1] * zI[q] }
        val dr = atan2(dI, dR)
        var pR = 0.0; var pI = 0.0
        for (q in 0 until Vdl2.PREAMBLE) { val c = cos(-dr * q); val s = sin(-dr * q); pR += zR[q] * c - zI[q] * s; pI += zR[q] * s + zI[q] * c }
        if (hypot(dR, dI) < 0.5 * e) return null
        val amp = hypot(pR, pI) / Vdl2.PREAMBLE
        var th = atan2(pI, pR)
        var om = dr
        var tau = bestT
        val ka = 0.12; val kb = 0.004; val kt = 0.02
        var prevIdx = 0
        var sig = 0.0
        var err2 = 0.0
        var prev = interp(tau - t)
        val bits = IntArray(nSym * 3)
        var nb = 0
        for (q in 0 until Vdl2.PREAMBLE + nSym) {
            val tq = tau + q * t
            val sv = interp(tq)
            val c = cos(-th); val s = sin(-th)
            val rr = sv.first * c - sv.second * s
            val ri = sv.first * s + sv.second * c
            val idx = if (q < Vdl2.PREAMBLE) ((Vdl2.preamblePhase[q] % 8) + 8) % 8 else (((atan2(ri, rr) / (PI / 4)).roundToInt() % 8) + 8) % 8
            val decR = cos(idx * PI / 4); val decI = sin(idx * PI / 4)
            val pe = atan2(ri * decR - rr * decI, rr * decR + ri * decI)
            om += kb * pe
            th += om + ka * pe
            sig += rr * rr + ri * ri
            err2 += (rr - amp * decR).let { it * it } + (ri - amp * decI).let { it * it }
            val mid = interp(tq - t * 0.5)
            val ge = (prev.first - sv.first) * mid.first + (prev.second - sv.second) * mid.second
            if (q > 2) tau += (kt * ge / maxOf(1e-12, amp * amp)).coerceIn(-0.05, 0.05) * t * 0.1
            prev = sv
            if (q >= Vdl2.PREAMBLE) {
                val b = Vdl2.grayBits[((idx - prevIdx) % 8 + 8) % 8]
                bits[nb++] = (b shr 2) and 1; bits[nb++] = (b shr 1) and 1; bits[nb++] = b and 1
            }
            prevIdx = idx
        }
        return Demod(bits, 10 * log10(maxOf(1e-12, sig) / maxOf(1e-12, err2)))
    }

    companion object {
        private const val RING = 1 shl 16
        private const val MASK = (RING - 1).toLong()
        private const val DET = 0.56f
        private const val DROP = 0.88f

        fun lowpass(n0: Int, cutoff: Double): FloatArray {
            val n = if (n0 and 1 == 0) n0 + 1 else n0
            val m = n / 2
            val h = DoubleArray(n) { i ->
                val k = (i - m).toDouble()
                val s = if (k == 0.0) 2 * cutoff else sin(2 * PI * cutoff * k) / (PI * k)
                s * (0.42 - 0.5 * cos(2 * PI * i / (n - 1)) + 0.08 * cos(4 * PI * i / (n - 1)))
            }
            val sum = h.sum()
            return FloatArray(n) { (h[it] / sum).toFloat() }
        }
    }
}

/** HackRF bytes at 2 MS/s with 136.85 MHz at +fs/4 → 500 kS/s → each channel mixed down and decimated to 50 kS/s. */
class Vdl2Receiver(channels: List<Long> = Vdl2.EuropeHz, private val centreHz: Long = Vdl2.CENTRE_HZ, onMessage: (Vdl2Message) -> Unit) {
    private class Chan(val off: Double, val ch: Vdl2Channel) {
        val dec = FloatDecimator(10, 12_000.0 / 500_000.0, 96)
        /** The mixer's phasor, stepped by a complex multiply and renormalised each block. */
        var pr = 1.0
        var pi = 0.0
        val mr = FloatArray(16384 + 8)
        val mi = FloatArray(16384 + 8)
        val or = FloatArray(2048)
        val oi = FloatArray(2048)
    }

    private val d1 = Decimator(4, 0.075, taps = 48)
    private val aRe = FloatArray(32768)
    private val aIm = FloatArray(32768)
    private val chans = channels.map { Chan((it - centreHz).toDouble(), Vdl2Channel(it, 50_000.0, onMessage)) }
    val channels get() = chans.map { it.ch }

    fun feed(buf: ByteArray, len: Int) {
        val k = d1.process(buf, len, aRe, aIm, 0)
        feedFloat(aRe, aIm, k)
    }

    /** Samples at 500 kS/s centred on [centreHz]. */
    fun feedFloat(re: FloatArray, im: FloatArray, k: Int) {
        for (c in chans) {
            val w = -2 * PI * c.off / 500_000.0
            val sr = cos(w)
            val sj = sin(w)
            var off = 0
            while (off < k) {
                val m = minOf(16384, k - off)
                var pr = c.pr
                var pi = c.pi
                for (i in 0 until m) {
                    val co = pr.toFloat(); val si = pi.toFloat()
                    c.mr[i] = re[off + i] * co - im[off + i] * si
                    c.mi[i] = re[off + i] * si + im[off + i] * co
                    val nr = pr * sr - pi * sj
                    pi = pr * sj + pi * sr
                    pr = nr
                }
                val g = 1 / hypot(pr, pi)
                c.pr = pr * g; c.pi = pi * g
                val n = c.dec.process(c.mr, c.mi, m, c.or, c.oi)
                for (i in 0 until n) c.ch.push(c.or[i], c.oi[i])
                c.ch.process()
                off += m
            }
        }
    }

    companion object {
        const val SAMPLE_RATE = 2_000_000
    }
}
