package com.allnetworktools.data.sdr

/**
 * DMR (ETSI TS 102 361-1), the TDMA digital radio of DMR446 and of professional networks: 4FSK at 4800 symbols/s in
 * 30 ms bursts of 132 symbols, a 48-bit sync or embedded signalling in the middle. What is read here is the signalling
 * every radio sends in the clear: the slot type (Golay 20,8), voice call headers and terminators (BPTC 196,96, then
 * Reed-Solomon 12,9) with their group or unit ids, and control blocks (CSBK, CRC-CCITT). Voice is never decoded.
 * Ported from OnAir (rust/onair_dmr, GPL-3.0).
 */
object Dmr {
    /** BS voice, BS data, MS voice, MS data, RC, direct slot 1 voice, slot 1 data, slot 2 voice, slot 2 data. */
    val SYNC = longArrayOf(0x755FD7DF75F7, 0xDFF57D75DF5D, 0x7F7D5DD57DFD, 0xD5D7F77FD757, 0x77D55F7DFD77, 0x5D577F7757FF, 0xF7FDD5DDFD55, 0x7DFFD5F55D5F, 0xD7557F5FF7F5)
    fun syncIsVoice(s: Int) = s == 0 || s == 2 || s == 5 || s == 7
    fun syncSlot(s: Int) = when (s) { 5, 6 -> 1; 7, 8 -> 2; else -> 0 }

    fun dibitToSymbol(d: Int) = when (d) { 1 -> 3; 0 -> 1; 2 -> -1; else -> -3 }
    fun symbolToDibit(s: Int) = when (s) { 3 -> 1; 1 -> 0; -1 -> 2; else -> 3 }
    fun syncSymbols(s: Int) = IntArray(24) { dibitToSymbol(((SYNC[s] shr (46 - 2 * it)) and 3).toInt()) }

    // ---- short block codes (annex B)
    private val golayRows = intArrayOf(0x3DA, 0xD99, 0x6CD, 0x367, 0xDC6, 0xA97, 0x93E, 0x8EB)
    private val h1393 = intArrayOf(0xF, 0xE, 0x7, 0xA, 0x5, 0xB, 0xC, 0x6, 0x3)
    private val h15113 = intArrayOf(0x9, 0xD, 0xF, 0xE, 0x7, 0xA, 0x5, 0xB, 0xC, 0x6, 0x3)

    private fun rows(rows: IntArray, info: Int): Int {
        var p = 0
        for (i in rows.indices) if ((info shr (rows.size - 1 - i)) and 1 != 0) p = p xor rows[i]
        return p
    }
    private val golayWords = IntArray(256) { (it shl 12) or rows(golayRows, it) }

    fun golayParity(info8: Int) = rows(golayRows, info8 and 0xFF)

    /** Colour code and data type, or null beyond three bit errors. */
    fun slotType(w20: Int): Pair<Int, Int>? {
        var best = 99
        var bi = 0
        for (i in 0 until 256) { val d = Integer.bitCount(golayWords[i] xor w20); if (d < best) { best = d; bi = i } }
        return if (best <= 3) (bi shr 4) to (bi and 15) else null
    }

    private fun hamPos(r: IntArray, p: Int): IntArray {
        val pos = IntArray(32) { -1 }
        val k = r.size
        for (j in 0 until k + p) pos[if (j < k) r[j] else 1 shl (p - 1 - (j - k))] = j
        return pos
    }
    private val pos1393 = hamPos(h1393, 4)
    private val pos15113 = hamPos(h15113, 4)

    /** One error corrected in place: 0 clean, 1 fixed, -1 more. */
    private fun hamCorrect(r: IntArray, pos: IntArray, w: IntArray, at: Int) {
        val p = 4
        val info = w[at] shr p
        val syn = rows(r, info) xor (w[at] and 15)
        if (syn == 0) { w[at + 1] = 0; return }
        val j = pos[syn]
        if (j < 0) { w[at + 1] = -1; return }
        w[at] = w[at] xor (1 shl (r.size + p - 1 - j))
        w[at + 1] = 1
    }

    private fun bptcIndex(r: Int, c: Int) = (1 + r * 15 + c) * 181 % 196
    private val infoPos = IntArray(96).also { o -> var k = 0; for (r in 0 until 9) for (c in (if (r == 0) 3 else 0) until 11) o[k++] = r * 15 + c }

