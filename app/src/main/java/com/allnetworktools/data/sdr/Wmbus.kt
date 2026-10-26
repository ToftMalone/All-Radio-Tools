package com.allnetworktools.data.sdr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.log10
import kotlin.math.pow

/**
 * Wireless M-Bus (EN 13757-3 and -4), the radio utility meters use: water, heat, gas, electricity, heat cost
 * allocators. Ported from OnAir (core/src/wmbus, GPL-3.0, itself written from the public standard). Modes T1 and C1
 * on 868.95 MHz, 100 kchip/s FSK, are received: by far the most common for water and heat meters. Frames whose payload
 * is encrypted are listed with their clear link-layer fields; their readings are never decoded.
 */
object Wmbus {
    /** EN 13757-4 CRC: polynomial 0x3D65, start 0, result inverted. */
    fun crc16(d: IntArray, from: Int, n: Int): Int {
        var c = 0
        for (i in from until from + n) {
            c = c xor (d[i] shl 8)
            repeat(8) { c = if (c and 0x8000 != 0) (c shl 1) xor 0x3D65 else c shl 1 }
            c = c and 0xFFFF
        }
        return c.inv() and 0xFFFF
    }

    private val t36 = intArrayOf(0x16, 0x0D, 0x0E, 0x0B, 0x1C, 0x19, 0x1A, 0x13, 0x2C, 0x25, 0x26, 0x23, 0x34, 0x31, 0x32, 0x29)
    private val d36 = IntArray(64) { -1 }.also { a -> t36.forEachIndexed { i, c -> a[c] = i } }
    fun encode36(nibble: Int) = t36[nibble and 15]
    fun decode36(six: Int) = d36[six and 63]

    /** Bytes on air, CRCs included, for a length field [l]. */
    fun airLength(l: Int, formatB: Boolean): Int {
        val n = l + 1
        if (formatB) return n
        if (n <= 10) return n + 2
        return n + 2 + 2 * ((n - 10 + 15) / 16)
    }

    /** The frame as sent, with its CRCs, for the body C M A CI data (without L). */
    fun buildFrame(body: IntArray, formatB: Boolean): IntArray {
        val out = ArrayList<Int>()
        fun put(p: IntArray, from: Int, n: Int) {
            val c = crc16(p, from, n)
            for (i in from until from + n) out += p[i]
            out += c ushr 8; out += c and 0xFF
        }
        if (!formatB) {
            val plain = intArrayOf(body.size) + body
            var pos = minOf(10, plain.size)
            put(plain, 0, pos)
            while (pos < plain.size) { val b = minOf(16, plain.size - pos); put(plain, pos, b); pos += b }
        } else {
            val ncrc = if (1 + body.size + 2 <= 128) 1 else 2
            val plain = intArrayOf(body.size + ncrc * 2) + body
            if (ncrc == 1) put(plain, 0, plain.size) else { put(plain, 0, 126); put(plain, 126, plain.size - 126) }
        }
        return out.toIntArray()
    }

    fun mfrCode(m: Int) = String(charArrayOf((((m shr 10) and 31) + 64).toChar(), (((m shr 5) and 31) + 64).toChar(), ((m and 31) + 64).toChar()))
    fun mfrOf(three: String) = (((three[0].code - 64) and 31) shl 10) or (((three[1].code - 64) and 31) shl 5) or ((three[2].code - 64) and 31)

    /** Well-known makers by their FLAG code. */
    val makers = mapOf(
        "AMT" to "Aquametro", "APA" to "Apator", "BMP" to "B METERS", "BMT" to "BMETERS", "BRA" to "Brunata", "DME" to "Diehl Metering",
        "DWZ" to "Lorenz", "EFE" to "Engelmann", "ELS" to "Elster", "EMH" to "EMH Metering", "GAV" to "Gavazzi", "HAG" to "Hager",
        "HYD" to "Diehl Hydrometer", "IST" to "ista", "ITR" to "Itron", "ITW" to "Itron", "KAM" to "Kamstrup", "LAS" to "Lansen",
        "LUG" to "Landis+Gyr", "MAD" to "Maddalena", "NES" to "Ness", "QDS" to "Qundis", "SAP" to "Sappel", "SEN" to "Sensus",
        "SON" to "Sontex", "TCH" to "Techem", "WEH" to "Wehrle", "ZRI" to "Zenner", "ZRM" to "Zenner", "SEO" to "Sensortec",
        "AXI" to "Axioma", "GWF" to "GWF", "INV" to "Invensys", "RAM" to "Rossweiner", "ELV" to "Elvaco", "WMO" to "Westermo",
    )

