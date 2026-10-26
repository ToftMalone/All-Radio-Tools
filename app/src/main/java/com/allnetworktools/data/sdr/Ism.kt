package com.allnetworktools.data.sdr

import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * ISM band sensors (315 / 345 / 433.92 / 868 / 915 MHz): weather stations, thermometers, energy monitors, utility meters,
 * door and motion sensors, fixed-code remotes and OOK tyre sensors. Ported from OnAir (core/src/ism, GPL-3.0), itself
 * written from rtl_433 (GPL-2.0-or-later): the slicers follow its pulse_slicer.c and bitbuffer.c, every decoder names the
 * src/devices/ file it comes from, and a reading only leaves when its CRC, checksum or repetition check passed.
 */

private typealias Out = MutableList<IsmReading>

/** Mark (carrier on, or the upper tone) and the space after it, in microseconds. */
data class IsmPulse(val pulseUs: Float, val gapUs: Float)

class IsmTrain(val fsk: Boolean, val p: List<IsmPulse>)

data class IsmField(val name: String, val unit: String, val value: Double, val decimals: Int = 1, val text: String? = null)

data class IsmReading(
    val model: String,
    /** Weather, Energy, Meter, Sensor, Remote or TPMS. */
    val kind: String,
    val id: String,
    val mic: String,
    val channel: Int? = null,
    val channelName: String? = null,
    /** True ok, false low, null not sent. */
    val battery: Boolean? = null,
    val fields: List<IsmField> = emptyList(),
)

/** rtl_433's bit buffer: up to 50 rows of up to 4096 bits, most significant bit first. */
class IsmBits {
    val bb = ArrayList<IntArray>()
    val n = ArrayList<Int>()

    init { clear() }

    fun copy(): IsmBits { val c = IsmBits(); c.bb.clear(); c.n.clear(); bb.forEach { c.bb += it.copyOf() }; c.n.addAll(n); return c }
    fun rows() = bb.size
    fun clear() { bb.clear(); n.clear(); bb += IntArray(ROW_BYTES); n += 0 }
    fun hasData() = n[0] > 0 || rows() > 1

    fun addBit(b: Int) {
        val k = n.last()
        if (k >= MAX_BITS) return
        if (b != 0) bb.last()[k shr 3] = bb.last()[k shr 3] or (0x80 ushr (k and 7))
        n[n.lastIndex] = k + 1
    }

    /** As bitbuffer_add_row: empty rows count. */
    fun addRow() {
        if (rows() < MAX_ROWS) { bb += IntArray(ROW_BYTES); n += 0 } else { bb[bb.lastIndex] = IntArray(ROW_BYTES); n[n.lastIndex] = 0 }
    }

    fun addSync() { if (n.last() != 0) addRow() }

    fun bit(row: Int, pos: Int): Int = if (row < 0 || row >= rows() || pos < 0 || pos >= n[row]) 0 else (bb[row][pos shr 3] shr (7 - (pos and 7))) and 1
    fun byteAt(row: Int, i: Int) = if (row < rows() && i < ROW_BYTES) bb[row][i] else 0
    fun row(r: Int): IntArray = bb[r]

    fun invert() {
        for (r in 0 until rows()) {
            if (n[r] == 0) continue
            val lastCol = (n[r] - 1) / 8
            val lastBits = (n[r] - 1) % 8 + 1
            for (c in 0..lastCol) bb[r][c] = bb[r][c].inv() and 0xFF
            bb[r][lastCol] = bb[r][lastCol] xor (0xFF ushr lastBits)
        }
    }

    /** bitbuffer_search: where [pat] (its first [plen] bits) starts at or after [start], else the row length. */
    fun search(r: Int, start: Int, pat: IntArray, plen: Int): Int {
        val len = n[r]
        var ipos = start
        var ppos = 0
        while (ipos < len && ppos < plen) {
            if (bit(r, ipos) == (pat[ppos shr 3] shr (7 - (ppos and 7))) and 1) {
                ppos++; ipos++
                if (ppos == plen) return ipos - plen
            } else { ipos -= ppos; ipos++; ppos = 0 }
        }
        return len
    }

    fun extract(r: Int, pos: Int, nbits: Int): IntArray {
        val out = IntArray((nbits + 7) / 8 + 1)
        for (i in 0 until nbits) if (bit(r, pos + i) == 1) out[i shr 3] = out[i shr 3] or (0x80 ushr (i and 7))
        return out
    }

    fun repeats(r: Int): Int {
        var c = 0
        for (j in 0 until rows()) {
            if (n[j] != n[r]) continue
            var same = true
            var i = 0
            while (i < (n[r] + 7) / 8 && same) { same = byteAt(j, i) == byteAt(r, i); i++ }
            if (same) c++
        }
        return c
    }

    fun findRepeatedRow(minRepeats: Int, minBits: Int): Int {
        for (i in 0 until rows()) if (n[i] >= minBits && repeats(i) >= minRepeats) return i
        return -1
    }

    companion object {
        const val MAX_ROWS = 50
        const val MAX_BITS = 4096
        const val ROW_BYTES = MAX_BITS / 8 + 64
    }
}

object Ism {
    // ---------------------------------------------------------------- bit_util.c

    fun crc8(m: IntArray, n: Int, poly: Int, init: Int, off: Int = 0): Int {
        var r = init
        for (i in 0 until n) { r = r xor m[off + i]; repeat(8) { r = if (r and 0x80 != 0) ((r shl 1) xor poly) and 0xFF else (r shl 1) and 0xFF } }
        return r
    }

    fun crc16(m: IntArray, n: Int, poly: Int, init: Int, off: Int = 0): Int {
        var r = init
        for (i in 0 until n) { r = r xor (m[off + i] shl 8); repeat(8) { r = if (r and 0x8000 != 0) ((r shl 1) xor poly) and 0xFFFF else (r shl 1) and 0xFFFF } }
        return r
    }

    fun lfsrDigest8(m: IntArray, n: Int, gen: Int, key0: Int): Int {
        var sum = 0
        var key = key0
        for (k in 0 until n) for (i in 7 downTo 0) {
            if ((m[k] shr i) and 1 != 0) sum = sum xor key
            key = if (key and 1 != 0) (key shr 1) xor gen else key shr 1
        }
        return sum and 0xFF
    }

    fun lfsrDigest8Reflect(m: IntArray, n: Int, gen: Int, key0: Int): Int {
        var sum = 0
        var key = key0
        for (k in n - 1 downTo 0) for (i in 0 until 8) {
            if ((m[k] shr i) and 1 != 0) sum = sum xor key
            key = if (key and 0x80 != 0) ((key shl 1) xor gen) and 0xFF else (key shl 1) and 0xFF
        }
        return sum and 0xFF
    }

    fun lfsrDigest16(m: IntArray, n: Int, gen: Int, key0: Int, off: Int = 0): Int {
        var sum = 0
        var key = key0
        for (k in 0 until n) for (i in 7 downTo 0) {
            if ((m[off + k] shr i) and 1 != 0) sum = sum xor key
            key = if (key and 1 != 0) (key shr 1) xor gen else key shr 1
        }
        return sum and 0xFFFF
    }

    private fun addBytes(m: IntArray, n: Int, off: Int = 0): Int { var r = 0; for (i in 0 until n) r += m[off + i]; return r }
    private fun xorBytes(m: IntArray, n: Int): Int { var r = 0; for (i in 0 until n) r = r xor m[i]; return r }
    private fun parity8(b: Int): Int { var x = b; x = x xor (x shr 4); x = x xor (x shr 2); x = x xor (x shr 1); return x and 1 }
    private fun parityBytes(m: IntArray, n: Int, off: Int): Int { var r = 0; for (i in 0 until n) r = r xor parity8(m[off + i]); return r }
    private fun reflect4(x0: Int): Int { var x = ((x0 and 0xCC) shr 2) or ((x0 and 0x33) shl 2); x = ((x and 0xAA) shr 1) or ((x and 0x55) shl 1); return x and 0xFF }
    private fun s16(v: Int) = v.toShort().toInt()
    private fun inv8(v: Int) = v.inv() and 0xFF
    private fun hex(v: Int, digits: Int) = "%0${digits}x".format(v)
    private fun pat(vararg b: Int) = b

    /** bitbuffer_manchester_decode: pairs 01 → 1, 10 → 0, up to the first pair without a transition. */
    fun manchester(inp: IsmBits, row: Int, start: Int, out: IsmBits, max: Int): Int {
        var len = inp.n[row]
        if (max > 0 && len > start + max * 2) len = start + max * 2
        var i = start
        while (i < len) {
            val b1 = inp.bit(row, i++)
            val b2 = inp.bit(row, i++)
            if (b1 == b2) break
            out.addBit(b2)
        }
        return i
    }

    // ---------------------------------------------------------------- pulse_slicer.c

    private enum class Mod { OokPwm, OokPpm, OokPcm, OokMcZero, FskPcm, FskPwm }
    private class Timing(val s: Float, val l: Float, val reset: Float, val gap: Float = 0f, val sync: Float = 0f, val tol: Float = 0f)

    /** The bit width the PCM slicer measured, for the decoders that tell two rates apart. */
    private var pcmBitUs = 0f

    private fun slicePcm(t: IsmTrain, d: Timing, emit: (IsmBits) -> Unit) {
        val p = t.p
        val sl = d.s
        val tol = if (d.tol > 0) d.tol else d.l / 4
        var tw = sl
        var minCount = 12
        var pre = 0
        var n = 0
        while (n < p.size) { // the exact bit width from a preamble of single bits
            var w = 0f
            var c = 0
            while (n < p.size && (p[n].pulseUs / tw + 0.5f).toInt() == 1 && (p[n].gapUs / tw + 0.5f).toInt() == 1) { w += p[n].pulseUs + p[n].gapUs; c += 2; n++ }
            if (c >= minCount) { tw = w / c; minCount = c; pre = c }
            n++
        }
        if (pre == 0) { // or measured over all single and double bits
            var w = 0f
            var c = 0
            for (q in p) {
                if (abs(q.pulseUs - sl) <= tol) { w += q.pulseUs; c += 1 }
                if (abs(q.pulseUs - 2 * sl) <= tol) { w += q.pulseUs; c += 2 }
                if (abs(q.gapUs - sl) <= tol) { w += q.gapUs; c += 1 }
                if (abs(q.gapUs - 2 * sl) <= tol) { w += q.gapUs; c += 2 }
            }
            if (c > 20) tw = w / c
        }
        pcmBitUs = tw
        val gapLimit = if (d.gap > 0) d.gap else d.reset
        val maxZeros = (gapLimit / tw).toInt()
        val bits = IsmBits()
        for (k in p.indices) {
            val highs = (p[k].pulseUs / tw + 0.5f).toInt()
            val lows = minOf((p[k].gapUs / tw + 0.5f).toInt(), maxZeros)
            repeat(highs) { bits.addBit(1) }
            repeat(lows) { bits.addBit(0) }
            if (p[k].gapUs > gapLimit && p[k].gapUs <= d.reset) bits.addRow()
            if ((k == p.size - 1 || p[k].gapUs > d.reset) && bits.hasData()) { emit(bits); bits.clear() }
        }
    }

