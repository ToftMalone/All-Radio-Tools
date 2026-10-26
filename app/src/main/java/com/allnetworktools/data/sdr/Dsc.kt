package com.allnetworktools.data.sdr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Digital Selective Calling (ITU-R M.493) on VHF channel 70, 156.525 MHz: 1200 baud FSK on FM, 1300 Hz for Y (1) and
 * 2100 Hz for B (0). Ported from OnAir (core/src/marine, GPL-3.0). A symbol is 7 bits LSB first plus 3 bits counting
 * its zeros; each one is sent twice, five places apart, and an even-parity check character closes the call.
 */
object Dsc {
    const val DISTRESS = 112
    const val ALL_SHIPS = 116
    const val GROUP = 114
    const val INDIVIDUAL = 120
    const val AREA = 102
    const val AUTOMATIC = 123
    const val EOS_ACK_RQ = 117
    const val EOS_ACK = 122
    const val EOS_OTHER = 127

    fun isEos(s: Int) = s == EOS_ACK_RQ || s == EOS_ACK || s == EOS_OTHER

    /** Phasing (M.493 §3): even slots 0 to 10 are DX 125, odd slots 1 to 15 carry RX 111 down to 104. */
    fun phasingAt(slot: Int) = when {
        slot !in 0..15 -> -2
        slot % 2 == 0 -> if (slot < 12) 125 else -2
        else -> 111 - (slot - 1) / 2
    }

    /** The 10 bits of a symbol, bit 1 (sent first) in bit 9. */
    fun bits(sym: Int): Int {
        var v = 0
        for (i in 0 until 7) v = (v shl 1) or ((sym shr i) and 1)
        return (v shl 3) or (7 - Integer.bitCount(sym and 127))
    }

    /** The symbol, or -1 when the check bits do not match. */
    fun decode(bits10: Int): Int {
        var s = 0
        for (i in 0 until 7) s = s or (((bits10 shr (9 - i)) and 1) shl i)
        return if (bits10 and 7 == 7 - Integer.bitCount(s)) s else -1
    }

    fun ecc(body: List<Int>, eos: Int) = body.fold(eos and 127) { e, s -> e xor (s and 127) }

    /** Nine digits; '~' for a symbol that could not be read, null when none could. */
    fun mmsi(s: List<Int>): String? {
        val r = buildString { for (x in s) if (x !in 0..99) append("~~") else append("%02d".format(x)) }.take(9)
        return r.takeIf { it.any { c -> c != '~' } }
    }

    fun mmsiSymbols(m: String): List<Int> { val t = m + "0"; return List(5) { (t[2 * it] - '0') * 10 + (t[2 * it + 1] - '0') } }

    /** Latitude and longitude from the 10 digits (quadrant, ddmm, dddmm), or null for "no position" (all 9s). */
    fun position(s: List<Int>): Pair<Double, Double>? {
        if (s.size < 5 || s.any { it !in 0..99 } || s.all { it == 99 }) return null
        val d = s.joinToString("") { "%02d".format(it) }
        val q = d[0] - '0'
        if (q > 3) return null
        val latD = d.substring(1, 3).toInt(); val latM = d.substring(3, 5).toInt()
        val lonD = d.substring(5, 8).toInt(); val lonM = d.substring(8, 10).toInt()
        if (latD > 90 || latM > 59 || lonD > 180 || lonM > 59) return null
        val lat = (latD + latM / 60.0) * (if (q == 2 || q == 3) -1 else 1)
        val lon = (lonD + lonM / 60.0) * (if (q == 1 || q == 3) -1 else 1)
        return lat to lon
    }

    fun positionSymbols(lat: Double, lon: Double): List<Int> {
        val q = (if (lat < 0) 2 else 0) + (if (lon < 0) 1 else 0)
        var latD = abs(lat).toInt(); var latM = ((abs(lat) - latD) * 60).roundToInt()
        var lonD = abs(lon).toInt(); var lonM = ((abs(lon) - lonD) * 60).roundToInt()
        if (latM == 60) { latD++; latM = 0 }
        if (lonM == 60) { lonD++; lonM = 0 }
        val d = "%d%02d%02d%03d%02d".format(q, latD, latM, lonD, lonM)
        return List(5) { d.substring(2 * it, 2 * it + 2).toInt() }
    }

