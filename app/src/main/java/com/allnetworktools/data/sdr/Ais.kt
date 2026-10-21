package com.allnetworktools.data.sdr

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import com.allnetworktools.R
import com.allnetworktools.util.S

/** One decoded AIS message, reduced to what the app shows. Fields a message type does not carry stay null. */
data class AisMessage(
    val type: Int,
    val mmsi: Int,
    val lat: Double? = null,
    val lon: Double? = null,
    val sogKn: Double? = null,
    val cogDeg: Double? = null,
    val heading: Int? = null,
    val navStatus: Int? = null,
    val name: String? = null,
    val callsign: String? = null,
    val shipType: Int? = null,
    val destination: String? = null,
    /** Aid to navigation (buoy, light…) or coastal base station, which are not ships. */
    val aid: Boolean = false,
    val base: Boolean = false,
)

/** Automatic Identification System (ITU-R M.1371): message layouts, 6-bit text and lookups. */
object Ais {
    private fun u(b: ByteArray, from: Int, len: Int): Int {
        var v = 0
        for (i in from until from + len) v = (v shl 1) or b[i].toInt()
        return v
    }

    private fun s(b: ByteArray, from: Int, len: Int): Int {
        val v = u(b, from, len)
        return if (v and (1 shl (len - 1)) != 0) v - (1 shl len) else v
    }

    /** 6-bit characters: 0–31 are '@'…'_', 32–63 are ' '…'?'. Trailing '@' and spaces are padding. */
    private fun text(b: ByteArray, from: Int, chars: Int): String? {
        val sb = StringBuilder()
        for (i in 0 until chars) {
            val v = u(b, from + 6 * i, 6)
            sb.append(if (v < 32) (v + 64).toChar() else v.toChar())
        }
        val t = sb.toString().trimEnd('@', ' ')
        return t.takeIf { it.isNotEmpty() && !t.all { c -> c == '@' } }
    }

    private fun lat(raw: Int) = if (raw == 91 * 600_000) null else (raw / 600_000.0).takeIf { it in -90.0..90.0 }
    private fun lon(raw: Int) = if (raw == 181 * 600_000) null else (raw / 600_000.0).takeIf { it in -180.0..180.0 }

    /** [b] holds one bit per byte (0 or 1), in transmission order, without the CRC. */
    fun decode(b: ByteArray, n: Int): AisMessage? {
        if (n < 38) return null
        val type = u(b, 0, 6)
        val mmsi = u(b, 8, 30)
        if (mmsi == 0) return null
        return when (type) {
            1, 2, 3 -> if (n < 137) null else {
                val sog = u(b, 50, 10)
                val cog = u(b, 116, 12)
                val hdg = u(b, 128, 9)
                AisMessage(
                    type, mmsi, lat(s(b, 89, 27)), lon(s(b, 61, 28)),
                    sog.takeIf { it < 1023 }?.let { it / 10.0 }, cog.takeIf { it < 3600 }?.let { it / 10.0 }, hdg.takeIf { it < 360 },
                    u(b, 38, 4),
                )
            }
            18, 19 -> if (n < 133) null else {
                val sog = u(b, 46, 10)
                val cog = u(b, 112, 12)
                val hdg = u(b, 124, 9)
                AisMessage(
                    type, mmsi, lat(s(b, 85, 27)), lon(s(b, 57, 28)),
                    sog.takeIf { it < 1023 }?.let { it / 10.0 }, cog.takeIf { it < 3600 }?.let { it / 10.0 }, hdg.takeIf { it < 360 },
                    name = if (type == 19 && n >= 263) text(b, 143, 20) else null,
                    shipType = if (type == 19 && n >= 271) u(b, 263, 8).takeIf { it > 0 } else null,
                )
            }
            5 -> if (n < 302) null else AisMessage(
                type, mmsi, callsign = text(b, 70, 7), name = text(b, 112, 20), shipType = u(b, 232, 8).takeIf { it > 0 },
                destination = if (n >= 422) text(b, 302, 20) else null,
            )
            24 -> when (u(b, 38, 2)) {
                0 -> if (n < 160) null else AisMessage(type, mmsi, name = text(b, 40, 20))
                1 -> if (n < 132) null else AisMessage(type, mmsi, callsign = text(b, 90, 7), shipType = u(b, 40, 8).takeIf { it > 0 })
                else -> null
            }
            21 -> if (n < 219) null else AisMessage(type, mmsi, lat(s(b, 192, 27)), lon(s(b, 164, 28)), name = text(b, 43, 20), aid = true)
            4 -> if (n < 134) null else AisMessage(type, mmsi, lat(s(b, 107, 27)), lon(s(b, 79, 28)), base = true)
            else -> null
        }
    }