    private fun slicePpm(t: IsmTrain, d: Timing, emit: (IsmBits) -> Unit) {
        val zl: Float; val zu: Float; val ol: Float; val ou: Float
        var syl = 0f; var syu = 0f
        if (d.tol > 0) {
            zl = d.s - d.tol; zu = d.s + d.tol; ol = d.l - d.tol; ou = d.l + d.tol
            if (d.sync > 0) { syl = d.sync - d.tol; syu = d.sync + d.tol }
        } else { zl = 0f; zu = (d.s + d.l) / 2 + 1; ol = zu - 1; ou = if (d.gap > 0) d.gap else d.reset }
        val bits = IsmBits()
        val p = t.p
        for (k in p.indices) {
            val g = p[k].gapUs
            when {
                g > zl && g < zu -> bits.addBit(0)
                g > ol && g < ou -> bits.addBit(1)
                g > syl && g < syu -> bits.addSync()
                g < d.reset -> bits.addRow()
            }
            if ((k == p.size - 1 || g >= d.reset) && bits.hasData()) { emit(bits); bits.clear() }
        }
    }

    private fun slicePwm(t: IsmTrain, d: Timing, emit: (IsmBits) -> Unit) {
        var ol: Float; var ou: Float; var zl: Float; var zu: Float
        var syl = 0f; var syu = 0f
        val s = d.s; val l = d.l; val sy = d.sync
        when {
            d.tol > 0 -> { ol = s - d.tol; ou = s + d.tol; zl = l - d.tol; zu = l + d.tol; if (sy > 0) { syl = sy - d.tol; syu = sy + d.tol } }
            sy <= 0 -> { ol = 0f; ou = (s + l) / 2 + 1; zl = ou - 1; zu = 1e9f }
            sy < s -> { syl = 0f; syu = (sy + s) / 2 + 1; ol = syu - 1; ou = (s + l) / 2 + 1; zl = ou - 1; zu = 1e9f }
            sy < l -> { ol = 0f; ou = (s + sy) / 2 + 1; syl = ou - 1; syu = (sy + l) / 2 + 1; zl = syu - 1; zu = 1e9f }
            else -> { ol = 0f; ou = (s + l) / 2 + 1; zl = ou - 1; zu = (l + sy) / 2 + 1; syl = zu - 1; syu = 1e9f }
        }
        val bits = IsmBits()
        val p = t.p
        for (k in p.indices) {
            val w = p[k].pulseUs
            when {
                w > ol && w < ou -> bits.addBit(1)
                w > zl && w < zu -> bits.addBit(0)
                w > syl && w < syu -> bits.addSync()
                w <= ol -> Unit
                else -> bits.addRow()
            }
            if (k == p.size - 1 || p[k].gapUs > d.reset) { if (bits.hasData()) emit(bits); bits.clear() }
            else if (d.gap > 0 && p[k].gapUs > d.gap && bits.n.last() > 0) bits.addRow()
        }
    }

    private fun sliceMcZero(t: IsmTrain, d: Timing, emit: (IsmBits) -> Unit) {
        val s = d.s
        var since = 0f
        val bits = IsmBits()
        bits.addBit(0)
        val p = t.p
        for (k in p.indices) {
            if (p[k].pulseUs + since > s * 1.5f) { bits.addBit(1); since = 0f } else since += p[k].pulseUs
            if (k == p.size - 1 || p[k].gapUs > d.reset) {
                emit(bits); bits.clear(); bits.addBit(0); since = 0f
            } else if (p[k].gapUs + since > s * 1.5f) { bits.addBit(0); since = 0f } else since += p[k].gapUs
        }
    }

    // ---------------------------------------------------------------- the devices

    private class Builder(val model: String, val kind: String, val id: String, val mic: String) {
        var channel: Int? = null
        var channelName: String? = null
        var battery: Boolean? = null
        val fields = ArrayList<IsmField>()
        fun add(name: String, v: Double, unit: String, dec: Int = 1) { fields += IsmField(name, unit, v, dec) }
        fun text(name: String, t: String) { fields += IsmField(name, "", 0.0, 0, t) }
        fun build() = IsmReading(model, kind, id, mic, channel, channelName, battery, fields.toList())
    }

    // nexus.c: Nexus, FreeTec NC-7345, NX-3980, Solight TE82S, TFA 30.3209 (36 bits PPM, no check: a row repeated 3 times)
    private fun nexus(bits: IsmBits, out: Out) {
        val r = bits.findRepeatedRow(3, 36)
        if (r < 0 || bits.n[r] > 37) return
        val b = bits.row(r)
        if (b[3] and 0xf0 != 0xf0) return
        if ((b[0] == 0 && b[2] == 0 && b[3] == 0) || (b[0] == 0xff && b[2] == 0xff && b[3] == 0xff)) return
        if (b[1] and 0x30 == 0x30) return
        val c = intArrayOf(b[0], b[1], b[2], b[3] and 0xf0, ((b[3] and 0x0f) shl 4) or ((b[4] and 0xf0) shr 4))
        if (crc8(c, 5, 0x31, 0x6c) == 0) return // a Rubicson frame
        val hum = ((b[3] and 0x0F) shl 4) or (b[4] shr 4)
        if (hum > 100) return
        val traw = s16((b[1] shl 12) or (b[2] shl 4))
        val r2 = Builder(if (hum != 0) "Nexus-TH" else "Nexus-T", "Weather", "${b[0]}", "REPEAT")
        r2.channel = ((b[1] and 0x30) shr 4) + 1
        r2.battery = b[1] and 0x80 != 0
        r2.add("Temperature", (traw shr 4) * 0.1, "C")
        if (hum != 0) r2.add("Humidity", hum.toDouble(), "%", 0)
        out += r2.build()
    }

    // rubicson.c: Rubicson, TFA 30.3197, InFactory PT-310 (36 bits PPM, CRC-8 poly 0x31 init 0x6c)
    private fun rubicson(bits: IsmBits, out: Out) {
        val r = bits.findRepeatedRow(3, 36)
        if (r < 0 || bits.n[r] < 36 || bits.n[r] > 38) return
        val b = bits.row(r)
        if (b[3] and 0xf0 != 0xf0) return
        val c = intArrayOf(b[0], b[1], b[2], b[3] and 0xf0, ((b[3] and 0x0f) shl 4) or ((b[4] and 0xf0) shr 4))
        if (crc8(c, 5, 0x31, 0x6c) != 0) return
        val traw = s16((b[1] shl 12) or (b[2] shl 4))
        val r2 = Builder("Rubicson-Temperature", "Weather", "${b[0]}", "CRC")
        r2.channel = ((b[1] and 0x30) shr 4) + 1
        r2.battery = b[1] and 0x80 != 0
        r2.add("Temperature", (traw shr 4) * 0.1, "C")
        out += r2.build()
    }

    // lacrosse_tx141x.c: LaCrosse TX141-Bv2, TX141TH-Bv2, TX141-Bv3, TX141W, TFA 30.3221.02 (PWM)
    private fun tx141(bits: IsmBits, out: Out) {
        bits.invert()
        var r = bits.findRepeatedRow(if (bits.rows() > 5) 5 else 3, 32)
        if (r < 0) r = bits.findRepeatedRow(2, 64)
        if (r < 0 && bits.rows() <= 4) {
            for (row in 0 until bits.rows()) {
                if ((bits.n[row] == 40 || bits.n[row] == 41) && lfsrDigest8Reflect(bits.row(row), 4, 0x31, 0xf4) == bits.row(row)[4]) { r = row; break }
            }
        }
        if (r < 0) return
        val nb = bits.n[r]
        val dev = when {
            nb >= 64 -> "W"
            nb > 41 -> return
            nb >= 41 -> { if (bits.rows() > 12) return; "TH" }
            nb >= 40 -> "TH"
            nb >= 37 -> "B37"
            nb == 32 -> "B32"
            else -> "BV3"
        }
        val b = bits.row(r)
        if (dev == "W") {
            if ((b[0] shr 3) != 0x01 || crc8(b, 8, 0x31, 0x00) != 0) return
            val id = ((b[0] and 0x07) shl 16) or (b[1] shl 8) or b[2]
            val type = b[3] and 0x0f
            val traw = (b[4] shl 4) or (b[5] shr 4)
            val hum = ((b[5] and 0x0f) shl 8) or b[6]
            val r2 = Builder("LaCrosse-TX141W", "Weather", hex(id, 5), "CRC")
            r2.channel = (b[3] and 0x30) shr 4; r2.battery = (b[3] shr 7) == 0
            when (type) {
                1 -> { r2.add("Temperature", (traw - 500) * 0.1, "C"); r2.add("Humidity", hum.toDouble(), "%", 0) }
                2 -> { r2.add("Wind speed", traw * 0.1, "km/h"); r2.add("Wind direction", hum.toDouble(), "deg", 0) }
                else -> return
            }
            out += r2.build()
            return
        }
        val id = b[0]
        val batLow = if (dev == "TH") b[1] shr 7 else if (b[1] shr 7 == 0) 1 else 0
        val traw = ((b[1] and 0x0F) shl 8) or b[2]
        val tc = (traw - 500) * 0.1
        val hum = if (dev == "TH") b[3] else 0
        if (id == 0 || (dev == "TH" && (hum == 0 || hum > 100)) || tc < -40.0 || tc > 140.0) return
        if (dev == "TH" && lfsrDigest8Reflect(b, 4, 0x31, 0xf4) != b[4]) return
        val model = when (dev) { "B32" -> "LaCrosse-TX141B"; "B37" -> "LaCrosse-TX141Bv2"; "BV3" -> "LaCrosse-TX141Bv3"; else -> "LaCrosse-TX141THBv2" }
        val r2 = Builder(model, "Weather", hex(id, 2), if (dev == "TH") "CRC" else "REPEAT")
        if (dev != "B32") r2.channel = (b[1] and 0x30) shr 4
        r2.battery = batLow == 0
        r2.add("Temperature", tc, "C")
        if (dev == "TH") r2.add("Humidity", hum.toDouble(), "%", 0)
        out += r2.build()
    }