    /** A proposed channel or frequency (three symbols in the order sent): "VHF 16", "2182.0 kHz", null for none. */
    fun frequency(s: List<Int>): String? {
        if (s.size < 3 || s.all { it == 126 } || s.any { it !in 0..99 }) return null
        val (c3, c2, c1) = s
        return when (c3 / 10) {
            3 -> "MF/HF %d".format((c3 % 10) * 10000 + c2 * 100 + c1)
            9 -> "VHF %d".format((c2 % 10) * 100 + c1)
            else -> "%.1f kHz".format(java.util.Locale.ROOT, (c3 * 10000 + c2 * 100 + c1) / 10.0)
        }
    }

    fun vhfChannelSymbols(ch: Int) = listOf(90, ch / 100, ch % 100)

    /**
     * What the call is, as an index into the localized names (dsc_kinds): distress alert, distress acknowledgement,
     * distress relay, distress relay acknowledgement, test call, test acknowledgement, position update, position request,
     * position reply, call acknowledgement, urgency call, safety call, routine call.
     */
    fun kind(c: DscCall): Int {
        val ack = c.eos == EOS_ACK
        return when {
            c.format == DISTRESS -> 0
            c.category == DISTRESS && c.telecmd1 == 110 -> if (c.format == ALL_SHIPS) 1 else 3
            c.category == DISTRESS && c.telecmd1 == 112 -> 2
            c.telecmd1 == 118 -> if (ack) 5 else 4
            c.telecmd1 == 121 -> 6
            c.telecmd1 == 103 -> if (ack) 8 else 7
            ack && c.position != null -> 8
            ack -> 9
            c.category == 110 -> 10
            c.category == 108 -> 11
            else -> 12
        }
    }

    /** Parses the merged information symbols, format specifier first (-1: unreadable). */
    fun parse(b: List<Int>, eos: Int, eccOk: Boolean): DscCall {
        var i = 0
        fun get(k: Int) = b.getOrElse(k) { -1 }
        fun take(n: Int) = List(n) { get(i + it) }.also { i += n }
        val format = get(i++)
        var to: String? = null
        var category = DISTRESS
        if (format != DISTRESS) {
            when (format) {
                INDIVIDUAL, GROUP, AUTOMATIC -> to = mmsi(take(5))
                AREA -> {
                    val s = take(5)
                    if (s.all { it in 0..99 }) {
                        val d = s.joinToString("") { "%02d".format(it) }
                        val q = d[0] - '0'
                        to = "${if (q >= 2) 'S' else 'N'}${d.substring(1, 3)}° ${if (q == 1 || q == 3) 'W' else 'E'}${d.substring(3, 6)}° · Δ${d.substring(6, 8)}°×${d.substring(8, 10)}°"
                    }
                }
            }
            category = get(i++)
        }
        val from = mmsi(take(5))
        var nature = -1
        var pos: Pair<Double, Double>? = null
        var time: Pair<Int, Int>? = null
        var tc1 = -1
        var distressMmsi: String? = null
        var channel: String? = null
        fun distressBlock() {
            nature = get(i++)
            pos = position(take(5))
            val a = get(i); val c = get(i + 1); i += 2
            if (a in 0..23 && c in 0..59) time = a to c
            i++ // the kind of communication proposed afterwards
        }
        if (format == DISTRESS) {
            distressBlock()
        } else {
            tc1 = get(i++)
            if (category == DISTRESS && (tc1 == 110 || tc1 == 112)) {
                distressMmsi = mmsi(take(5))
                distressBlock()
            } else {
                i++ // second telecommand
                val left = b.size - i
                val f = List(6) { get(i + it) }
                if (left >= 6 && f[0] == 55) {
                    pos = position(f.drop(1))
                    if (left >= 8) { val a = get(i + 6); val c = get(i + 7); if (a in 0..23 && c in 0..59) time = a to c }
                } else if (left >= 3) {
                    channel = frequency(f.take(3)) ?: if (left >= 6) frequency(f.drop(3)) else null
                }
            }
        }
        return DscCall(format, category, from, to, nature, pos, time, tc1, distressMmsi, channel, eos, eccOk, b)
    }