    fun bptcEncode(info96: IntArray): IntArray {
        val m = Array(13) { IntArray(15) }
        for (i in 0 until 96) m[infoPos[i] / 15][infoPos[i] % 15] = info96[i] and 1
        for (r in 0 until 9) {
            var w = 0
            for (c in 0 until 11) w = (w shl 1) or m[r][c]
            val p = rows(h15113, w)
            for (j in 0 until 4) m[r][11 + j] = (p shr (3 - j)) and 1
        }
        for (c in 0 until 15) {
            var w = 0
            for (r in 0 until 9) w = (w shl 1) or m[r][c]
            val p = rows(h1393, w)
            for (j in 0 until 4) m[9 + j][c] = (p shr (3 - j)) and 1
        }
        val tx = IntArray(196)
        for (r in 0 until 13) for (c in 0 until 15) tx[bptcIndex(r, c)] = m[r][c]
        return tx
    }

    /** BPTC (196,96) with up to six row and column passes; null when the checks do not clear. */
    fun bptcDecode(tx: IntArray): IntArray? {
        val m = Array(13) { r -> IntArray(15) { c -> tx[bptcIndex(r, c)] and 1 } }
        val w = IntArray(2)
        var clean = false
        var iter = 0
        while (iter < 6 && !clean) {
            clean = true
            for (c in 0 until 15) {
                w[0] = 0; for (r in 0 until 13) w[0] = (w[0] shl 1) or m[r][c]
                hamCorrect(h1393, pos1393, w, 0)
                if (w[1] > 0) { for (r in 0 until 13) m[r][c] = (w[0] shr (12 - r)) and 1; clean = false } else if (w[1] < 0) clean = false
            }
            for (r in 0 until 9) {
                w[0] = 0; for (c in 0 until 15) w[0] = (w[0] shl 1) or m[r][c]
                hamCorrect(h15113, pos15113, w, 0)
                if (w[1] > 0) { for (c in 0 until 15) m[r][c] = (w[0] shr (14 - c)) and 1; clean = false } else if (w[1] < 0) clean = false
            }
            iter++
        }
        if (!clean) {
            for (c in 0 until 15) { var v = 0; for (r in 0 until 13) v = (v shl 1) or m[r][c]; if (rows(h1393, v shr 4) != (v and 15)) return null }
            for (r in 0 until 9) { var v = 0; for (c in 0 until 15) v = (v shl 1) or m[r][c]; if (rows(h15113, v shr 4) != (v and 15)) return null }
        }
        return IntArray(96) { m[infoPos[it] / 15][infoPos[it] % 15] }
    }

    // ---- Reed-Solomon (12,9) over GF(256), x^8 + x^4 + x^3 + x^2 + 1
    private val ex = IntArray(512)
    private val lg = IntArray(256)
    init {
        var x = 1
        for (i in 0 until 255) { ex[i] = x; lg[x] = i; x = x shl 1; if (x and 0x100 != 0) x = x xor 0x11D }
        for (i in 255 until 512) ex[i] = ex[i - 255]
    }
    private fun mul(a: Int, b: Int) = if (a != 0 && b != 0) ex[lg[a] + lg[b]] else 0
    private val rsGen = intArrayOf(0x0E, 0x38, 0x40)

    fun rsParity(msg: IntArray): IntArray {
        val r = IntArray(3)
        for (m in msg.take(9)) {
            val fb = m xor r[0]
            r[0] = r[1] xor mul(fb, rsGen[0]); r[1] = r[2] xor mul(fb, rsGen[1]); r[2] = mul(fb, rsGen[2])
        }
        return r
    }

    /** One wrong octet corrected: 0 clean, 1 fixed, -1 failed. */
    fun rsCorrect(w: IntArray): Int {
        val s = IntArray(3)
        for (j in 1..3) { var acc = 0; for (x in w) acc = mul(acc, ex[j]) xor x; s[j - 1] = acc }
        if (s.all { it == 0 }) return 0
        if (s.any { it == 0 }) return -1
        val p = (lg[s[1]] - lg[s[0]] + 255) % 255
        if (p > 11) return -1
        val e = ex[(lg[s[0]] - p + 255) % 255]
        if (mul(e, ex[(3 * p) % 255]) != s[2]) return -1
        w[11 - p] = w[11 - p] xor e
        return 1
    }

    fun crcCcitt(d: IntArray, n: Int): Int {
        var c = 0
        for (i in 0 until n) { c = c xor (d[i] shl 8); repeat(8) { c = if (c and 0x8000 != 0) (c shl 1) xor 0x1021 else c shl 1 }; c = c and 0xFFFF }
        return c.inv() and 0xFFFF
    }

    private fun bytes(bits: IntArray, n: Int) = IntArray(n) { o -> var v = 0; for (b in 0 until 8) v = (v shl 1) or bits[8 * o + b]; v }
    private fun bits(bytes: IntArray) = IntArray(bytes.size * 8) { (bytes[it / 8] shr (7 - it % 8)) and 1 }