    // acurite.c: 592TXR tower and 5-in-1 (PWM, add checksum and byte parity)
    private val acurite5n1Dir = intArrayOf(14, 11, 13, 12, 15, 10, 0, 9, 3, 6, 4, 5, 2, 7, 1, 8)
    private fun acuriteCheck(bb: IntArray, browlen: Int, explen: Int): Boolean {
        if (browlen < 6 || browlen < explen) return false
        if (addBytes(bb, explen - 1) and 0xff != bb[explen - 1]) return false
        if (parityBytes(bb, explen - 3, 2) != 0) return false
        return ((bb[0] and 0xC0) shr 6) != 1
    }
    private fun acurite(bits: IsmBits, out: Out) {
        bits.invert()
        val chan = arrayOf("C", "E", "B", "A")
        for (r in 0 until bits.rows()) {
            val browlen = bits.n[r] / 8
            if (browlen < 6 || browlen > 10) continue
            val bb = bits.row(r)
            if (bb[0] == 0 && bb[1] == 0 && bb[2] == 0 && bb[browlen - 1] == 0) continue
            val mt = bb[2] and 0x3f
            if (mt == 0x04) {
                if (!acuriteCheck(bb, browlen, 7)) continue
                val hum = bb[3] and 0x7f
                if (hum > 100 && hum != 127) continue
                val tc = ((((bb[4] and 0x7F) shl 7) or (bb[5] and 0x7F)) - 1000) * 0.1
                if (tc < -40 || tc > 70) continue
                val r2 = Builder("Acurite-Tower", "Weather", "${((bb[0] and 0x3f) shl 8) or bb[1]}", "CHECKSUM")
                r2.channelName = chan[(bb[0] and 0xC0) shr 6]
                r2.battery = bb[2] and 0x40 != 0
                r2.add("Temperature", tc, "C")
                if (hum != 127) r2.add("Humidity", hum.toDouble(), "%", 0)
                out += r2.build()
            } else if (mt == 0x31 || mt == 0x38) {
                if (!acuriteCheck(bb, browlen, 8)) continue
                val r2 = Builder("Acurite-5n1", "Weather", "${((bb[0] and 0x0f) shl 8) or bb[1]}", "CHECKSUM")
                r2.channelName = chan[(bb[0] and 0xC0) shr 6]
                r2.battery = bb[2] and 0x40 != 0
                val wraw = ((bb[3] and 0x1F) shl 3) or ((bb[4] and 0x70) shr 4)
                r2.add("Wind speed", if (wraw > 0) wraw * 0.8278 + 1.0 else 0.0, "km/h")
                if (mt == 0x31) {
                    r2.add("Wind direction", acurite5n1Dir[bb[4] and 0x0f] * 22.5, "deg")
                    r2.add("Rain", (((bb[5] and 0x7f) shl 7) or (bb[6] and 0x7F)) * 0.254, "mm", 1)
                } else {
                    val tf = ((((bb[4] and 0x0F) shl 7) or (bb[5] and 0x7F)) - 400) * 0.1
                    val hum = bb[6] and 0x7f
                    if (tf < -40.0 || tf > 158.0 || hum > 100) continue
                    r2.add("Temperature", (tf - 32) / 1.8, "C")
                    r2.add("Humidity", hum.toDouble(), "%", 0)
                }
                out += r2.build()
            }
        }
    }

    // fineoffset_wh1080.c: WH1080 / WH3080 weather message (OOK PWM, CRC-8 poly 0x31 init 0xff)
    private val wh1080Dir = intArrayOf(0, 23, 45, 68, 90, 113, 135, 158, 180, 203, 225, 248, 270, 293, 315, 338)
    private fun wh1080(bits: IsmBits, out: Out) {
        if (bits.rows() != 1) return
        val br: IntArray = when {
            bits.n[0] in 88..99 -> bits.row(0)
            bits.n[0] == 87 -> { val e = bits.extract(0, 7, 80); IntArray(16).also { it[0] = (bits.row(0)[0] shr 1) or 0x80; e.copyInto(it, 1, 0, 10) } }
            else -> return
        }
        if (br[0] != 0xff || crc8(br, 11, 0x31, 0xff) != 0) return
        if ((br[1] shr 4) != 0x0a) return
        val r2 = Builder("Fineoffset-WHx080", "Weather", "${((br[1] shl 4) and 0xf0) or (br[2] shr 4)}", "CRC")
        r2.battery = (br[9] shr 4) != 1
        r2.add("Temperature", ((((br[2] and 0x03) shl 8) or br[3]) - 400) * 0.1, "C")
        r2.add("Humidity", br[4].toDouble(), "%", 0)
        r2.add("Wind direction", wh1080Dir[br[9] and 0x0f].toDouble(), "deg", 0)
        r2.add("Wind speed", br[5] * 0.34 * 3.6, "km/h")
        r2.add("Wind gust", br[6] * 0.34 * 3.6, "km/h")
        r2.add("Rain", (((br[7] and 0x0f) shl 8) or br[8]) * 0.3, "mm")
        out += r2.build()
    }

    // ambient_weather.c: Ambient Weather F007TH, F012TH, TFA 30.3208.02, SwitchDoc F016TH (Manchester, LFSR digest)
    private fun f007th(bits: IsmBits, out: Out) {
        val pre = pat(0x01, 0x45)
        val inv = pat(0xfd, 0x45)
        fun one(row: Int, pos: Int): Boolean {
            val b = bits.extract(row, pos, 48)
            if ((lfsrDigest8(b, 5, 0x98, 0x3e) xor 0x64) != b[5]) return false
            val hum = b[4]
            val traw = ((b[2] and 0x0f) shl 8) or b[3]
            val tf = (traw - 400) * 0.1
            if (hum > 100 || tf < -40.0 || tf >= 344.0) return false
            val r2 = Builder("Ambientweather-F007TH", "Weather", "${b[1]}", "CRC")
            r2.channel = ((b[2] and 0x70) shr 4) + 1
            r2.battery = b[2] and 0x80 == 0
            r2.add("Temperature", (tf - 32) / 1.8, "C")
            r2.add("Humidity", hum.toDouble(), "%", 0)
            out += r2.build()
            return true
        }
        for (row in 0 until bits.rows()) {
            var pos = 0
            while (true) { pos = bits.search(row, pos, pre, 12); if (pos + 8 + 48 > bits.n[row]) break; if (one(row, pos + 8)) return; pos += 16 }
            pos = 0
            while (true) { pos = bits.search(row, pos, inv, 12); if (pos + 8 + 48 > bits.n[row]) break; if (one(row, pos + 8)) return; pos += 15 }
        }
    }

    // oregon_scientific.c: v2.1 THGR122N / THGR228N / THGR968 and v3 THGR810 (Manchester, sum of nibbles)
    private fun osTemp(m: IntArray): Double {
        var t = (((m[5] shr 4) * 100) + ((m[4] and 0x0f) * 10) + ((m[4] shr 4) and 0x0f)) / 10.0
        t += (m[5] and 0x07) * 100.0
        return if (m[5] and 0x08 != 0) -t else t
    }
    private fun osHum(m: IntArray) = ((m[6] and 0x0f) * 10) + (m[6] shr 4)
    private fun osChecksum(m: IntArray, idx: Int): Boolean {
        var sum = 0
        var i = 0
        while (i < idx - 1) { sum += (m[i shr 1] shr 4) + (m[i shr 1] and 0x0f); i += 2 }
        val chk: Int
        if (idx and 1 != 0) { sum += m[idx shr 1] shr 4; chk = (m[idx shr 1] and 0x0f) or (m[(idx + 1) shr 1] and 0xf0) }
        else chk = (m[idx shr 1] shr 4) or ((m[idx shr 1] and 0x0f) shl 4)
        return (sum and 0xff) == chk
    }
    private fun oregon(bits: IsmBits, out: Out) {
        val b = bits.row(0)
        if ((b[1] == 0x55 && b[2] == 0x55) || (b[1] == 0xAA && b[2] == 0xAA)) {
            val db = IsmBits()
            val sync = (b[3].toLong() shl 24) or (b[4].toLong() shl 16) or (b[5].toLong() shl 8) or b[6].toLong()
            var found = false
            for (k in 0 until 8) {
                val mask = 0xffff0000L ushr k
                val p1 = 0x55990000L ushr k
                val p2 = 0xaa990000L ushr k
                if (sync and mask != p1 && sync and mask != p2) continue
                manchester(bits, 0, k + 40, db, 173)
                found = true
                break
            }
            if (!found) return
            val n = db.n[0]
            val m = db.row(0)
            for (i in 0 until (n + 7) / 8) m[i] = reflect4(m[i])
            val sid = (m[0] shl 8) or m[1]
            if (sid == 0x1d20 || sid == 0x1d30) {
                if (!((n == 68 || n == 76) && osChecksum(m, 15))) return
                val model = if (sid == 0x1d30) "Oregon-THGR968" else if (n == 76) "Oregon-THGR122N" else "Oregon-THGR228N"
                val r2 = Builder(model, "Weather", "${(m[2] and 0x0f) or (m[3] and 0xf0)}", "CHECKSUM")
                r2.channel = (m[2] shr 4) and 0x0f
                r2.battery = (m[3] shr 2) and 1 == 0
                r2.add("Temperature", osTemp(m), "C", 2)
                r2.add("Humidity", osHum(m).toDouble(), "%", 0)
                out += r2.build()
            }
            return
        }
        if (((b[0] and 0xf) != 0x0f || b[1] != 0xff || (b[2] and 0xc0) != 0xc0) && ((b[0] and 0xf) != 0x00 || b[1] != 0x00 || (b[2] and 0xc0) != 0x00)) return
        val pos = bits.search(0, 0, pat(0x00, 0x05), 16) + 16
        if (pos > bits.n[0] || bits.n[0] - pos < 56) return
        val len = bits.n[0] - pos
        if (len > 44 * 8) return
        val m = bits.extract(0, pos, len).copyOf(46)
        for (i in 0 until (len + 7) / 8) m[i] = reflect4(m[i])
        val sid = (m[0] shl 8) or m[1]
        if ((sid and 0xf0ff) == 0xf024 || sid == 0xf8b4) {
            if (!osChecksum(m, 15)) return
            if (((m[5] shr 4) and 0x0F) > 9 || (m[4] and 0x0F) > 9 || ((m[4] shr 4) and 0x0F) > 9 || (m[6] and 0x0F) > 9 || ((m[6] shr 4) and 0x0F) > 9) return
            val tc = osTemp(m)
            if (tc > 70 || tc < -50) return
            val r2 = Builder("Oregon-THGR810", "Weather", "${(m[2] and 0x0f) or (m[3] and 0xf0)}", "CHECKSUM")
            r2.channel = (m[2] shr 4) and 0x0f
            r2.battery = (m[3] shr 2) and 1 == 0
            r2.add("Temperature", tc, "C", 2)
            r2.add("Humidity", osHum(m).toDouble(), "%", 0)
            out += r2.build()
        }
    }

    // acurite.c: Acurite 606TX / Technoline TX960 (PPM, 32 bits repeated, lfsr digest 0x98 / 0xf1)
    private fun acurite606(bits: IsmBits, out: Out) {
        val r = bits.findRepeatedRow(3, 32)
        if (r < 0 || bits.n[r] > 33) return
        val b = bits.row(r)
        if (b[0] == 0 && b[1] == 0 && b[2] == 0 && b[3] == 0) return
        if (lfsrDigest8(b, 3, 0x98, 0xf1) != b[3]) return
        val traw = s16((b[1] shl 12) or (b[2] shl 4))
        val r2 = Builder("Acurite-606TX", "Weather", "${b[0]}", "CHECKSUM")
        r2.channel = ((b[1] and 0x30) shr 4) + 1
        r2.battery = b[1] and 0x80 != 0
        r2.add("Temperature", (traw shr 4) * 0.1, "C")
        out += r2.build()
    }