    /** Body from the format specifier to the last message symbol. */
    fun distressAlert(mmsi: String, nature: Int, lat: Double, lon: Double, h: Int, m: Int) =
        listOf(DISTRESS) + mmsiSymbols(mmsi) + nature + positionSymbols(lat, lon) + listOf(h, m, 100)

    fun individual(to: String, category: Int, from: String, tc1: Int, tc2: Int, channel: Int) =
        listOf(INDIVIDUAL) + mmsiSymbols(to) + category + mmsiSymbols(from) + listOf(tc1, tc2) + vhfChannelSymbols(channel) + listOf(126, 126, 126)

    fun allShips(from: String, category: Int, tc1: Int, channel: Int) =
        listOf(ALL_SHIPS, category) + mmsiSymbols(from) + listOf(tc1, 126) + vhfChannelSymbols(channel) + listOf(126, 126, 126)

    /** The symbols in slot order: phasing, then each one at its DX place and again five places later. */
    fun slots(body: List<Int>, eos: Int): List<Int> {
        val dx = listOf(body[0], body[0]) + body.drop(1) + listOf(eos, ecc(body, eos), eos, eos)
        val last = 12 + 2 * (dx.size - 3) + 5
        val s = MutableList(last + 1) { 126 }
        for (k in 0 until 16) phasingAt(k).let { if (it >= 0) s[k] = it }
        for (k in dx.indices) {
            s[12 + 2 * k] = dx[k]
            val rx = 12 + 2 * k + 5
            if (rx in 16..last) s[rx] = dx[k]
        }
        return s
    }

    /** Bits on air: the dot pattern, then each slot's 10 bits. 1 = Y. */
    fun frameBits(body: List<Int>, eos: Int, dots: Int = 20): IntArray {
        val out = ArrayList<Int>()
        repeat(dots) { out += if (it % 2 == 0) 1 else 0 }
        for (s in slots(body, eos)) { val b = bits(s); for (i in 9 downTo 0) out += (b shr i) and 1 }
        return out.toIntArray()
    }

    /** The country of a station from its MID: ships MIDxxxxxx, coast stations 00MIDxxxx, groups 0MIDxxxxx. */
    fun mid(m: String?): Int? {
        if (m == null || m.length != 9 || m.any { !it.isDigit() }) return null
        return when {
            m.startsWith("00") -> m.substring(2, 5).toInt()
            m.startsWith("0") -> m.substring(1, 4).toInt()
            m.startsWith("111") -> m.substring(3, 6).toInt()
            m.startsWith("8") || m.startsWith("9") -> null
            else -> m.substring(0, 3).toInt()
        }
    }

    fun isCoast(m: String?) = m != null && m.startsWith("00")
}

data class DscCall(
    val format: Int,
    val category: Int,
    val from: String?,
    /** The called station's MMSI, the area, or null for all ships. */
    val to: String?,
    /** Nature of distress, 100 to 112, or -1. */
    val nature: Int,
    val position: Pair<Double, Double>?,
    val utc: Pair<Int, Int>?,
    val telecmd1: Int,
    val distressMmsi: String?,
    /** The proposed working channel or frequency. */
    val channel: String?,
    val eos: Int,
    val eccOk: Boolean,
    val symbols: List<Int>,
) {
    val distress get() = format == Dsc.DISTRESS || category == Dsc.DISTRESS
}

/**
 * Bits to calls: the phasing sequence locks the symbol clock (three of its symbols in their places), then every
 * information symbol is taken from whichever copy passes its check bits, and the check character settles copies that
 * disagree, trying up to three changes; one symbol lost in both copies is rebuilt from it.
 */
class DscDecoder(private val onCall: (DscCall) -> Unit) {
    private class Sym(val s: Int, val conf: Float)

    private var reg = 0
    private val regConf = FloatArray(10)
    private var count = 0L
    private val hist = Array(10) { ArrayList<Sym>() }
    private var locked = false
    private var lockPhase = 0
    private val slots = ArrayList<Sym>()

    var good = 0
        private set
    var bad = 0
        private set