    /** A full link control: opcode (0 group voice, 3 unit to unit), feature set, service options, destination, source. */
    data class Lc(val flco: Int, val fid: Int, val svc: Int, val dst: Int, val src: Int) {
        val group get() = flco == 0
        val emergency get() = svc and 0x80 != 0
        val privacy get() = svc and 0x40 != 0
        fun bytes() = intArrayOf(flco and 0x3F, fid, svc, dst shr 16 and 0xFF, dst shr 8 and 0xFF, dst and 0xFF, src shr 16 and 0xFF, src shr 8 and 0xFF, src and 0xFF)
    }

    /** Header (0x96) or terminator (0x99) information: the LC with its masked Reed-Solomon parity. */
    fun lcInfo(lc: Lc, terminator: Boolean): IntArray {
        val b = lc.bytes()
        val mask = if (terminator) 0x99 else 0x96
        return bits(b + rsParity(b).map { it xor mask })
    }

    fun lcDecode(info96: IntArray, terminator: Boolean): Lc? {
        val w = bytes(info96, 12)
        val mask = if (terminator) 0x99 else 0x96
        for (i in 9 until 12) w[i] = w[i] xor mask
        if (rsCorrect(w) < 0) return null
        return Lc(w[0] and 0x3F, w[1], w[2], (w[3] shl 16) or (w[4] shl 8) or w[5], (w[6] shl 16) or (w[7] shl 8) or w[8])
    }

    /** A control block: opcode, feature set, eight argument octets; CRC masked with 0xA5A5. */
    data class Csbk(val opcode: Int, val fid: Int, val data: IntArray) {
        val dst get() = (data[2] shl 16) or (data[3] shl 8) or data[4]
        val src get() = (data[5] shl 16) or (data[6] shl 8) or data[7]
    }

    fun csbkInfo(c: Csbk): IntArray {
        val d = intArrayOf(0x80 or (c.opcode and 0x3F), c.fid) + c.data
        val crc = crcCcitt(d, 10) xor 0xA5A5
        return bits(d + intArrayOf(crc shr 8, crc and 0xFF))
    }

    fun csbkParse(info96: IntArray): Csbk? {
        val b = bytes(info96, 12)
        if (crcCcitt(b, 10) xor 0xA5A5 != (b[10] shl 8 or b[11])) return null
        return Csbk(b[0] and 0x3F, b[1], b.copyOfRange(2, 10))
    }

    /** A 264-bit data burst: payload halves around the slot type and the sync. */
    fun dataBurst(cc: Int, dt: Int, payload196: IntArray, sync: Int): IntArray {
        val out = ArrayList<Int>()
        fun put(v: Long, n: Int) { for (i in n - 1 downTo 0) out += ((v shr i) and 1).toInt() }
        val info = ((cc and 15) shl 4) or (dt and 15)
        val par = golayParity(info)
        for (i in 0 until 98) out += payload196[i]
        put(info.toLong(), 8); put((par shr 10).toLong(), 2)
        put(SYNC[sync], 48)
        put((par and 0x3FF).toLong(), 10)
        for (i in 98 until 196) out += payload196[i]
        return out.toIntArray()
    }

    /** What a received data burst says. */
    sealed class Burst(val cc: Int) {
        class Header(cc: Int, val lc: Lc) : Burst(cc)
        class Terminator(cc: Int, val lc: Lc) : Burst(cc)
        class Control(cc: Int, val csbk: Csbk) : Burst(cc)
        class Other(cc: Int, val dataType: Int) : Burst(cc)
    }

    /** Reads a 264-bit burst whose centre was a data sync. */
    fun readData(b: IntArray): Burst? {
        var w = 0
        for (i in 98 until 108) w = (w shl 1) or b[i]
        for (i in 156 until 166) w = (w shl 1) or b[i]
        val (cc, dt) = slotType(w) ?: return null
        val payload = IntArray(196) { if (it < 98) b[it] else b[it + 68] }
        return when (dt) {
            1, 2 -> bptcDecode(payload)?.let { lcDecode(it, dt == 2) }?.let { if (dt == 1) Burst.Header(cc, it) else Burst.Terminator(cc, it) }
            3 -> bptcDecode(payload)?.let(::csbkParse)?.let { Burst.Control(cc, it) }
            else -> Burst.Other(cc, dt)
        }
    }
}

/**
 * dPMR mode 1 (ETSI TS 102 490), the FDMA digital PMR446: 4FSK at 2400 symbols/s on 6.25 kHz. Read here: the header
 * frame (colour code, called and own ids, call type) and the end frame. Every block is Hamming (12,8) words written in
 * rows, read in columns and scrambled with PN9. Ported from OnAir (core/src/dpmr, GPL-3.0).
 */
object Dpmr {
    private fun syms(v: Long, n: Int) = IntArray(n) { Dmr.dibitToSymbol(((v shr (2 * (n - 1 - it))) and 3).toInt()) }
    val FS1 = syms(0x57FF5F75D577, 24)
    val FS3 = syms(0x7DFFD5F55D5F, 24)
    val FS4 = syms(0xFD55F5DF7FDD, 24)