    // acurite.c: Acurite 609TXC (40 bits: id, status + temperature, temperature, humidity, sum)
    private fun acurite609(bits: IsmBits, out: Out) {
        for (row in 0 until bits.rows()) {
            if (bits.n[row] != 40) continue
            val b = bits.row(row)
            val sum = b[0] + b[1] + b[2] + b[3]
            if (sum == 0 || sum and 0xff != b[4]) continue
            val traw = s16(((b[1] and 0x0f) shl 12) or (b[2] shl 4))
            if (b[3] > 100) continue
            val r2 = Builder("Acurite-609TXC", "Weather", "${b[0]}", "CHECKSUM")
            r2.battery = b[1] and 0x80 == 0
            r2.add("Temperature", (traw shr 4) * 0.1, "C")
            r2.add("Humidity", b[3].toDouble(), "%", 0)
            out += r2.build()
            return
        }
    }

    // prologue.c: Prologue, FreeTec NC-7104 (36 bits PPM, a row repeated 4 times; type nibble 9 or 5)
    private fun prologue(bits: IsmBits, out: Out) {
        if (bits.n[0] != 0 && bits.n[0] <= 8) return
        val r = bits.findRepeatedRow(4, 36)
        if (r < 0 || bits.n[r] > 37) return
        val b = bits.row(r)
        val type = b[0] shr 4
        if (type != 9 && type != 5) return
        val hum = ((b[3] and 0x0f) shl 4) or (b[4] shr 4)
        val traw = s16((b[2] shl 8) or (b[3] and 0xf0)) shr 4
        val r2 = Builder("Prologue-TH", "Weather", "${((b[0] and 0x0f) shl 4) or (b[1] shr 4)}", "REPEAT")
        r2.channel = (b[1] and 3) + 1
        r2.add("Temperature", traw * 0.1, "C")
        if (hum != 0xCC) r2.add("Humidity", hum.toDouble(), "%", 0)
        out += r2.build()
    }

    private fun crc4(m: IntArray, n: Int, poly: Int, init: Int): Int {
        var rem = init shl 4
        val p = poly shl 4
        for (i in 0 until n) { rem = rem xor m[i]; repeat(8) { rem = if (rem and 0x80 != 0) ((rem shl 1) xor p) and 0xff else (rem shl 1) and 0xff } }
        return (rem shr 4) and 0x0f
    }

    // kedsum.c: Kedsum-TH, Pearl NC-7415 (5 empty sync rows, then 42-bit rows repeated 4 times, CRC-4)
    private fun kedsum(bits: IsmBits, out: Out) {
        if (bits.rows() < 5) return
        for (i in 0 until 5) if (bits.n[i] != 0) return
        val r = bits.findRepeatedRow(4, 42)
        if (r < 0 || bits.n[r] != 42) return
        val b = bits.extract(r, 2, 40)
        if ((crc4(b, 4, 0x3, 0) xor (b[4] shr 4)) != (b[4] and 0xf)) return
        val traw = ((b[2] and 0x0f) shl 8) or (b[2] and 0xf0) or (b[1] and 0x0f)
        val hum = ((b[3] and 0x0f) shl 4) or ((b[3] and 0xf0) shr 4)
        val r2 = Builder("Kedsum-TH", "Weather", "${b[0]}", "CRC")
        r2.channel = ((b[1] and 0x30) shr 4) + 1
        r2.battery = (b[1] shr 6) == 2
        r2.add("Temperature", ((traw - 900) * 0.1 - 32.0) / 1.8, "C")
        r2.add("Humidity", hum.toDouble(), "%", 0)
        out += r2.build()
    }

    // gt_wt_02.c: Globaltronics GT-WT-02 (37 or 39 bits; nibble sum modulo 64)
    private fun gtWt02(bits: IsmBits, out: Out) {
        if (bits.rows() < 2) return
        for (row in 0 until bits.rows()) {
            val b = when (bits.n[row]) { 39 -> bits.extract(row, 2, 37); 37 -> bits.extract(row, 0, 37); else -> continue }
            if (b[0] or b[1] or b[2] or b[3] or b[4] == 0) continue
            val sum = (b[0] shr 4) + (b[0] and 0xF) + (b[1] shr 4) + (b[1] and 0xF) + (b[2] shr 4) + (b[2] and 0xF) + (b[3] shr 4) + (b[3] and 0xe)
            val chk = ((b[3] and 1) shl 5) + (b[4] shr 3)
            if (sum and 0x3F != chk) continue
            val channel = (b[1] shr 4) and 3
            val tc = (s16(((b[1] and 0x0f) shl 12) or (b[2] shl 4)) shr 4) * 0.1
            if (channel > 2 || tc < -20 || tc > 60) continue
            var hum = b[3] shr 1
            if (hum != 10 && hum != 110 && (hum < 20 || hum > 90)) continue
            if (hum == 10) hum = 0 else if (hum == 110) hum = 100
            val r2 = Builder("GT-WT02", "Weather", "${b[0]}", "CHECKSUM")
            r2.channel = channel + 1
            r2.battery = (b[1] shr 7) and 1 == 0
            r2.add("Temperature", tc, "C")
            r2.add("Humidity", hum.toDouble(), "%", 0)
            out += r2.build()
            return
        }
    }

    // bresser_3ch.c: Bresser Thermo-/Hygro-Sensor 3CH (40 bits, inverted, sum check, Fahrenheit)
    private fun bresser3ch(bits: IsmBits, out: Out) {
        val r = bits.findRepeatedRow(3, 40)
        if (r < 0 || bits.n[r] > 42) return
        val b = IntArray(5) { inv8(bits.row(r)[it]) }
        if ((b[0] + b[1] + b[2] + b[3] - b[4]) and 0xFF != 0) return
        val channel = (b[1] and 0x30) shr 4
        val hum = b[3]
        val tf = ((((b[1] and 0x0F) shl 8) + b[2]) - 900) * 0.1
        if (channel == 0 || hum > 100 || tf < -20.0 || tf > 160.0) return
        val r2 = Builder("Bresser-3CH", "Weather", "${b[0]}", "CHECKSUM")
        r2.channel = channel
        r2.battery = b[1] and 0x80 == 0
        r2.add("Temperature", (tf - 32.0) / 1.8, "C")
        r2.add("Humidity", hum.toDouble(), "%", 0)
        out += r2.build()
    }

    // fineoffset.c: WH2 (48 bits, 0xFF preamble), WH2A (55, 0xFE), WH5 (47, 0xFE), Telldus Proove (49): CRC-8 poly 0x31
    private fun wh2(bits: IsmBits, out: Out) {
        val nb = bits.n[0]
        val b0 = bits.byteAt(0, 0)
        val b: IntArray
        val m: String
        when {
            nb == 48 && b0 == 0xFF -> { b = bits.extract(0, 8, 40).copyOf(6); m = "WH2" }
            nb == 55 && b0 == 0xFE -> { b = bits.extract(0, 7, 48).copyOf(6); m = if (b[3] == 0xff) "TFA" else "WH2A" }
            nb == 47 && b0 == 0xFE -> { b = bits.extract(0, 7, 40).copyOf(6); m = "WH5" }
            nb == 49 && b0 == 0xFF && bits.byteAt(0, 1) and 0x80 != 0 -> { b = bits.extract(0, 9, 40).copyOf(6); m = "TP" }
            else -> return
        }
        if (b[4] != crc8(b, 4, 0x31, 0)) return
        if (m == "TFA" && addBytes(b, 5) and 0xff != b[5]) return
        if ((b[0] shr 4) != 4) return
        val id = ((b[0] and 0x0F) shl 4) or ((b[1] and 0xF0) shr 4)
        var temp = ((b[1] and 0x0F) shl 8) or b[2]
        var low = false
        when {
            m == "TFA" -> { low = temp and 0x800 != 0; temp = (temp and 0x7FF) - 400 }
            m == "WH5" -> temp -= 400
            temp and 0x800 != 0 -> temp = -(temp and 0x7FF)
        }
        val t = temp * 0.1
        if (m == "WH5" && (t < -40.0 || t > 60.0)) return
        val name = when (m) { "WH2" -> "Fineoffset-WH2"; "WH2A" -> "Fineoffset-WH2A"; "WH5" -> "Fineoffset-WH5"; "TP" -> "Fineoffset-TelldusProove"; else -> "TFA-303225" }
        val r2 = Builder(name, "Weather", "$id", "CRC")
        if (m == "TFA") r2.battery = !low
        r2.add("Temperature", t, "C")
        if (b[3] != 0xff) r2.add("Humidity", b[3].toDouble(), "%", 0)
        out += r2.build()
    }

    // thermopro_tp11.c: ThermoPro TP11 (32 / 33 bits PPM, lfsr digest reflect 0x51 / 0x04)
    private fun tp11(bits: IsmBits, out: Out) {
        val r = bits.findRepeatedRow(2, 32)
        if (r < 0 || bits.n[r] > 33) return
        val b = bits.row(r)
        if (lfsrDigest8Reflect(b, 3, 0x51, 0x04) != b[3]) return
        if ((b[0] or b[1] or b[2] or b[3]) == 0 || (b[0] and b[1] and b[2] and b[3]) == 0xff) return
        val r2 = Builder("Thermopro-TP11", "Weather", "${(b[0] shl 4) or (b[1] shr 4)}", "CRC")
        r2.add("Temperature", ((((b[1] and 0x0f) shl 8) or b[2]) - 200) * 0.1, "C")
        out += r2.build()
    }

    // thermopro_tp12.c: ThermoPro TP08 / TP12 / TP20 (41 bits PPM, two probes)
    private fun tp12(bits: IsmBits, out: Out) {
        val need = if (bits.rows() > 5) 5 else 2
        var r = -1
        var i = 0
        while (i < bits.rows() && r < 0) {
            if (bits.n[i] == 41) {
                var c = 0
                for (j in 0 until bits.rows()) {
                    if (bits.n[j] < 40) continue
                    var same = true
                    var k = 0
                    while (k < 40 && same) { same = bits.bit(i, k) == bits.bit(j, k); k++ }
                    if (same) c++
                }
                if (c >= need) r = i
            }
            i++
        }
        if (r < 0) return
        val b = bits.row(r)
        if ((b[0] or b[1] or b[2] or b[3]) == 0) return
        if (lfsrDigest8Reflect(b, 4, 0x51, 0x04) != b[4]) return
        val raw1 = ((b[2] and 0xf0) shl 4) or b[1]
        val raw2 = ((b[2] and 0x0f) shl 8) or b[3]
        val r2 = Builder("Thermopro-TP12", "Weather", "${b[0]}", "CRC")
        r2.add("Temperature 1", (raw1 - 200) * 0.1, "C")
        r2.add("Temperature 2", (raw2 - 200) * 0.1, "C")
        out += r2.build()
    }

    // ert_scm.c: Itron ERT Standard Consumption Message (96 bits Manchester, CRC-16 poly 0x6F63 over bytes 2..11)
    private fun ertScm(bits: IsmBits, out: Out) {
        if (bits.n[0] != 96) return
        val b = bits.row(0)
        if (b[0] == 0 && b[1] == 0 && b[2] == 0 && b[3] == 0) return
        if (crc16(b, 10, 0x6F63, 0, off = 2) != 0) return
        val id = ((b[2] and 0x06).toLong() shl 23) or (b[7].toLong() shl 16) or (b[8].toLong() shl 8) or b[9].toLong()
        val r2 = Builder("ERT-SCM", "Meter", "$id", "CRC")
        r2.add("ERT type", ((b[3] shr 2) and 0x0F).toDouble(), "", 0)
        r2.add("Consumption", ((b[4] shl 16) or (b[5] shl 8) or b[6]).toDouble(), "", 0)
        out += r2.build()
    }