    /** One bit; [soft] > 0 is Y, its size (0 to 1) how sure. */
    fun bit(soft: Float) {
        reg = ((reg shl 1) or (if (soft > 0) 1 else 0)) and 0x3FF
        System.arraycopy(regConf, 1, regConf, 0, 9)
        regConf[9] = minOf(abs(soft), 1f)
        count++
        if (count < 10) return
        val ph = (count % 10).toInt()
        val s = Dsc.decode(reg)
        val conf = regConf.min()
        if (locked) {
            if (ph == lockPhase) { slots += Sym(s, conf); finish() }
            return
        }
        val h = hist[ph]
        h += Sym(s, conf)
        if (h.size > 24) h.removeAt(0)
        if (s < 0) return
        val n = h.size
        var bestSlot = -1
        var bestCount = 0
        for (slot in 0 until minOf(16, n)) {
            if (Dsc.phasingAt(slot) != s) continue
            var c = 0
            for (j in 0..slot) if (h[n - 1 - j].s == Dsc.phasingAt(slot - j)) c++
            if (c > bestCount) { bestCount = c; bestSlot = slot }
        }
        if (bestCount >= 3) {
            locked = true; lockPhase = ph
            slots.clear(); slots.addAll(h.subList(n - (bestSlot + 1), n))
            hist.forEach { it.clear() }
            finish()
        }
    }

    private fun abort() { locked = false; slots.clear(); hist.forEach { it.clear() } }

    private class Cand(val v: MutableList<Int> = ArrayList(), var conf: Float = 0f)

    private fun finish() {
        val n = slots.size
        if (n > 12 + 2 * 140) { abort(); return }
        if (n >= 60 && (n - 30 until n).count { slots[it].s < 0 } >= 24) { bad++; abort(); return }
        if (n < 18) return
        fun tok(i: Int): Pair<Int, Boolean> {
            val dxs = 12 + 2 * i
            val rxs = dxs + 5
            val d = if (dxs < n) slots[dxs].s else -1
            val r = if (rxs < n) slots[rxs].s else -1
            return (if (d >= 0) d else r) to (rxs >= n)
        }
        var eosIdx = -1
        for (i in 2..(n - 13) / 2) {
            val (m, pend) = tok(i)
            if (pend) break
            if (Dsc.isEos(m)) { eosIdx = i; break }
        }
        if (eosIdx < 0) return
        if (tok(eosIdx + 1).second) return // wait for the check character's second copy
        fun cand(dxs: Int, rxs: Int): Cand {
            val c = Cand()
            val d = slots.getOrNull(dxs)
            val r = slots.getOrNull(rxs)
            if (d != null && d.s >= 0) { c.v += d.s; c.conf = d.conf }
            if (r != null && r.s >= 0 && (c.v.isEmpty() || r.s != c.v[0])) { c.v += r.s; if (c.v.size == 1) c.conf = r.conf }
            return c
        }
        val cands = ArrayList<Cand>()
        // The format specifier is sent twice, at both places: four copies.
        val c0 = Cand()
        for (dxs in intArrayOf(12, 14)) { val t = cand(dxs, dxs + 5); t.v.forEach { if (it !in c0.v) c0.v += it }; if (c0.conf == 0f) c0.conf = t.conf }
        cands += c0
        for (i in 2 until eosIdx) cands += cand(12 + 2 * i, 12 + 2 * i + 5)
        val eccPos = cands.size
        cands += cand(12 + 2 * (eosIdx + 1), 12 + 2 * (eosIdx + 1) + 5)
        val eos = tok(eosIdx).first
        val unknown = (0 until eccPos).filter { cands[it].v.isEmpty() }
        var body: List<Int>? = null
        if (cands[eccPos].v.isNotEmpty()) {
            if (unknown.isEmpty()) {
                val flex = (0..eccPos).filter { cands[it].v.size > 1 }.sortedBy { cands[it].conf }
                val pick = IntArray(cands.size)
                fun build() = List(eccPos) { cands[it].v[pick[it]] }
                fun dfs(idx: Int, left: Int): Boolean {
                    if (idx == flex.size) return Dsc.ecc(build(), eos) == cands[eccPos].v[pick[eccPos]]
                    val k = flex[idx]
                    for (j in cands[k].v.indices) {
                        if (j > 0 && left == 0) break
                        pick[k] = j
                        if (dfs(idx + 1, left - (if (j > 0) 1 else 0))) return true
                    }
                    pick[k] = 0
                    return false
                }
                for (dev in 0..3) if (dfs(0, dev)) { body = build(); break }
            } else if (unknown.size == 1 && (0..eccPos).none { it != unknown[0] && cands[it].v.size > 1 }) {
                var x = eos and 127
                val bd = MutableList(eccPos) { if (it == unknown[0]) 0 else cands[it].v[0] }
                for (k in 0 until eccPos) if (k != unknown[0]) x = x xor (bd[k] and 127)
                x = x xor (cands[eccPos].v[0] and 127)
                bd[unknown[0]] = x
                body = bd
            }
        }
        abort()
        if (body != null) { good++; onCall(Dsc.parse(body, eos, true)) } else bad++
    }
}