    /**
     * Device type index into the localized names (wmbus_types): other, oil, electricity, gas, heat, steam, warm water,
     * water, heat cost allocator, compressed air, cooling, heat and cooling, bus, unknown, hot water, cold water, dual
     * water, pressure, A/D converter, smoke detector, room sensor, gas detector, breaker, valve, waste water, repeater.
     */
    fun typeIndex(t: Int) = when (t) {
        0x00 -> 0; 0x01 -> 1; 0x02 -> 2; 0x03 -> 3; 0x04, 0x0C -> 4; 0x05 -> 5; 0x06 -> 6; 0x07 -> 7; 0x08 -> 8; 0x09 -> 9
        0x0A, 0x0B -> 10; 0x0D -> 11; 0x0E, 0x31, 0x36, 0x37 -> 12; 0x0F -> 13; 0x15 -> 14; 0x16 -> 15; 0x17 -> 16; 0x18 -> 17
        0x19 -> 18; 0x1A -> 19; 0x1B -> 20; 0x1C -> 21; 0x20 -> 22; 0x21 -> 23; 0x28 -> 24; 0x32, 0x33 -> 25
        else -> 0
    }

    fun bcdId(d: IntArray, o: Int) = buildString { for (i in 3 downTo 0) { append(((d[o + i] shr 4) and 15) % 10); append((d[o + i] and 15) % 10) } }

    /**
     * Quantity index into the localized names (wmbus_quantities): energy, volume, mass, on time, operating time, power,
     * volume flow, mass flow, flow temperature, return temperature, temperature difference, external temperature,
     * pressure, date, date and time, HCA units, averaging duration, actuality duration, fabrication number, enhanced
     * identification, bus address, access number, medium, manufacturer, parameter set, model, hardware version,
     * firmware version, software version, customer location, customer, error flags, battery life (days), manufacturer
     * specific.
     */
    object Q {
        const val ENERGY = 0; const val VOLUME = 1; const val MASS = 2; const val ON_TIME = 3; const val OP_TIME = 4; const val POWER = 5
        const val VOL_FLOW = 6; const val MASS_FLOW = 7; const val FLOW_T = 8; const val RETURN_T = 9; const val DIFF_T = 10; const val EXT_T = 11
        const val PRESSURE = 12; const val DATE = 13; const val DATETIME = 14; const val HCA = 15; const val AVG = 16; const val ACTUALITY = 17
        const val FABRICATION = 18; const val ENHANCED_ID = 19; const val BUS_ADDR = 20; const val MFR_SPECIFIC = 33
        /** 0xFD extension codes → index. */
        val fd = mapOf(0x08 to 21, 0x09 to 22, 0x0A to 23, 0x0B to 24, 0x0C to 25, 0x0D to 26, 0x0E to 27, 0x0F to 28, 0x10 to 29, 0x11 to 30, 0x17 to 31, 0x74 to 32)
    }

    private class Vif(val q: Int, val unit: String, val exp: Int)

    private val timeUnits = arrayOf("s", "min", "h", "d")

    private fun vif(v0: Int): Vif? {
        val v = v0 and 0x7F
        val n = v and 7
        return when {
            v <= 0x07 -> Vif(Q.ENERGY, "Wh", n - 3)
            v <= 0x0F -> Vif(Q.ENERGY, "J", n)
            v <= 0x17 -> Vif(Q.VOLUME, "m³", n - 6)
            v <= 0x1F -> Vif(Q.MASS, "kg", n - 3)
            v <= 0x23 -> Vif(Q.ON_TIME, timeUnits[v and 3], 0)
            v <= 0x27 -> Vif(Q.OP_TIME, timeUnits[v and 3], 0)
            v <= 0x2F -> Vif(Q.POWER, "W", n - 3)
            v <= 0x37 -> Vif(Q.POWER, "J/h", n)
            v <= 0x3F -> Vif(Q.VOL_FLOW, "m³/h", n - 6)
            v <= 0x47 -> Vif(Q.VOL_FLOW, "m³/min", n - 7)
            v <= 0x4F -> Vif(Q.VOL_FLOW, "m³/s", n - 9)
            v <= 0x57 -> Vif(Q.MASS_FLOW, "kg/h", n - 3)
            v <= 0x5B -> Vif(Q.FLOW_T, "°C", (v and 3) - 3)
            v <= 0x5F -> Vif(Q.RETURN_T, "°C", (v and 3) - 3)
            v <= 0x63 -> Vif(Q.DIFF_T, "K", (v and 3) - 3)
            v <= 0x67 -> Vif(Q.EXT_T, "°C", (v and 3) - 3)
            v <= 0x6B -> Vif(Q.PRESSURE, "bar", (v and 3) - 3)
            v == 0x6C -> Vif(Q.DATE, "", 0)
            v == 0x6D -> Vif(Q.DATETIME, "", 0)
            v == 0x6E -> Vif(Q.HCA, "", 0)
            v == 0x6F -> null
            v <= 0x73 -> Vif(Q.AVG, timeUnits[v and 3], 0)
            v <= 0x77 -> Vif(Q.ACTUALITY, timeUnits[v and 3], 0)
            v == 0x78 -> Vif(Q.FABRICATION, "", 0)
            v == 0x79 -> Vif(Q.ENHANCED_ID, "", 0)
            v == 0x7A -> Vif(Q.BUS_ADDR, "", 0)
            else -> null
        }
    }