    fun shipTypeName(t: Int): String? = when (t) {
        in 20..29 -> S(R.string.hovercraft)
        30 -> S(R.string.fishing); 31, 32 -> S(R.string.towing); 33 -> S(R.string.dredging); 34 -> S(R.string.diving); 35 -> S(R.string.military); 36 -> S(R.string.sailing); 37 -> S(R.string.pleasure_craft)
        in 40..49 -> S(R.string.high_speed_craft)
        50 -> S(R.string.pilot); 51 -> S(R.string.search_and_rescue); 52 -> S(R.string.tug); 53 -> S(R.string.port_tender); 54 -> S(R.string.anti_pollution)
        55 -> S(R.string.law_enforcement); 58 -> S(R.string.medical)
        in 60..69 -> S(R.string.passenger)
        in 70..79 -> S(R.string.cargo)
        in 80..89 -> S(R.string.tanker)
        in 90..99 -> S(R.string.other)
        else -> null
    }

    fun navStatusName(s: Int): String? = when (s) {
        0 -> S(R.string.under_way); 1 -> S(R.string.at_anchor); 2 -> S(R.string.not_under_command); 3 -> S(R.string.restricted_manoeuvrability); 4 -> S(R.string.constrained_by_draught)
        5 -> S(R.string.moored); 6 -> S(R.string.aground); 7 -> S(R.string.fishing_2); 8 -> S(R.string.under_sail)
        else -> null
    }

    /** Flag state from the Maritime Identification Digits at the start of a ship's MMSI (the common ones). */
    fun country(mmsi: Int): String? = when (mmsi / 1_000_000) {
        226, 227, 228 -> S(R.string.france); 232, 233, 234, 235 -> S(R.string.united_kingdom); 211, 218 -> S(R.string.germany); 244, 245, 246 -> S(R.string.netherlands)
        205 -> S(R.string.belgium); 224, 225 -> S(R.string.spain); 247 -> S(R.string.italy); 263, 255 -> S(R.string.portugal); 219, 220 -> S(R.string.denmark); 257, 258, 259 -> S(R.string.norway)
        265, 266 -> S(R.string.sweden); 230 -> S(R.string.finland); 250 -> S(R.string.ireland); 237, 239, 240, 241 -> S(R.string.greece); 215, 248, 249, 256 -> S(R.string.malta)
        636, 637 -> S(R.string.liberia); 351, 352, 353, 354, 355, 356, 357, 370, 371, 372, 373 -> S(R.string.panama); 538 -> S(R.string.marshall_islands)
        209, 210, 212 -> S(R.string.cyprus); 253 -> S(R.string.luxembourg); 201 -> S(R.string.albania); 203 -> S(R.string.austria); 272 -> S(R.string.ukraine)
        273 -> S(R.string.russia); 271 -> S(R.string.turkey); 308, 309, 311 -> S(R.string.bahamas); 338, 366, 367, 368, 369 -> S(R.string.united_states); 412, 413, 414 -> S(R.string.china)
        else -> null
    }
}

/** What is known about one vessel or aid, built from successive messages. */
class Vessel(val mmsi: Int) {
    var name: String? = null
    var callsign: String? = null
    var shipType: Int? = null
    var destination: String? = null
    var navStatus: Int? = null
    var lat: Double? = null
    var lon: Double? = null
    var sogKn: Double? = null
    var cogDeg: Double? = null
    var heading: Int? = null
    var aid = false
    var base = false
    var messages = 0
    var lastSeenMs = 0L
}

class AisTracker {
    val vessels = LinkedHashMap<Int, Vessel>()

    fun update(m: AisMessage, nowMs: Long) {
        val v = vessels.getOrPut(m.mmsi) { Vessel(m.mmsi) }
        v.messages++
        v.lastSeenMs = nowMs
        m.name?.let { v.name = it }
        m.callsign?.let { v.callsign = it }
        m.shipType?.let { v.shipType = it }
        m.destination?.let { v.destination = it }
        m.navStatus?.let { v.navStatus = it }
        if (m.lat != null && m.lon != null) {
            v.lat = m.lat; v.lon = m.lon
            v.sogKn = m.sogKn; v.cogDeg = m.cogDeg; v.heading = m.heading
        }
        if (m.aid) v.aid = true
        if (m.base) v.base = true
    }

    /** Vessels not heard for 30 minutes (class A ships report every 2–10 s moving, 3 min at anchor) are forgotten. */
    fun prune(nowMs: Long) {
        vessels.values.removeAll { nowMs - it.lastSeenMs > 30 * 60_000 }
    }
}

/** Shifts a channel [offsetHz] from the centre to 0 Hz, low-passes it and decimates. */
class ChannelDown(offsetHz: Double, fs: Double, decim: Int, cutoff: Double, taps: Int) {
    private val inc = -2 * PI * offsetHz / fs
    private var phase = 0.0
    private val dec = FloatDecimator(decim, cutoff, taps)
    private var tr = FloatArray(0)
    private var ti = FloatArray(0)

    fun process(re: FloatArray, im: FloatArray, n: Int, outRe: FloatArray, outIm: FloatArray): Int {
        if (tr.size < n) { tr = FloatArray(n); ti = FloatArray(n) }
        for (i in 0 until n) {
            val c = cos(phase).toFloat()
            val s = sin(phase).toFloat()
            tr[i] = re[i] * c - im[i] * s
            ti[i] = re[i] * s + im[i] * c
            phase += inc
            if (phase > PI) phase -= 2 * PI else if (phase < -PI) phase += 2 * PI
        }
        return dec.process(tr, ti, n, outRe, outIm)
    }
}