    // solight_te44.c: Solight TE44 / TE66, EMOS E0107T (the Rubicson frame, 37 bits at 972 / 1932 µs)
    private fun solightTe44(bits: IsmBits, out: Out) {
        val r = bits.findRepeatedRow(3, 36)
        if (r < 0 || bits.n[r] != 37) return
        val b = bits.row(r)
        if (b[3] and 0xf0 != 0xf0) return
        val t = intArrayOf(b[0], b[1], b[2], b[3] and 0xf0, ((b[3] and 0x0f) shl 4) or ((b[4] and 0xf0) shr 4))
        if (crc8(t, 5, 0x31, 0x6c) != 0) return
        val traw = s16((b[1] shl 12) or (b[2] shl 4))
        val r2 = Builder("Solight-TE44", "Weather", "${b[0]}", "CRC")
        r2.channel = ((b[1] and 0x30) shr 4) + 1
        r2.add("Temperature", (traw shr 4) * 0.1, "C", 2)
        out += r2.build()
    }

    // kerui.c: Kerui PIR / contact sensors (25-bit rows, at least 9 identical; bits inverted; 20-bit id, 4-bit command)
    private fun kerui(bits: IsmBits, out: Out) {
        val r = bits.findRepeatedRow(9, 25)
        if (r < 0 || bits.n[r] != 25) return
        val p = bits.row(r)
        if (p[0] == 0 && p[1] == 0 && p[2] == 0) return
        val id = (inv8(p[0]) shl 12) or (inv8(p[1]) shl 4) or (inv8(p[2]) shr 4)
        val cmd = inv8(p[2]) and 0x0F
        val st = when (cmd) { 0xa -> "motion"; 0xe -> "open"; 0x7 -> "close"; 0xb -> "tamper"; 0x5 -> "water"; 0xf -> "battery low"; else -> return }
        val r2 = Builder("Kerui-Security", "Sensor", hex(id, 5), "REPEAT")
        r2.text("State", st)
        if (cmd == 0xf) r2.battery = false
        out += r2.build()
    }

    // springfield.c: Springfield PreciseTemp temperature and soil moisture (36 / 37 bits PPM, nibble xor)
    private fun springfield(bits: IsmBits, out: Out) {
        val r = bits.findRepeatedRow(3, 36)
        if (r < 0 || (bits.n[r] != 36 && bits.n[r] != 37)) return
        val b = bits.row(r)
        val all = (b[0].toLong() shl 24) or (b[1].toLong() shl 16) or (b[2].toLong() shl 8) or b[3].toLong()
        if (all == 0xffffffffL || all == 0L) return
        var chk = xorBytes(b, 4)
        chk = (chk shr 4) xor (chk and 0x0f)
        if (chk != 0) return
        val tc = (s16(((b[1] and 0x0f) shl 12) or (b[2] shl 4)) shr 4) * 0.1
        val moist = (b[3] shr 4) * 10
        if (tc < -30 || tc > 70 || moist > 100) return
        val r2 = Builder("Springfield-Soil", "Weather", "${b[0]}", "CHECKSUM")
        r2.channel = ((b[1] shr 4) and 3) + 1
        r2.battery = b[1] and 0x80 == 0
        r2.add("Temperature", tc, "C")
        r2.add("Moisture", moist.toDouble(), "%", 0)
        out += r2.build()
    }

    // honeywell.c: Honeywell / Ademco 345 MHz door and window sensors (Manchester at 136 µs, CRC-16)
    private fun honeywell(bits: IsmBits, out: Out) {
        if (bits.n[0] < 120) return
        val pre = pat(0x55, 0x55, 0x56)
        var pos = 0
        val len = bits.n[0]
        while (true) {
            pos = bits.search(0, pos, pre, 24)
            if (pos + 24 >= len) break
            val d = IsmBits()
            manchester(bits, 0, pos + 24, d, 96)
            pos += 1
            if (d.n[0] < 48) continue
            val b = d.extract(0, 0, minOf(d.n[0], 80))
            val channel = b[0] shr 4
            val id = ((b[0] and 0xf) shl 16) or (b[1] shl 8) or b[2]
            val crc = (b[4] shl 8) or b[5]
            if (id == 0 && crc == 0) continue
            val poly = if (channel == 0x2 || channel == 0x4 || channel == 0x9 || channel == 0xA || channel == 0xC) 0x8050 else 0x8005
            if (crc != crc16(b, 4, poly, 0)) continue
            val ev = b[3]
            val r2 = Builder("Honeywell-Security", "Sensor", hex(id, 5), "CRC")
            r2.channel = channel
            r2.battery = ev and 0x08 == 0
            r2.text("State", if (ev and 0x80 != 0) "open" else "closed")
            if (ev and 0x40 != 0) r2.text("Tamper", "yes")
            if (ev and 0x10 != 0) r2.text("Alarm", "yes")
            out += r2.build()
            return
        }
    }

    // generic_remote.c: EV1527 / PT2262 fixed-code remotes (25 bits PWM; the code must come twice in the burst)
    private fun genericRemote(bits: IsmBits, out: Out, seen: MutableList<Int>) {
        if (bits.n[0] != 25) return
        val b = intArrayOf(inv8(bits.row(0)[0]), inv8(bits.row(0)[1]), inv8(bits.row(0)[2]), bits.row(0)[3])
        if (b[3] and 0x80 == 0 || (b[0] == 0 && b[1] == 0) || b[2] == 0) return
        val full = (b[0] shl 16) or (b[1] shl 8) or b[2]
        if (full !in seen) { seen += full; return }
        val id = "${(b[0] shl 8) or b[1]}"
        if (out.any { it.model == "Generic-Remote" && it.id == id }) return
        val tri = StringBuilder()
        var i = 22
        while (i >= 0) { tri.append("0ZX1"[(full shr i) and 3]); i -= 2 }
        val r2 = Builder("Generic-Remote", "Remote", id, "REPEAT")
        r2.text("Code", hex(full, 6))
        r2.text("Tri-state", tri.toString())
        out += r2.build()
    }

    // schraeder.c: Schrader TPMS (68 bits Manchester, CRC-8 poly 0x07 init 0xf0)
    private fun schrader(bits: IsmBits, out: Out) {
        if (bits.n[0] != 68) return
        val b = bits.extract(0, 4, 64)
        if (b[7] != crc8(b, 7, 0x07, 0xf0)) return
        val r2 = Builder("Schrader", "TPMS", "%07X".format(((b[1] and 0x0F) shl 24) or (b[2] shl 16) or (b[3] shl 8) or b[4]), "CRC")
        r2.add("Pressure", b[5] * 2.5, "kPa")
        r2.add("Temperature", (b[6] - 50).toDouble(), "C", 0)
        out += r2.build()
    }

    // schraeder.c: Schrader EG53MA4 TPMS (120 bits, sum check, Fahrenheit)
    private fun schraderEg53(bits: IsmBits, out: Out) {
        if (bits.n[0] != 120) return
        val b = bits.extract(0, 40, 80)
        if (b[1] == 0 && b[2] == 0 && b[4] == 0 && b[5] == 0 && b[7] == 0 && b[8] == 0) return
        if (addBytes(b, 9) and 0xff != b[9]) return
        val r2 = Builder("Schrader-EG53MA4", "TPMS", "%06X".format((b[4] shl 16) or (b[5] shl 8) or b[6]), "CHECKSUM")
        r2.add("Pressure", b[7] * 2.5, "kPa")
        r2.add("Temperature", (b[8] - 32) / 1.8, "C", 0)
        out += r2.build()
    }

    // bresser_5in1.c: Bresser 5-in-1 weather station (FSK, the message followed by its inverted copy)
    private fun bresser5in1(bits: IsmBits, out: Out) {
        if (bits.rows() != 1 || bits.n[0] < 248 || bits.n[0] > 440) return
        var sp = bits.search(0, 0, pat(0xaa, 0xaa, 0xaa, 0x2d, 0xd4), 40)
        if (sp == bits.n[0]) return
        sp += 40
        var len = bits.n[0] - sp
        if ((len + 7) / 8 < 26) return
        len = minOf(len, 26 * 8)
        val msg = bits.extract(0, sp, len).copyOf(26)
        for (c in 0 until 13) if ((msg[c] xor msg[c + 13]) != 0xff) return
        var ones = 0
        for (i in 14 until 26) ones += Integer.bitCount(msg[i])
        if (ones != msg[13]) return
        val tok = (msg[20] and 0x0f) <= 9
        val hok = (msg[22] and 0x0f) <= 9
        var traw = (msg[20] and 0x0f) + ((msg[20] and 0xf0) shr 4) * 10 + (msg[21] and 0x0f) * 100
        if (msg[25] and 0x0f != 0) traw = -traw
        val hum = (msg[22] and 0x0f) + ((msg[22] and 0xf0) shr 4) * 10
        val stype = msg[15] and 0x7f
        val rain = ((msg[23] and 0x0f) + ((msg[23] and 0xf0) shr 4) * 10 + (msg[24] and 0x0f) * 100 + ((msg[24] and 0xf0) shr 4) * 1000) * 0.1
        val gauge = stype in 0x39..0x3b
        val r2 = Builder(if (gauge) "Bresser-ProRainGauge" else "Bresser-5in1", "Weather", "${msg[14]}", "CHECKSUM")
        r2.battery = msg[25] and 0x80 == 0
        if (tok) r2.add("Temperature", traw * 0.1, "C")
        if (gauge) { r2.add("Rain", rain * 2.5, "mm"); out += r2.build(); return }
        if (hok) r2.add("Humidity", hum.toDouble(), "%", 0)
        r2.add("Wind gust", (((msg[17] and 0x0f) shl 8) + msg[16]) * 0.1 * 3.6, "km/h")
        r2.add("Wind speed", ((msg[18] and 0x0f) + ((msg[18] and 0xf0) shr 4) * 10 + (msg[19] and 0x0f) * 100) * 0.1 * 3.6, "km/h")
        r2.add("Wind direction", ((msg[17] and 0xf0) shr 4) * 22.5, "deg", 0)
        r2.add("Rain", rain, "mm")
        out += r2.build()
    }