    private val syn = intArrayOf(0x3, 0x5, 0x6, 0x7, 0x9, 0xA, 0xB, 0xC)

    fun pn9(n: Int): IntArray {
        var s = 0x1FF
        return IntArray(n) { val o = s and 1; val f = (s xor (s shr 5)) and 1; s = (s shr 1) or (f shl 8); o }
    }

    private fun crc(b: IntArray, n: Int, deg: Int, poly: Int, init: Int): Int {
        var r = init
        val top = 1 shl (deg - 1)
        val mask = (1 shl deg) - 1
        for (i in 0 until n) { val f = (r and top != 0) != (b[i] != 0); r = (r shl 1) and mask; if (f) r = r xor poly }
        return r
    }
    fun crc8(b: IntArray, n: Int) = crc(b, n, 8, 0x07, 0)
    fun crc7(b: IntArray, n: Int) = crc(b, n, 7, 0x09, 0)

    fun blockEncode(info: IntArray, nw: Int): IntArray {
        val air = IntArray(12 * nw)
        for (w in 0 until nw) {
            var p = 0
            for (i in 0 until 8) { val d = info[8 * w + i] and 1; air[i * nw + w] = d; if (d == 1) p = p xor syn[i] }
            for (i in 0 until 4) air[(8 + i) * nw + w] = (p shr (3 - i)) and 1
        }
        val pn = pn9(12 * nw)
        for (i in air.indices) air[i] = air[i] xor pn[i]
        return air
    }

    /** Null when a word has more than one error. */
    fun blockDecode(air: IntArray, nw: Int): IntArray? {
        val pn = pn9(12 * nw)
        val info = IntArray(8 * nw)
        val c = IntArray(12)
        for (w in 0 until nw) {
            for (k in 0 until 12) c[k] = (air[k * nw + w] xor pn[k * nw + w]) and 1
            var s = 0
            for (i in 0 until 8) if (c[i] == 1) s = s xor syn[i]
            for (i in 0 until 4) if (c[8 + i] == 1) s = s xor (1 shl (3 - i))
            if (s != 0) {
                val j = syn.indexOf(s)
                if (j >= 0) c[j] = c[j] xor 1 else if (Integer.bitCount(s) != 1) return null
            }
            for (i in 0 until 8) info[8 * w + i] = c[i]
        }
        return info
    }

    data class Header(val type: Int, val called: Int, val own: Int, val commMode: Int, val callType: Int, val emergency: Boolean)

    private fun put(b: IntArray, p0: Int, v: Int, n: Int): Int { var p = p0; for (i in n - 1 downTo 0) b[p++] = (v shr i) and 1; return p }
    private fun get(b: IntArray, p: Int, n: Int): Int { var v = 0; for (i in 0 until n) v = (v shl 1) or b[p + i]; return v }

    fun headerBits(h: Header): IntArray {
        val b = IntArray(80)
        var p = put(b, 0, h.type, 4)
        p = put(b, p, h.called, 24); p = put(b, p, h.own, 24)
        p = put(b, p, h.commMode, 3); p = put(b, p, h.callType, 2); p = put(b, p, if (h.emergency) 1 else 0, 1)
        p = put(b, p, 0, 14)
        put(b, p, crc8(b, 72), 8)
        return b
    }

    fun headerParse(b: IntArray): Header? {
        if (get(b, 72, 8) != crc8(b, 72)) return null
        val h = Header(get(b, 0, 4), get(b, 4, 24), get(b, 28, 24), get(b, 52, 3), get(b, 55, 2), get(b, 57, 1) == 1)
        return h.takeIf { it.type <= 5 && it.commMode <= 5 && it.callType <= 2 }
    }

    fun endBits(own: Int): IntArray {
        val b = IntArray(48)
        var p = put(b, 0, 0, 2)
        p = put(b, p, own, 24); p = put(b, p, 0, 15)
        put(b, p, crc7(b, 41), 7)
        return b
    }

    fun endParse(b: IntArray): Int? = if (get(b, 41, 7) == crc7(b, 41)) get(b, 2, 24) else null

    fun ccSymbols(cc: Int) = IntArray(12) { val bit = (cc shr (5 - it / 2)) and 1; if ((it % 2 == 0) == (bit == 1)) 3 else -3 }
    fun ccDecode(v: FloatArray, at: Int): Int { var cc = 0; for (i in 0 until 6) if (v[at + 2 * i] - v[at + 2 * i + 1] > 0) cc = cc or (1 shl (5 - i)); return cc }

    fun idText(id: Int) = if (id == 0xFFFFFF) "ALL" else "%08d".format(id)
}