/** HDLC deframer: NRZI-decoded bits in, frames with a valid CRC-16 out as one bit per byte (CRC removed). */
class Hdlc(private val onFrame: (ByteArray, Int) -> Unit) {
    private val buf = ByteArray(MAX)
    private var n = 0
    private var ones = 0
    private var inFrame = false

    fun bit(b: Int) {
        if (b == 1) {
            if (++ones > 6) { inFrame = false; n = 0; return } // abort
            if (inFrame) { if (n < MAX) buf[n++] = 1 else { inFrame = false; n = 0 } }
            return
        }
        when (ones) {
            5 -> { ones = 0; return } // stuffed zero
            6 -> { // flag 0 111111 0: its leading zero and six ones were appended to the frame
                if (inFrame && n >= 7) { n -= 7; check() }
                inFrame = true; n = 0; ones = 0; return
            }
        }
        ones = 0
        if (inFrame) { if (n < MAX) buf[n++] = 0 else { inFrame = false; n = 0 } }
    }

    private fun check() {
        if (n < 16 + 38) return
        var crc = 0xFFFF
        for (i in 0 until n) crc = if ((crc xor buf[i].toInt()) and 1 != 0) (crc ushr 1) xor 0x8408 else crc ushr 1
        if (crc == 0xF0B8) onFrame(buf, n - 16)
    }

    companion object {
        private const val MAX = 1200
    }
}

/**
 * GMSK demodulator at 48 kS/s (5 per bit): FM discriminator, DC removal, light smoothing, then NRZI and HDLC decoders
 * running on each of the five sampling phases (the one with the best timing decodes; neighbours decode the same frame,
 * which is dropped as a duplicate).
 */
class AisDemodulator(private val onMessage: (AisMessage) -> Unit) {
    private var pr = 0f
    private var pi = 0f
    private var mean = 0f
    private val d = FloatArray(3)
    private var count = 0L
    private val prevLevel = IntArray(SPS)
    private val phases = Array(SPS) { p -> Hdlc { bits, n -> frame(bits, n) } }
    private val recent = HashMap<Int, Long>()

    private fun frame(bits: ByteArray, n: Int) {
        var h = n
        for (i in 0 until n) h = h * 31 + bits[i]
        val last = recent[h]
        if (last != null && count - last < 4800) return // same frame seen on a neighbouring phase
        recent[h] = count
        if (recent.size > 64) recent.entries.removeAll { count - it.value > 48_000 }
        Ais.decode(bits, n)?.let(onMessage)
    }

    fun feed(re: FloatArray, im: FloatArray, len: Int) {
        for (i in 0 until len) {
            val a = re[i] * pr + im[i] * pi
            val b = im[i] * pr - re[i] * pi
            pr = re[i]; pi = im[i]
            val f = atan2(b, a)
            mean += (f - mean) / 100f
            d[2] = d[1]; d[1] = d[0]; d[0] = f - mean
            // Three-sample average centred on the previous sample.
            val v = (d[0] + d[1] + d[2]) / 3f
            val p = (count % SPS).toInt()
            val level = if (v > 0) 1 else 0
            phases[p].bit(if (level == prevLevel[p]) 1 else 0) // NRZI: no change is a 1
            prevLevel[p] = level
            count++
        }
    }

    companion object {
        const val SPS = 5
    }
}

/**
 * Both AIS channels (161.975 and 162.025 MHz) from one HackRF capture at [SAMPLE_RATE] tuned so that 162.000 MHz sits at
 * fs/4 above the tuning frequency. The channels are 25 kHz either side of that centre.
 */
class AisReceiver(private val onMessage: (AisMessage, Int) -> Unit) {
    private val d1 = Decimator(8, 0.04, taps = 128)
    private val aRe = FloatArray(16384)
    private val aIm = FloatArray(16384)
    private val chRe = FloatArray(4096)
    private val chIm = FloatArray(4096)
    private val down = arrayOf(
        ChannelDown(-25_000.0, SAMPLE_RATE / 8, 6, 0.038, 96),
        ChannelDown(25_000.0, SAMPLE_RATE / 8, 6, 0.038, 96),
    )
    private val demod = Array(2) { ch -> AisDemodulator { onMessage(it, ch) } }

    fun feed(buf: ByteArray, len: Int) {
        val n = d1.process(buf, len, aRe, aIm, 0)
        for (ch in 0 until 2) {
            val m = down[ch].process(aRe, aIm, n, chRe, chIm)
            demod[ch].feed(chRe, chIm, m)
        }
    }

    companion object {
        /** 48 000 × 48: each channel ends at exactly five samples per 9 600 bit/s symbol. */
        const val SAMPLE_RATE = 2_304_000.0
        const val CENTER_HZ = 162_000_000L
    }
}