    /**
     * One data record: [quantity] is an index (see [Q]) or -1 with [code] naming an unknown VIF; [storage] 0 is the
     * current value; [function] 0 instantaneous, 1 maximum, 2 minimum, 3 error state.
     */
    data class Record(val quantity: Int, val code: String?, val value: String, val unit: String, val storage: Long, val tariff: Int, val subunit: Int, val function: Int)

    private val dataLen = intArrayOf(0, 1, 2, 3, 4, 4, 6, 8, 0, 1, 2, 3, 4, -1, 6, 0)

    private fun num(v: Double, exp: Int): String {
        val s = v * 10.0.pow(exp)
        return if (exp < 0) "%.${minOf(-exp, 9)}f".format(java.util.Locale.ROOT, s) else "%.0f".format(java.util.Locale.ROOT, s)
    }

    /** The DIF/VIF records of an unencrypted payload; null when it is cut in the middle of one. */
    fun records(d: IntArray, from: Int = 0, to: Int = d.size): List<Record>? {
        val out = ArrayList<Record>()
        var i = from
        while (i < to) {
            val dif = d[i++]
            if (dif == 0x2F) continue
            if (dif == 0x0F || dif == 0x1F || dif == 0x7F) break // manufacturer data follows
            var storage = ((dif shr 6) and 1).toLong()
            var tariff = 0
            var subunit = 0
            var sh = 1
            var ext = dif and 0x80
            var k = 0
            while (ext != 0 && k < 10) {
                if (i >= to) return null
                val e = d[i++]
                storage = storage or ((e and 15).toLong() shl sh); sh += 4
                tariff = tariff or (((e shr 4) and 3) shl (2 * k))
                subunit = subunit or (((e shr 6) and 1) shl k)
                ext = e and 0x80
                k++
            }
            if (i >= to) return null
            val vifByte = d[i++]
            var q = -1
            var code: String? = null
            var vi: Vif? = null
            when (vifByte) {
                0xFD, 0xFB -> {
                    if (i >= to) return null
                    val c = d[i++]
                    q = if (vifByte == 0xFD) Q.fd[c and 0x7F] ?: -1 else -1
                    if (q < 0) code = "VIF %02X %02X".format(vifByte, c)
                    ext = c and 0x80
                }
                0x7C -> {
                    if (i >= to) return null
                    val l = d[i++]
                    if (i + l > to) return null
                    code = String(CharArray(l) { d[i + l - 1 - it].toChar() })
                    i += l; ext = 0
                }
                else -> {
                    vi = vif(vifByte)
                    if (vi != null) q = vi.q else code = "VIF %02X".format(vifByte and 0x7F)
                    if (vifByte and 0x7F == 0x7F) { q = Q.MFR_SPECIFIC; code = null; vi = null }
                    ext = vifByte and 0x80
                }
            }
            k = 0
            while (ext != 0 && k < 10) { if (i >= to) return null; ext = d[i++] and 0x80; k++ }
            var len = dataLen[dif and 15]
            var dc = dif and 15
            if (len < 0) {
                if (i >= to) return null
                val lv = d[i++]
                if (lv >= 0xC0) break
                len = lv; dc = 13
            }
            if (i + len > to) return null
            val p = i
            i += len
            val exp = vi?.exp ?: 0
            val value = when {
                dc == 0 || dc == 8 || dc == 15 -> "-"
                dc == 13 -> String(CharArray(len) { val c = d[p + len - 1 - it]; if (c in 32..126) c.toChar() else '.' })
                dc == 5 -> num(java.lang.Float.intBitsToFloat(d[p] or (d[p + 1] shl 8) or (d[p + 2] shl 16) or (d[p + 3] shl 24)).toDouble(), exp)
                dc in 9..12 || dc == 14 -> {
                    var v = 0.0
                    var neg = false
                    for (b in len - 1 downTo 0) {
                        var hi = d[p + b] shr 4
                        if (b == len - 1 && hi == 0xF) { neg = true; hi = 0 }
                        v = v * 100 + hi * 10 + (d[p + b] and 15)
                    }
                    num(if (neg) -v else v, exp)
                }
                else -> {
                    var u = 0L
                    for (b in len - 1 downTo 0) u = (u shl 8) or d[p + b].toLong()
                    val sv = if (len < 8 && (u shr (8 * len - 1)) and 1L == 1L) u - (1L shl (8 * len)) else u
                    when {
                        vifByte == 0x6C && len == 2 -> "%04d-%02d-%02d".format(2000 + (((d[p] and 0xE0) shr 5) or ((d[p + 1] and 0xF0) shr 1)), d[p + 1] and 15, d[p] and 31)
                        vifByte == 0x6D && len == 4 -> "%04d-%02d-%02d %02d:%02d".format(2000 + (((d[p + 2] and 0xE0) shr 5) or ((d[p + 3] and 0xF0) shr 1)), d[p + 3] and 15, d[p + 2] and 31, d[p + 1] and 31, d[p] and 63)
                        vi != null -> num(sv.toDouble(), exp)
                        else -> sv.toString()
                    }
                }
            }
            out += Record(q, code, value, if (value == "-") "" else vi?.unit ?: "", storage, tariff, subunit, (dif shr 4) and 3)
        }
        return out
    }