    // bresser_6in1.c: Bresser 6-in-1 and 3-in-1 sensors (FSK, LFSR-16 digest and byte sum)
    private val bresserMoist = intArrayOf(0, 7, 13, 20, 27, 33, 40, 47, 53, 60, 67, 73, 80, 87, 93, 99)
    private fun bresser6in1(bits: IsmBits, out: Out) {
        if (bits.rows() != 1 || bits.n[0] < 160 || bits.n[0] > 440) return
        val sp = bits.search(0, 0, pat(0xaa, 0xaa, 0x2d, 0xd4), 32) + 32
        if (sp >= bits.n[0] || bits.n[0] - sp < 18 * 8) return
        val msg = bits.extract(0, sp, 144)
        if (((msg[0] shl 8) or msg[1]) != lfsrDigest16(msg, 15, 0x8810, 0x5412, off = 2)) return
        if (addBytes(msg, 16, 2) and 0xff != 0xff) return
        val id = (msg[2].toLong() shl 24) or (msg[3].toLong() shl 16) or (msg[4].toLong() shl 8) or msg[5].toLong()
        val stype = msg[6] shr 4
        val chan = msg[6] and 7
        val tok = msg[12] <= 0x99 && (msg[13] and 0xf0) <= 0x90
        val traw = (msg[12] shr 4) * 100 + (msg[12] and 0x0f) * 10 + (msg[13] shr 4)
        var tc = if ((msg[13] shr 3) and 1 != 0) (traw - 1000) * 0.1 else traw * 0.1
        if (tc < -50.0) tc = -traw * 0.1
        val hum = (msg[14] shr 4) * 10 + (msg[14] and 0x0f)
        var uvok = (msg[16] and 0x0f) == 0 && inv8(msg[15]) <= 0x99 && (inv8(msg[16]) and 0xf0) <= 0x90
        val uvraw = ((inv8(msg[15]) and 0xf0) shr 4) * 100 + (inv8(msg[15]) and 0x0f) * 10 + ((inv8(msg[16]) and 0xf0) shr 4)
        val w7 = inv8(msg[7]); val w8 = inv8(msg[8]); val w9 = inv8(msg[9])
        var wok = w7 <= 0x99 && w8 <= 0x99 && w9 <= 0x99
        val gust = (w7 shr 4) * 100 + (w7 and 0x0f) * 10 + (w8 shr 4)
        val wavg = (w9 shr 4) * 100 + (w9 and 0x0f) * 10 + (w8 and 0x0f)
        val wdir = ((msg[10] and 0xf0) shr 4) * 100 + (msg[10] and 0x0f) * 10 + ((msg[11] and 0xf0) shr 4)
        val r12 = inv8(msg[12]); val r13 = inv8(msg[13]); val r14 = inv8(msg[14])
        val rok = msg[16] and 1 != 0
        val rraw = (r12 shr 4) * 100000 + (r12 and 0x0f) * 10000 + (r13 shr 4) * 1000 + (r13 and 0x0f) * 100 + (r14 shr 4) * 10 + (r14 and 0x0f)
        if (stype == 2 || stype == 4) { wok = false; uvok = false }
        val mo = if (stype == 4 && tok && hum in 1..16) bresserMoist[hum - 1] else -1
        val r2 = Builder("Bresser-6in1", "Weather", hex(id.toInt(), 8), "CRC")
        r2.channel = chan
        if (!rok) r2.battery = (msg[13] shr 1) and 1 != 0
        if (tok) r2.add("Temperature", tc, "C")
        if (tok && mo < 0) r2.add("Humidity", hum.toDouble(), "%", 0)
        if (mo >= 0) r2.add("Moisture", mo.toDouble(), "%", 0)
        if (wok) { r2.add("Wind gust", gust * 0.1 * 3.6, "km/h"); r2.add("Wind speed", wavg * 0.1 * 3.6, "km/h"); r2.add("Wind direction", wdir.toDouble(), "deg", 0) }
        if (rok) r2.add("Rain", rraw * 0.1, "mm")
        if (uvok) r2.add("UV index", uvraw * 0.1, "")
        out += r2.build()
    }

    // bresser_7in1.c: Bresser 7-in-1, air quality and CO2 sensors (FSK, whitened with 0xaa, LFSR-16 digest)
    private fun bresser7in1(bits: IsmBits, out: Out) {
        if (bits.rows() != 1 || bits.n[0] < 160) return
        val sp = bits.search(0, 0, pat(0xaa, 0xaa, 0xaa, 0x2d, 0xd4), 40) + 40
        if (sp >= bits.n[0] || sp + 21 * 8 >= bits.n[0]) return
        val msg = bits.extract(0, sp, 200).copyOf(25)
        if (msg[21] == 0x00) return
        val stype = msg[6] shr 4
        val chan = msg[6] and 0x07
        for (i in msg.indices) msg[i] = msg[i] xor 0xaa
        if ((((msg[0] shl 8) or msg[1]) xor lfsrDigest16(msg, 23, 0x8810, 0xba95, off = 2)) != 0x6df1) return
        val id = (msg[2] shl 8) or msg[3]
        val batLow = (msg[15] and 0x06) == 0x06
        fun bcd(vararg nibbles: Int): Int = nibbles.fold(0) { a, v -> a * 10 + v }
        when (stype) {
            1, 12, 13 -> {
                val wdir = bcd(msg[4] shr 4, msg[4] and 0x0f, msg[5] shr 4)
                val wg = bcd(msg[7] shr 4, msg[7] and 0x0f, msg[8] shr 4)
                val wa = bcd(msg[8] and 0x0f, msg[9] shr 4, msg[9] and 0x0f)
                val rr = bcd(msg[10] shr 4, msg[10] and 0x0f, msg[11] shr 4, msg[11] and 0x0f, msg[12] shr 4, msg[12] and 0x0f)
                val traw = bcd(msg[14] shr 4, msg[14] and 0x0f, msg[15] shr 4)
                val tc = if (traw > 600) (traw - 1000) * 0.1 else traw * 0.1
                val hum = bcd(msg[16] shr 4, msg[16] and 0x0f)
                val lux = bcd(msg[17] shr 4, msg[17] and 0x0f, msg[18] shr 4, msg[18] and 0x0f, msg[19] shr 4, msg[19] and 0x0f)
                val uv = bcd(msg[20] shr 4, msg[20] and 0x0f, msg[21] shr 4)
                val r2 = Builder("Bresser-7in1", "Weather", "$id", "CRC")
                r2.battery = !batLow
                r2.add("Temperature", tc, "C")
                r2.add("Humidity", hum.toDouble(), "%", 0)
                if (stype != 12) { r2.add("Wind gust", wg * 0.1 * 3.6, "km/h"); r2.add("Wind speed", wa * 0.1 * 3.6, "km/h"); r2.add("Wind direction", wdir.toDouble(), "deg", 0) }
                r2.add("Rain", rr * 0.1, "mm")
                if (stype != 12) { r2.add("Light", lux.toDouble(), "lux", 0); r2.add("UV index", uv * 0.1, "") }
                out += r2.build()
            }
            8 -> {
                val r2 = Builder("Bresser-PM", "Sensor", "$id", "CRC")
                r2.channel = chan; r2.battery = !batLow
                if (msg[10] and 0x0f != 0x0f) r2.add("PM2.5", bcd(msg[10] and 0x0f, msg[11] shr 4, msg[11] and 0x0f, msg[12] shr 4).toDouble(), "µg/m³", 0)
                if (msg[12] and 0x0f != 0x0f) r2.add("PM10", bcd(msg[12] and 0x0f, msg[13] shr 4, msg[13] and 0x0f, msg[14] shr 4).toDouble(), "µg/m³", 0)
                out += r2.build()
            }
            10, 11 -> {
                val r2 = Builder(if (stype == 10) "Bresser-CO2" else "Bresser-HCHOVOC", "Sensor", "$id", "CRC")
                r2.channel = chan; r2.battery = !batLow
                val v = bcd((msg[4] and 0xf0) shr 4, msg[4] and 0x0f, (msg[5] and 0xf0) shr 4, msg[5] and 0x0f)
                if (msg[5] and 0x0f != 0x0f) r2.add(if (stype == 10) "CO2" else "Formaldehyde", v.toDouble(), if (stype == 10) "ppm" else "ppb", 0)
                out += r2.build()
            }
        }
    }

    // lacrosse_tx35.c: LaCrosse TX29-IT (55 µs) and TX35DTH-IT (105 µs), TFA 30.3159 / 30.3155 (FSK, CRC-8 0x31)
    private fun tx29(bits: IsmBits, out: Out, tx35: Boolean) {
        val w = if (tx35) 105f else 55f
        if (abs(pcmBitUs - w) > w * 0.2f) return
        for (r in 0 until bits.rows()) {
            val sp = bits.search(r, 0, pat(0xa2, 0xdd, 0x49), 24)
            if (sp >= bits.n[r]) continue
            val b = bits.extract(r, sp + 20, 40)
            if (b[4] != crc8(b, 4, 0x31, 0x00)) continue
            var id = ((b[0] and 0x0f) shl 2) or (b[1] shr 6)
            val tc = 10 * (b[1] and 0x0f) + ((b[2] shr 4) and 0x0f) + 0.1 * (b[2] and 0x0f) - 40.0
            val hum = b[3] and 0x7f
            if (hum == 0x7d) id += 0x40
            val r2 = Builder(if (tx35) "LaCrosse-TX35DTHIT" else "LaCrosse-TX29IT", "Weather", "$id", "CRC")
            r2.battery = (b[3] shr 7) == 0
            r2.add("Temperature", tc, "C")
            if (hum != 0x6a && hum != 0x7d) r2.add("Humidity", hum.toDouble(), "%", 0)
            out += r2.build()
        }
    }

    // fineoffset.c: Fine Offset / Ecowitt WH24, WH65B, WS69 (FSK, CRC-8 0x31 and sum)
    private fun wh24(bits: IsmBits, out: Out) {
        val nb = bits.n[0]
        if (nb < 190 || nb > 268) return
        val off = bits.search(0, 0, pat(0xAA, 0x2D, 0xD4), 24) + 24
        if (off + 17 * 8 > nb) return
        var type = if (nb - off - 17 * 8 < 8) (if (off < 61) 24 else 65) else 65
        if (nb > 215) type = 69
        val b = bits.extract(0, off, 200)
        if (b[0] != 0x24) return
        if (crc8(b, 16, 0x31, 0x00) != 0 || addBytes(b, 16) and 0xff != b[16]) return
        val wdir = b[2] or ((b[3] and 0x80) shl 1)
        val traw = ((b[3] and 0x07) shl 8) or b[4]
        val hum = b[5]
        val wsraw = b[6] or ((b[3] and 0x10) shl 4)
        val gust = b[7]
        val rain = (b[8] shl 8) or b[9]
        val light = (b[12] shl 16) or (b[13] shl 8) or b[14]
        val wf = if (type == 24) 1.12 else 0.51
        val rf = if (type == 24) 0.3 else 0.254
        val r2 = Builder(if (type == 24) "Fineoffset-WH24" else if (type == 65) "Fineoffset-WH65B" else "Fineoffset-WS69", "Weather", "${b[1]}", "CRC")
        r2.battery = (b[3] and 0x08) == 0
        if (traw != 0x7ff) r2.add("Temperature", (traw - 400) * 0.1, "C")
        if (hum != 0xff) r2.add("Humidity", hum.toDouble(), "%", 0)
        if (wdir != 0x1ff) r2.add("Wind direction", wdir.toDouble(), "deg", 0)
        if (wsraw != 0x1ff) r2.add("Wind speed", wsraw * 0.125 * wf * 3.6, "km/h")
        if (gust != 0xff) r2.add("Wind gust", gust * wf * 3.6, "km/h")
        r2.add("Rain", rain * rf, "mm")
        if (light != 0xffffff) r2.add("Light", light * 0.1, "lux", 0)
        out += r2.build()
    }