/**
 * VHF DSC from FM-discriminated baseband: tone correlators for 1300 and 2100 Hz over one bit, then a bit clock that
 * re-centres on each transition (the dot pattern gives plenty).
 */
class DscDemodulator(private val sampleRate: Double, private val onCall: (DscCall) -> Unit) {
    private val len = (sampleRate / BAUD).roundToInt()
    private val spb = sampleRate / BAUD
    private val yre = FloatArray(len)
    private val yim = FloatArray(len)
    private val bre = FloatArray(len)
    private val bim = FloatArray(len)
    private var sYr = 0.0
    private var sYi = 0.0
    private var sBr = 0.0
    private var sBi = 0.0
    private var phY = 0.0
    private var phB = 0.0
    private val incY = 2 * PI * 1300 / sampleRate
    private val incB = 2 * PI * 2100 / sampleRate
    private var pr = 0f
    private var pi = 0f
    private var mean = 0f
    private var n = 0L
    private var next = spb / 2
    private var lastLevel = 0
    private val decoder = DscDecoder(onCall)
    val dsc get() = decoder

    fun feed(re: FloatArray, im: FloatArray, count: Int) {
        for (i in 0 until count) {
            val a = re[i] * pr + im[i] * pi
            val b = im[i] * pr - re[i] * pi
            pr = re[i]; pi = im[i]
            step(atan2(b, a))
        }
    }

    private fun step(f: Float) {
        mean += (f - mean) / 400f
        val x = f - mean
        val k = (n % len).toInt()
        val cy = (x * cos(phY)).toFloat(); val sy = (-x * sin(phY)).toFloat()
        val cb = (x * cos(phB)).toFloat(); val sb = (-x * sin(phB)).toFloat()
        sYr += cy - yre[k]; sYi += sy - yim[k]; yre[k] = cy; yim[k] = sy
        sBr += cb - bre[k]; sBi += sb - bim[k]; bre[k] = cb; bim[k] = sb
        phY += incY; if (phY > 2 * PI) phY -= 2 * PI
        phB += incB; if (phB > 2 * PI) phB -= 2 * PI
        val ey = sYr * sYr + sYi * sYi
        val eb = sBr * sBr + sBi * sBi
        val soft = ((ey - eb) / (ey + eb + 1e-12)).toFloat()
        val level = if (soft > 0) 1 else 0
        // The correlators sum the last bit's worth of samples: they cross over half a bit after a boundary, and see the
        // whole next bit half a bit later still, where it is decided. Nudged, not snapped, to ride over noise.
        if (level != lastLevel) {
            val err = (n + spb / 2) - next
            next += err * 0.3
            lastLevel = level
        }
        if (n >= next) {
            decoder.bit(soft)
            next += spb
        }
        n++
    }

    companion object {
        const val BAUD = 1200.0
    }
}

/** HackRF bytes at 2 MS/s with channel 70 at +fs/4 → 500 kS/s → 50 kS/s → DSC calls. */
class DscReceiver(onCall: (DscCall) -> Unit) {
    private val d1 = Decimator(4, 0.07, taps = 48)
    private val d2 = FloatDecimator(10, 0.024)
    private val aRe = FloatArray(32768)
    private val aIm = FloatArray(32768)
    private val bRe = FloatArray(4096)
    private val bIm = FloatArray(4096)
    val demod = DscDemodulator(50_000.0, onCall)

    fun feed(buf: ByteArray, len: Int) {
        val k = d1.process(buf, len, aRe, aIm, 0)
        val m = d2.process(aRe, aIm, k, bRe, bIm)
        demod.feed(bRe, bIm, m)
    }

    companion object {
        const val SAMPLE_RATE = 2_000_000
        const val CHANNEL_70_HZ = 156_525_000L
    }
}