    data class Frame(
        val formatB: Boolean,
        val data: IntArray,
        val c: Int,
        val mfr: String,
        val id: String,
        val version: Int,
        val type: Int,
        val ci: Int,
        val encrypted: Boolean,
        val status: Int?,
        val records: List<Record>,
    )

    /** Checks the CRCs of what was received ([air] at least [airLength] long) and reads the frame; null on a failed CRC. */
    fun parse(air: IntArray, formatB: Boolean): Frame? {
        if (air.size < 3) return null
        val l = air[0]
        if (air.size < airLength(l, formatB)) return null
        val data = ArrayList<Int>()
        var pos = 0
        fun block(n: Int): Boolean {
            val c = crc16(air, pos, n)
            val ok = c == (air[pos + n] shl 8 or air[pos + n + 1])
            for (i in pos until pos + n) data += air[i]
            pos += n + 2
            return ok
        }
        val total = l + 1
        if (!formatB) {
            var left = total
            var b = minOf(10, left)
            if (!block(b)) return null
            left -= b
            while (left > 0) { b = minOf(16, left); if (!block(b)) return null; left -= b }
        } else {
            if (total < 3 || total in 129..130) return null
            if (total <= 128) { if (!block(total - 2)) return null } else { if (!block(126) || !block(total - 130)) return null }
        }
        if (data.size < 11) return null
        val d = data.toIntArray()
        var p = 11
        var ci = d[10]
        var encrypted = false
        var status: Int? = null
        when (ci) {
            0x8C -> { if (p + 3 > d.size) return frame(d, formatB, false, null, emptyList()); p += 2; ci = d[p++] }
            0x8D -> {
                if (p + 9 > d.size) return frame(d, formatB, false, null, emptyList())
                if ((d[p + 5] shr 5) and 7 != 0) return frame(d, formatB, true, null, emptyList())
                p += 8; ci = d[p++]
            }
        }
        if (ci == 0x7A || ci == 0x72 || ci == 0x7B) {
            val hl = if (ci == 0x72) 12 else 4
            if (p + hl > d.size) return frame(d, formatB, false, null, emptyList())
            if (ci == 0x72) p += 8
            status = d[p + 1]
            val config = d[p + 2] or (d[p + 3] shl 8)
            p += 4
            if ((config shr 8) and 0x1F != 0) encrypted = true
        }
        val recs = if (!encrypted && (ci == 0x7A || ci == 0x72 || ci == 0x78)) records(d, p) ?: emptyList() else emptyList()
        return frame(d, formatB, encrypted, status, recs)
    }