    // efergy_e2_classic.c: Efergy e2 classic (FSK PWM, 64 bits, sum check)
    private fun efergy(bits: IsmBits, out: Out) {
        if (bits.rows() != 1) return
        if (bits.n[0] < 64 || bits.n[0] > 65) return
        val by = IntArray(8) { bits.byteAt(0, it) }
        if ((by[0] and 0xf0) != 0xf0 && (by[0] and 0xf0) != 0x00) return
        if (by[0] and 0xf0 != 0) for (i in by.indices) by[i] = inv8(by[i])
        if (by.count { it == 0 } > 5) return
        val sum = addBytes(by, 7)
        if (sum == 0 || sum and 0xff != by[7]) return
        val fact = (-(by[6].toByte().toInt()) + 15) and 0xFF
        if (fact < 7 || fact > 23) return
        val r2 = Builder("Efergy-e2CT", "Energy", "${(by[2] shl 8) or by[1]}", "CHECKSUM")
        r2.battery = by[3] and 0x40 != 0
        r2.add("Current", ((by[4] shl 8) or by[5]).toDouble() / (1 shl fact), "A", 2)
        out += r2.build()
    }

    // current_cost.c: CurrentCost TX / EnviR (FSK, Manchester after a sync pattern)
    private fun currentCost(bits: IsmBits, out: Out) {
        bits.invert()
        var envir = false
        var sp = bits.search(0, 0, pat(0x55, 0x55, 0x55, 0x55, 0xa4, 0x57), 48)
        if (sp + 47 + 112 <= bits.n[0]) { envir = true; sp += 47 } else {
            sp = bits.search(0, 0, pat(0xcc, 0xcc, 0xcc, 0xce, 0x91, 0x5d), 45)
            if (sp + 45 + 112 > bits.n[0]) return
            sp += 45
        }
        val pk = IsmBits()
        manchester(bits, 0, sp, pk, 0)
        if (pk.n[0] < 64) return
        val b = pk.row(0)
        val dev = ((b[0] and 0x0f) shl 8) or b[1]
        if (b[0] and 0xf0 == 0) {
            val r2 = Builder(if (envir) "CurrentCost-EnviR" else "CurrentCost-TX", "Energy", "$dev", "MANCHESTER")
            r2.add("Power 0", (if (b[2] and 0x80 != 0) ((b[2] and 0x7F) shl 8) or b[3] else 0).toDouble(), "W", 0)
            r2.add("Power 1", (if (b[4] and 0x80 != 0) ((b[4] and 0x7F) shl 8) or b[5] else 0).toDouble(), "W", 0)
            r2.add("Power 2", (if (b[6] and 0x80 != 0) ((b[6] and 0x7F) shl 8) or b[7] else 0).toDouble(), "W", 0)
            out += r2.build()
        }
    }

    // ---------------------------------------------------------------- the table

    private class Proto(val name: String, val fsk: Boolean, val mod: Mod, val t: Timing, val fn: (IsmBits, Out, MutableList<Int>) -> Unit)

    private fun p(name: String, mod: Mod, t: Timing, fn: (IsmBits, Out) -> Unit) =
        Proto(name, mod == Mod.FskPcm || mod == Mod.FskPwm, mod, t) { b, o, _ -> fn(b, o) }

    private val protos: List<Proto> = listOf(
        p("Nexus / FreeTec / TFA 30.3209", Mod.OokPpm, Timing(1000f, 2000f, 5000f, 3000f), ::nexus),
        p("Rubicson / TFA 30.3197", Mod.OokPpm, Timing(1000f, 2000f, 4800f, 3000f), ::rubicson),
        p("LaCrosse TX141 / TFA 30.3221", Mod.OokPwm, Timing(208f, 417f, 1700f, 625f, 833f), ::tx141),
        p("Acurite 592TXR, 5-in-1", Mod.OokPwm, Timing(220f, 408f, 4000f, 500f, 620f), ::acurite),
        p("Fine Offset WH1080 / WH3080", Mod.OokPwm, Timing(544f, 1524f, 2800f), ::wh1080),
        p("Ambient Weather F007TH / TFA 30.3208", Mod.OokMcZero, Timing(500f, 0f, 2400f), ::f007th),
        p("Oregon Scientific v2.1 / v3", Mod.OokMcZero, Timing(440f, 0f, 2400f), ::oregon),
        p("Acurite 606TX / Technoline TX960", Mod.OokPpm, Timing(2000f, 4000f, 10000f, 7000f), ::acurite606),
        p("Acurite 609TXC", Mod.OokPpm, Timing(1000f, 2000f, 10000f, 3000f), ::acurite609),
        p("Prologue / FreeTec NC-7104", Mod.OokPpm, Timing(2000f, 4000f, 10000f, 7000f), ::prologue),
        p("Kedsum / Pearl NC-7415", Mod.OokPpm, Timing(2000f, 4000f, 9400f, 4400f), ::kedsum),
        p("Globaltronics GT-WT-02", Mod.OokPpm, Timing(2500f, 5000f, 12000f, 8000f), ::gtWt02),
        p("Bresser 3CH", Mod.OokPwm, Timing(250f, 500f, 1250f, 625f, 750f), ::bresser3ch),
        p("Fine Offset WH2 / WH2A / WH5, Telldus", Mod.OokPwm, Timing(500f, 1500f, 1200f, tol = 160f), ::wh2),
        p("ThermoPro TP11", Mod.OokPpm, Timing(500f, 1500f, 4000f, 2000f), ::tp11),
        p("ThermoPro TP08 / TP12 / TP20", Mod.OokPpm, Timing(500f, 1500f, 4000f, 2000f), ::tp12),
        p("Itron ERT SCM", Mod.OokMcZero, Timing(30f, 0f, 64f), ::ertScm),
        p("Solight TE44 / EMOS E0107T", Mod.OokPpm, Timing(972f, 1932f, 6000f, 3000f), ::solightTe44),
        p("Honeywell / Ademco 345 MHz", Mod.OokPcm, Timing(136f, 136f, 408f), ::honeywell),
        p("Kerui PIR / contact", Mod.OokPwm, Timing(420f, 960f, 9900f, 1100f, tol = 160f), ::kerui),
        p("Springfield soil moisture", Mod.OokPpm, Timing(2000f, 4000f, 9200f, 5000f), ::springfield),
        p("Schrader TPMS", Mod.OokMcZero, Timing(120f, 0f, 480f), ::schrader),
        p("Schrader EG53MA4 TPMS", Mod.OokMcZero, Timing(123f, 0f, 300f), ::schraderEg53),
        Proto("EV1527 / PT2262 remotes", false, Mod.OokPwm, Timing(464f, 1404f, 1800f, tol = 200f), ::genericRemote),
        p("Bresser 5-in-1", Mod.FskPcm, Timing(124f, 124f, 25000f), ::bresser5in1),
        p("Bresser 6-in-1", Mod.FskPcm, Timing(124f, 124f, 25000f), ::bresser6in1),
        p("Bresser 7-in-1 / air quality", Mod.FskPcm, Timing(124f, 124f, 25000f), ::bresser7in1),
        p("LaCrosse TX29-IT / TFA 30.3159", Mod.FskPcm, Timing(55f, 55f, 4000f)) { b, o -> tx29(b, o, false) },
        p("LaCrosse TX35DTH-IT / TFA 30.3155", Mod.FskPcm, Timing(105f, 105f, 4000f)) { b, o -> tx29(b, o, true) },
        p("Fine Offset / Ecowitt WH24 / WH65", Mod.FskPcm, Timing(58f, 58f, 20000f), ::wh24),
        p("Efergy e2 classic", Mod.FskPwm, Timing(64f, 136f, 400f, 200f, 500f), ::efergy),
        p("CurrentCost TX / EnviR", Mod.FskPcm, Timing(250f, 250f, 8000f), ::currentCost),
    )

    /** The supported devices, for the screen. */
    val deviceNames: List<String> get() = protos.map { it.name }

    /** Every device that matches the train, one reading each. */
    fun decode(t: IsmTrain): List<IsmReading> {
        if (t.p.isEmpty() || t.p.any { !it.pulseUs.isFinite() || !it.gapUs.isFinite() || it.pulseUs < 0 || it.gapUs < 0 }) return emptyList()
        val out = ArrayList<IsmReading>()
        val seen = ArrayList<Int>()
        for (pr in protos) {
            if (pr.fsk != t.fsk) continue
            val emit: (IsmBits) -> Unit = { b -> runCatching { pr.fn(b.copy(), out, seen) } }
            when (pr.mod) {
                Mod.OokPwm, Mod.FskPwm -> slicePwm(t, pr.t, emit)
                Mod.OokPpm -> slicePpm(t, pr.t, emit)
                Mod.OokPcm, Mod.FskPcm -> slicePcm(t, pr.t, emit)
                Mod.OokMcZero -> sliceMcZero(t, pr.t, emit)
            }
        }
        // A device repeats its packet within a burst: keep one of each.
        return out.distinctBy { listOf(it.model, it.id, it.channel, it.fields) }
    }

    /** Rounds a reading's value for display. */
    fun fmt(f: IsmField): String = f.text ?: if (f.decimals == 0) "${f.value.roundToInt()}" else "%.${f.decimals}f".format(java.util.Locale.getDefault(), f.value)
}

/**
 * HackRF bytes at 2 MS/s tuned fs/4 below the band in, sensor readings out. The channel (±120 kHz) comes down to
 * 500 kS/s; a squelch on its power cuts bursts out of a ring buffer. In each burst the signal's centre frequency is
 * found and brought to 0 Hz, then it is read as FSK when the carrier stays up while the frequency takes two values,
 * else as OOK from the envelope, into a train of marks and spaces for the protocol table (as OnAir's ism_rx).
 */
class IsmReceiver(private val onReading: (IsmReading, Double) -> Unit, private val onUnknown: (IsmTrain) -> Unit = {}) {
    private val d1 = Decimator(4, 0.06, taps = 48)
    private val inRe = FloatArray(32768)
    private val inIm = FloatArray(32768)
    private val demod = IsmDemodulator(RATE, onReading, onUnknown)

    fun feed(buf: ByteArray, len: Int) {
        val k = d1.process(buf, len, inRe, inIm, 0)
        demod.feed(inRe, inIm, k)
    }

    companion object {
        const val SAMPLE_RATE = 2_000_000
        const val RATE = 500_000.0
    }
}

class IsmDemodulator(private val rd: Double = 500_000.0, private val onReading: (IsmReading, Double) -> Unit, private val onUnknown: (IsmTrain) -> Unit = {}) {
    private val ringSize = Integer.highestOneBit((rd * 1.2).toInt()) * 2
    private val ringRe = FloatArray(ringSize)
    private val ringIm = FloatArray(ringSize)
    private val mask = ringSize - 1
    private var total = 0L
    private var power = 0f
    private var floor = -1f
    private var inBurst = false
    private var bStart = 0L
    private var bLast = 0L
    private val hang = (0.025 * rd).toLong()
    private val pre = (0.001 * rd).toLong()
    private val post = (0.0005 * rd).toLong()
    private val maxBurst = (ringSize * 0.75).toLong()

    fun feed(re: FloatArray, im: FloatArray, n: Int) {
        for (i in 0 until n) {
            val r = re[i]
            val q = im[i]
            ringRe[(total and mask.toLong()).toInt()] = r
            ringIm[(total and mask.toLong()).toInt()] = q
            total++
            val p = r * r + q * q
            power += (p - power) * 0.05f
            if (floor < 0) floor = power
            if (total < WARMUP) { floor += (power - floor) * 0.05f; continue }
            if (!inBurst) floor += (power - floor) * 2e-4f
            val loud = power > floor * SQUELCH && power > 1e-7f
            if (loud) {
                if (!inBurst) { inBurst = true; bStart = total }
                bLast = total
                if (bLast - bStart > maxBurst) { inBurst = false; floor = power } // a carrier that came up: into the floor
            } else if (inBurst && total - bLast > hang) {
                inBurst = false
                burst()
            }
        }
    }

    private fun burst() {
        var s0 = maxOf(0L, bStart - pre)
        val s1 = minOf(total, bLast + post)
        if (s0 < total - ringSize) s0 = total - ringSize
        val m = (s1 - s0).toInt()
        if (m < 200) return
        val yr = FloatArray(m) { ringRe[((s0 + it) and mask.toLong()).toInt()] }
        val yi = FloatArray(m) { ringIm[((s0 + it) and mask.toLong()).toInt()] }
        // The signal's centre: the mean frequency over the loud samples (the carrier for OOK, between the tones for FSK).
        var maxP = 0f
        for (k in 0 until m) maxP = maxOf(maxP, yr[k] * yr[k] + yi[k] * yi[k])
        var sx = 0.0
        var sy = 0.0
        for (k in 1 until m) {
            if (yr[k] * yr[k] + yi[k] * yi[k] < maxP * 0.25f) continue
            sx += yr[k] * yr[k - 1] + yi[k] * yi[k - 1]
            sy += yi[k] * yr[k - 1] - yr[k] * yi[k - 1]
        }
        val w = -kotlin.math.atan2(sy, sx)
        var ph = 0.0
        for (k in 0 until m) {
            val c = kotlin.math.cos(ph).toFloat(); val s = kotlin.math.sin(ph).toFloat()
            val a = yr[k] * c - yi[k] * s
            yi[k] = yr[k] * s + yi[k] * c; yr[k] = a
            ph += w
        }
        demod(yr, yi, m)
    }

    private fun demod(yr: FloatArray, yi: FloatArray, m: Int) {
        val w1 = maxOf(2, (rd * 20e-6).roundToInt())
        val w2 = maxOf(1, (rd * 8e-6).roundToInt())
        // Two envelopes: the wide one (|y| over 8 µs) shows an FSK carrier; the narrow one (y averaged over 20 µs, about
        // ±25 kHz, then |.| over 8 µs) slices OOK.
        val magW = FloatArray(m)
        val magN = FloatArray(m)
        run {
            var car = 0.0; var cai = 0.0
            val a = FloatArray(m); val b = FloatArray(m)
            for (k in 0 until m) {
                car += yr[k]; cai += yi[k]
                if (k >= w1) { car -= yr[k - w1]; cai -= yi[k - w1] }
                a[k] = (kotlin.math.sqrt(car * car + cai * cai) / minOf(k + 1, w1)).toFloat()
                b[k] = kotlin.math.sqrt(yr[k] * yr[k] + yi[k] * yi[k])
            }
            var accA = 0.0; var accB = 0.0
            for (k in 0 until m) {
                accA += a[k]; accB += b[k]
                if (k >= w2) { accA -= a[k - w2]; accB -= b[k - w2] }
                val n = minOf(k + 1, w2).toDouble()
                magN[k] = (accA / n).toFloat(); magW[k] = (accB / n).toFloat()
            }
        }
        val head = minOf(m / 4, (0.8 * 0.001 * rd).toInt())
        val tail = minOf(m / 4, (0.8 * 0.0005 * rd).toInt())
        val skip = 12 + w1
        if (head < skip + 16) return
        val us = 1e6 / rd

        fun slice(mag: FloatArray): ByteArray? {
            fun stats(a: Int, b: Int): Pair<Double, Double> {
                var s1 = 0.0; var s2 = 0.0
                for (k in a until b) { s1 += mag[k]; s2 += mag[k].toDouble() * mag[k] }
                val mean = s1 / (b - a)
                return mean to kotlin.math.sqrt(maxOf(0.0, s2 / (b - a) - mean * mean))
            }
            var (lo, sd) = stats(skip, head)
            if (tail > 16) { val (l2, s2) = stats(m - tail, m); if (l2 < lo) { lo = l2; sd = s2 } }
            val sm = ArrayList<Float>()
            for (k in 0 until m) if (mag[k] > lo + 6 * sd + 1e-12) sm += mag[k]
            if (sm.size < 20) return null
            sm.sort()
            val hi = sm[sm.size / 2].toDouble()
            if (hi <= lo * 1.6) return null
            val on = (lo + 0.55 * (hi - lo)).toFloat()
            val off = (lo + 0.45 * (hi - lo)).toFloat()
            val st = ByteArray(m)
            var s = 0; var cand = 0; var run = 0
            for (k in 0 until m) {
                val want = if (s != 0) (if (mag[k] >= off) 1 else 0) else (if (mag[k] > on) 1 else 0)
                if (want != s) {
                    if (want == cand) run++ else { cand = want; run = 1 }
                    if (run >= 2) { s = want; for (b in 0 until run) st[k - b] = s.toByte(); run = 0; continue }
                } else run = 0
                st[k] = s.toByte()
            }
            return st
        }

        val stW = slice(magW)
        if (stW != null) {
            var first = m; var last = 0; var nOn = 0
            for (k in 0 until m) if (stW[k].toInt() != 0) { first = minOf(first, k); last = k; nOn++ }
            if (nOn >= 10 && nOn.toDouble() / (last - first + 1) >= 0.9) {
                val f0 = FloatArray(m)
                for (k in 1 until m) f0[k] = kotlin.math.atan2(yi[k] * yr[k - 1] - yr[k] * yi[k - 1], yr[k] * yr[k - 1] + yi[k] * yi[k - 1])
                run { // median of three: discriminator clicks are single samples
                    val g = f0.copyOf()
                    for (k in 1 until m - 1) f0[k] = maxOf(minOf(g[k - 1], g[k]), minOf(maxOf(g[k - 1], g[k]), g[k + 1]))
                }
                var fr = FloatArray(m)
                var mid = 0f
                var fsk = false
                var fw = 3
                for (pass in 0..1) {
                    fr = FloatArray(m)
                    var acc = 0.0
                    for (k in 0 until m) { acc += f0[k]; if (k >= fw) acc -= f0[k - fw]; fr[k] = (acc / fw).toFloat() }
                    val v = ArrayList<Float>()
                    for (k in first + fw..last) if (stW[k].toInt() != 0) v += fr[k]
                    if (v.size <= 20) break
                    val sv = v.sorted()
                    var c1 = sv[sv.size / 10]; var c2 = sv[sv.size * 9 / 10]
                    repeat(12) {
                        val md = 0.5f * (c1 + c2)
                        var a = 0.0; var b = 0.0; var na = 0; var nb = 0
                        for (q in v) if (q < md) { a += q; na++ } else { b += q; nb++ }
                        if (na > 0) c1 = (a / na).toFloat()
                        if (nb > 0) c2 = (b / nb).toFloat()
                    }
                    mid = 0.5f * (c1 + c2)
                    val devHz = (c2 - c1) * rd / (4 * Math.PI)
                    fsk = devHz > 4e3
                    if (!fsk || pass == 1) break
                    val runs = ArrayList<Int>()
                    var a = first
                    for (k in first + 1..last) if ((fr[k] > mid) != (fr[k - 1] > mid)) { runs += k - a; a = k }
                    if (runs.size < 8) break
                    runs.sort()
                    val big = runs.filter { it * 10 >= runs[runs.size / 2] }
                    val chip = if (big.isEmpty()) 3 else big[big.size / 4]
                    val nfw = (chip / 3).coerceIn(1, 64)
                    if (nfw == fw) break
                    fw = nfw
                }
                if (fsk) {
                    val dl = fw / 2
                    fun mark(j: Int) = fr[minOf(m - 1, j + dl)] > mid
                    val split = (200e-6 * rd).toInt()
                    var k = first
                    while (k <= last) {
                        var e = k
                        var offRun = 0
                        while (e <= last) { if (stW[e].toInt() == 0) { if (++offRun > split) break } else offRun = 0; e++ }
                        val end = e - offRun
                        val p = ArrayList<IsmPulse>()
                        var j = k
                        while (j < end && !mark(j)) j++
                        while (j < end) {
                            var a = j
                            while (j < end && mark(j)) j++
                            val pl = j - a
                            a = j
                            while (j < end && !mark(j)) j++
                            p += IsmPulse((pl * us).toFloat(), ((j - a) * us).toFloat())
                        }
                        handle(IsmTrain(true, fold(p)))
                        k = e
                        while (k <= last && stW[k].toInt() == 0) k++
                    }
                    return
                }
            }
        }
        // OOK
        val st = slice(magN) ?: return
        var first = m; var last = 0; var nOn = 0
        for (k in 0 until m) if (st[k].toInt() != 0) { first = minOf(first, k); last = k; nOn++ }
        if (nOn < 10) return
        val p = ArrayList<IsmPulse>()
        var j = first
        while (j <= last) {
            var a = j
            while (j <= last && st[j].toInt() != 0) j++
            val pl = j - a
            a = j
            while (j <= last && st[j].toInt() == 0) j++
            p += IsmPulse((pl * us).toFloat(), if (j > last) 1e6f else ((j - a) * us).toFloat())
        }
        handle(IsmTrain(false, fold(p)))
    }

    /** Noise splits a pulse or a gap: anything under a third of the shortest real element joins its neighbours. */
    private fun fold(p: List<IsmPulse>): List<IsmPulse> {
        if (p.size < 4) return p
        val d = ArrayList<Float>()
        for (q in p) { d += q.pulseUs; d += q.gapUs }
        val lastGap = d.removeAt(d.lastIndex)
        val s = d.sorted()
        val med = s[s.size / 2]
        val big = s.filter { it >= 0.1f * med }
        if (big.isEmpty()) return p
        val thr = maxOf((2.5e6 / rd).toFloat(), big[big.size / 4] / 3f)
        var i = 1
        while (i + 1 < d.size) {
            if (d[i] < thr) { d[i - 1] += d[i] + d[i + 1]; d.removeAt(i); d.removeAt(i) } else i++
        }
        if (d.size >= 2 && d[0] < thr) { d.removeAt(0); d.removeAt(0) }
        d += lastGap
        return (0 until d.size / 2).map { IsmPulse(d[2 * it], d[2 * it + 1]) }
    }

    private fun handle(t: IsmTrain) {
        if (t.p.size < 3) return
        val rs = Ism.decode(t)
        if (rs.isEmpty()) { if (t.p.size >= 8) onUnknown(t); return }
        val now = System.currentTimeMillis().toDouble()
        rs.forEach { onReading(it, now) }
    }

    companion object {
        private const val WARMUP = 2048L
        /** 13 dB over the floor. */
        private const val SQUELCH = 20f
    }
}