    private fun frame(d: IntArray, formatB: Boolean, enc: Boolean, status: Int?, recs: List<Record>) =
        Frame(formatB, d, d[1], mfrCode(d[2] or (d[3] shl 8)), bcdId(d, 4), d[8], d[9], d[10], enc, status, recs)

    /** Chips on air (1 = upper tone) for a frame's bytes: preamble, sync 0x543D, C-mode format marker, coded bytes. */
    fun chips(air: IntArray, mode: Mode, preamble: Int = 16): IntArray {
        val c = ArrayList<Int>()
        repeat(preamble) { c += 0; c += 1 }
        fun nrz(v: Int, bits: Int) { for (i in bits - 1 downTo 0) c += (v shr i) and 1 }
        nrz(0x543D, 16)
        when (mode) { Mode.C1A -> nrz(0x54CD, 16); Mode.C1B -> nrz(0x543D, 16); Mode.T1 -> {} }
        for (b in air) if (mode == Mode.T1) { nrz(encode36(b shr 4), 6); nrz(encode36(b and 15), 6) } else nrz(b, 8)
        repeat(4) { c += 0; c += 1 }
        return c.toIntArray()
    }

    enum class Mode(val label: String) { T1("T1"), C1A("C1"), C1B("C1") }
}

/** A frame that passed its CRCs, with how it came and its level over the noise. */
class WmbusTelegram(val frame: Wmbus.Frame, val mode: Wmbus.Mode, val snrDb: Double)

/**
 * T1 and C1 from samples at 500 kS/s centred on 868.95 MHz: FM demodulation, a chip every 5 samples at each of the 5
 * phases, the decision level taken from the alternating preamble (so a meter's frequency error needs no correction).
 * After the sync, the next 16 chips tell C1 (format A or B) from T1; the bytes are read once the longest frame could
 * be in, and the CRCs decide.
 */
class WmbusDemodulator(private val onTelegram: (WmbusTelegram) -> Unit) {
    private val lp = FloatDecimator(1, 110_000.0 / RATE, 33)
    private val yr = FloatArray(BLOCK)
    private val yi = FloatArray(BLOCK)
    private var pr = 1f
    private var pi = 0f
    private var n = 0L
    private val dring = FloatArray(SPS)
    private var s = 0f
    private val soft = Array(SPS) { FloatArray(RING) }
    private val sum = FloatArray(SPS)
    private val reg = IntArray(SPS)
    private val pw = FloatArray(RING)
    private var pwAcc = 0f
    private var blkSum = 0.0
    private var blkN = 0
    private val blocks = ArrayDeque<Double>()
    private var nf = 0.0
    private var grpOpen = false
    private var grpN0 = 0L
    private var best: Cand? = null
    private val jobs = ArrayDeque<Cand>()
    private val done = ArrayDeque<Long>()

    private class Cand(val p: Int, val k0: Long, val th: Float, val score: Float)

    var syncs = 0
        private set

    fun feed(re: FloatArray, im: FloatArray, len: Int) {
        var off = 0
        while (off < len) {
            val m = minOf(BLOCK, len - off)
            val inR = if (off == 0) re else re.copyOfRange(off, off + m)
            val inI = if (off == 0) im else im.copyOfRange(off, off + m)
            process(lp.process(inR, inI, m, yr, yi))
            off += m
        }
    }

    private fun process(m: Int) {
        val kd = (RATE / (2 * PI) / 50_000.0).toFloat()
        for (i in 0 until m) {
            val sr = yr[i]; val si = yi[i]
            val d = atan2(si * pr - sr * pi, sr * pr + si * pi) * kd
            pr = sr; pi = si
            val p = (n % SPS).toInt()
            val k = n / SPS
            val pwr = sr * sr + si * si
            pwAcc = if (p == 0) pwr else pwAcc + pwr
            if (p == SPS - 1) pw[(k and MASK).toInt()] = pwAcc
            blkSum += pwr
            if (++blkN >= NOISE_BLOCK) blockEnd()
            s += d - dring[p]
            dring[p] = d
            val kk = (k and MASK).toInt()
            // The mean of the last 32 chips: over the preamble, the carrier.
            sum[p] += s - soft[p][((k - 32) and MASK).toInt()]
            soft[p][kk] = s
            val th = sum[p] / 32
            reg[p] = ((reg[p] shl 1) or (if (s > th) 1 else 0)) and 0xFFFFFF
            val e = Integer.bitCount(reg[p] xor SYNC)
            if (n > 2000 && e <= 1 && done.none { abs(it - k) <= 3 }) {
                // Level from the 16 preamble chips before the sync word, which alternate.
                var t = 0f
                for (j in 24 until 40) t += soft[p][((k - j) and MASK).toInt()]
                val thr = t / 16
                var score = 0f
                for (j in 0 until 24) score += abs(soft[p][((k - j) and MASK).toInt()] - thr)
                val cd = Cand(p, k, thr, score)
                if (!grpOpen) { grpOpen = true; grpN0 = n; best = cd } else if (score > best!!.score) best = cd
            }
            if (grpOpen && n >= grpN0 + 3 * SPS) {
                grpOpen = false
                syncs++
                val b = best!!
                jobs.addLast(b); done.addLast(b.k0)
                while (done.size > 8) done.removeFirst()
            }
            while (jobs.isNotEmpty()) {
                val j = jobs.first()
                if (n < SPS * (j.k0 + MAX_CHIPS) + j.p + 1) break
                jobs.removeFirst()
                decode(j)
            }
            n++
        }
    }

    private fun chip(c: Cand, i: Int) = if (soft[c.p][((c.k0 + 1 + i) and MASK).toInt()] > c.th) 1 else 0
    private fun bits(c: Cand, from: Int, count: Int): Int { var v = 0; for (i in 0 until count) v = (v shl 1) or chip(c, from + i); return v }

    private fun decode(c: Cand) {
        val marker = bits(c, 0, 16)
        val (mode, start) = when (marker) {
            0x54CD -> Wmbus.Mode.C1A to 16
            0x543D -> Wmbus.Mode.C1B to 16
            else -> Wmbus.Mode.T1 to 0
        }
        val bytes = ArrayList<Int>()
        fun byteAt(i: Int): Int {
            if (mode != Wmbus.Mode.T1) return bits(c, start + 8 * i, 8)
            val hi = Wmbus.decode36(bits(c, start + 12 * i, 6))
            val lo = Wmbus.decode36(bits(c, start + 12 * i + 6, 6))
            return if (hi < 0 || lo < 0) -1 else (hi shl 4) or lo
        }
        val l = byteAt(0)
        if (l < 10) return
        val formatB = mode == Wmbus.Mode.C1B
        val total = Wmbus.airLength(l, formatB)
        for (i in 0 until total) { val b = byteAt(i); if (b < 0) return; bytes += b }
        val f = Wmbus.parse(bytes.toIntArray(), formatB) ?: return
        val chipsUsed = start + total * (if (mode == Wmbus.Mode.T1) 12 else 8)
        var pwr = 0.0
        for (k in c.k0 - 24..c.k0 + chipsUsed) pwr += pw[(k and MASK).toInt()]
        pwr /= (chipsUsed + 25).toDouble() * SPS
        val nfv = if (nf > 0) nf else 1e-12
        onTelegram(WmbusTelegram(f, mode, 10 * log10(maxOf(pwr - nfv, nfv * 0.01) / nfv)))
    }

    private fun blockEnd() {
        blocks.addLast(blkSum / blkN)
        if (blocks.size > 250) blocks.removeFirst()
        if (blocks.size >= 3) nf = maxOf(blocks.sorted()[blocks.size / 10] * 1.05, 1e-12)
        blkSum = 0.0; blkN = 0
    }

    companion object {
        const val RATE = 500_000.0
        private const val SPS = 5
        private const val RING = 4096
        private const val MASK = (RING - 1).toLong()
        /** The last 8 preamble chips then the sync word. */
        private const val SYNC = 0x55543D
        /** The longest T1 frame: L = 255 in format A, 12 chips a byte. */
        private const val MAX_CHIPS = 290 * 12 + 16
        private const val BLOCK = 8192
        private const val NOISE_BLOCK = 10_000
    }
}

/** HackRF bytes at 2 MS/s with 868.95 MHz at +fs/4 → 500 kS/s. */
class WmbusReceiver(onTelegram: (WmbusTelegram) -> Unit) {
    private val dec = Decimator(4, 150_000.0 / SAMPLE_RATE, 80)
    private val re = FloatArray(131072 / 2 / 4 + 8)
    private val im = FloatArray(re.size)
    val demod = WmbusDemodulator(onTelegram)

    fun feed(buf: ByteArray, len: Int) {
        val n = dec.process(buf, len, re, im, 0)
        demod.feed(re, im, n)
    }

    companion object {
        const val SAMPLE_RATE = 2_000_000
        const val CENTRE_HZ = 868_950_000L
    }
}
