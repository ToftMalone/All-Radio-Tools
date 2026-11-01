package com.allnetworktools.data.sdr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Mode S / ADS-B on 1090 MHz (ICAO Annex 10 vol. IV; decoding as described in "The 1090 MHz Riddle", J. Sun,
 * TU Delft): pulse-position modulation at 1 Mbit/s, 8 µs preamble, 56- or 112-bit frames with a 24-bit CRC.
 */
object ModeS {
    private val crcTable = IntArray(256).also { t ->
        for (i in 0 until 256) {
            var c = i shl 16
            repeat(8) { c = if (c and 0x800000 != 0) (c shl 1) xor 0xFFF409 else c shl 1 }
            t[i] = c and 0xFFFFFF
        }
    }

    /** CRC-24 (generator 0x1FFF409) of the first [bytes] bytes. */
    fun crc(msg: ByteArray, bytes: Int): Int {
        var c = 0
        for (i in 0 until bytes) c = ((c shl 8) xor crcTable[((c shr 16) xor (msg[i].toInt() and 0xFF)) and 0xFF]) and 0xFFFFFF
        return c
    }

    /** Parity check of an extended squitter (DF17/18): CRC of the first 88 bits equals the last 24. */
    fun parityOk(msg: ByteArray): Boolean {
        if (msg.size < 14) return false
        val pi = ((msg[11].toInt() and 0xFF) shl 16) or ((msg[12].toInt() and 0xFF) shl 8) or (msg[13].toInt() and 0xFF)
        return crc(msg, 11) == pi
    }

    fun df(msg: ByteArray) = (msg[0].toInt() and 0xFF) shr 3

    fun icao(msg: ByteArray) = ((msg[1].toInt() and 0xFF) shl 16) or ((msg[2].toInt() and 0xFF) shl 8) or (msg[3].toInt() and 0xFF)

    fun hex(msg: ByteArray) = msg.joinToString("") { "%02X".format(it) }

    fun fromHex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}

/** What one extended squitter carries. */
sealed interface AdsbInfo {
    data class Identification(val callsign: String, val category: Int) : AdsbInfo
    data class Position(val altitudeFt: Int?, val odd: Boolean, val latCpr: Int, val lonCpr: Int) : AdsbInfo
    data class Velocity(val speedKt: Double?, val headingDeg: Double?, val verticalFtMin: Int?, val airspeed: Boolean) : AdsbInfo
    data class Other(val typeCode: Int) : AdsbInfo
}

object AdsbDecode {
    private const val CHARS = "#ABCDEFGHIJKLMNOPQRSTUVWXYZ##### ###############0123456789######"

    /** Bits [from, from+len) of the 112-bit message, MSB first. */
    private fun bits(m: ByteArray, from: Int, len: Int): Int {
        var v = 0
        for (i in from until from + len) v = (v shl 1) or ((m[i / 8].toInt() shr (7 - i % 8)) and 1)
        return v
    }

    /** Decodes a DF17/18 message that passed its parity check. */
    fun decode(m: ByteArray): AdsbInfo {
        val tc = bits(m, 32, 5)
        return when (tc) {
            in 1..4 -> {
                val cs = (0 until 8).map { CHARS[bits(m, 40 + 6 * it, 6)] }.joinToString("").replace("#", "").trim()
                AdsbInfo.Identification(cs, bits(m, 37, 3))
            }
            in 9..18, in 20..22 -> {
                val alt = if (tc <= 18) baroAltitude(bits(m, 40, 12)) else null
                AdsbInfo.Position(alt, bits(m, 53, 1) == 1, bits(m, 54, 17), bits(m, 71, 17))
            }
            19 -> velocity(m)
            else -> AdsbInfo.Other(tc)
        }
    }

    /** 12-bit altitude with the Q bit (25 ft steps); Gillham-coded altitudes (Q = 0) are not decoded. */
    fun baroAltitude(a: Int): Int? {
        if (a == 0) return null
        if ((a shr 4) and 1 == 0) return null
        val n = ((a shr 5) shl 4) or (a and 0x0F)
        return n * 25 - 1000
    }

    private fun velocity(m: ByteArray): AdsbInfo {
        val st = bits(m, 37, 3)
        val vrRaw = bits(m, 69, 9)
        val vr = if (vrRaw == 0) null else (vrRaw - 1) * 64 * (if (bits(m, 68, 1) == 1) -1 else 1)
        return when (st) {
            1, 2 -> {
                val f = if (st == 2) 4 else 1
                val ew = bits(m, 46, 10)
                val ns = bits(m, 57, 10)
                if (ew == 0 || ns == 0) return AdsbInfo.Velocity(null, null, vr, false)
                val vew = (ew - 1) * f * (if (bits(m, 45, 1) == 1) -1 else 1)
                val vns = (ns - 1) * f * (if (bits(m, 56, 1) == 1) -1 else 1)
                val hdg = (Math.toDegrees(atan2(vew.toDouble(), vns.toDouble())) + 360) % 360
                AdsbInfo.Velocity(hypot(vew.toDouble(), vns.toDouble()), hdg, vr, false)
            }
            3, 4 -> {
                val hdg = if (bits(m, 45, 1) == 1) bits(m, 46, 10) * 360.0 / 1024 else null
                val asRaw = bits(m, 57, 10)
                val speed = if (asRaw == 0) null else ((asRaw - 1) * (if (st == 4) 4 else 1)).toDouble()
                AdsbInfo.Velocity(speed, hdg, vr, true)
            }
            else -> AdsbInfo.Other(19)
        }
    }
}

/** Compact Position Reporting: global airborne decoding from an even and an odd frame. */
object Cpr {
    /** Number of longitude zones at a latitude. */
    fun nl(lat: Double): Int {
        val a = abs(lat)
        if (a < 1e-9) return 59
        if (abs(a - 87) < 1e-9) return 2
        if (a > 87) return 1
        val nz = 15.0
        val x = 1 - (1 - cos(PI / (2 * nz))) / (cos(PI * a / 180) * cos(PI * a / 180))
        return floor(2 * PI / acos(x)).toInt()
    }

    private fun mod(a: Double, b: Double) = a - b * floor(a / b)

    /** Position from both frames; [oddNewer] picks which one the result refers to. Null if they straddle a zone edge. */
    fun global(latEven: Int, lonEven: Int, latOdd: Int, lonOdd: Int, oddNewer: Boolean): Pair<Double, Double>? {
        val le = latEven / 131072.0
        val ne = lonEven / 131072.0
        val lo = latOdd / 131072.0
        val no = lonOdd / 131072.0
        val j = floor(59 * le - 60 * lo + 0.5)
        var latE = 360.0 / 60 * (mod(j, 60.0) + le)
        var latO = 360.0 / 59 * (mod(j, 59.0) + lo)
        if (latE >= 270) latE -= 360
        if (latO >= 270) latO -= 360
        if (nl(latE) != nl(latO)) return null
        val lat = if (oddNewer) latO else latE
        val nlLat = nl(lat)
        val ni = maxOf(if (oddNewer) nlLat - 1 else nlLat, 1)
        val m = floor(ne * (nlLat - 1) - no * nlLat + 0.5)
        var lon = 360.0 / ni * (mod(m, ni.toDouble()) + if (oddNewer) no else ne)
        if (lon >= 180) lon -= 360
        return lat to lon
    }
}

/**
 * PPM demodulator for interleaved signed 8-bit I/Q at 2 MS/s (two samples per bit): finds the four-pulse
 * preamble on the magnitude, slices 112 bits by comparing each bit's two half-samples, and keeps messages whose
 * CRC is valid. At 2 MS/s a pulse edge can fall on a sample, leaving a few bits uncertain; when the CRC fails, the
 * least certain bits are flipped, one or two at a time, and a message mended this way is kept only for an aircraft
 * already heard without mending, so noise cannot invent one.
 */
class AdsbDemodulator(private val onMessage: (ByteArray, Double) -> Unit) {
    // Magnitude look-up for every (I, Q) byte pair, DC is removed beforehand.
    private val mags = FloatArray(MAX + 300)
    private var carry = 0
    private var dcI = 0f
    private var dcQ = 0f
    /** Samples read so far, the clock for [clean]. */
    private var clock = 0L
    /** Aircraft heard in a message that needed no mending, and when (in samples). */
    private val clean = HashMap<Int, Long>()
    private val conf = FloatArray(112)
    private val order = IntArray(112)

    var decoded = 0
        private set
    var mended = 0
        private set

    fun feed(buf: ByteArray, len: Int) {
        val n = len / 2
        var i = 0
        while (i < n) {
            val chunk = minOf(MAX - carry, n - i)
            var si = 0f
            var sq = 0f
            for (k in 0 until chunk) {
                val re = buf[2 * (i + k)].toFloat()
                val im = buf[2 * (i + k) + 1].toFloat()
                si += re; sq += im
                val a = re - dcI
                val b = im - dcQ
                mags[carry + k] = sqrt(a * a + b * b)
            }
            // Slow DC tracking: the HackRF's offset sits exactly on 1090 MHz when tuned there.
            dcI += (si / chunk - dcI) * 0.5f
            dcQ += (sq / chunk - dcQ) * 0.5f
            scan(carry + chunk)
            clock += chunk
            i += chunk
        }
    }

    private fun scan(total: Int) {
        val m = mags
        val last = total - FRAME
        var p = 0
        while (p < last) {
            // Pulses at 0, 1, 3.5 and 4.5 µs → samples 0, 2, 7, 9.
            if (m[p] > m[p + 1] && m[p + 1] < m[p + 2] && m[p + 2] > m[p + 3] && m[p + 3] < m[p] &&
                m[p + 4] < m[p] && m[p + 5] < m[p] && m[p + 6] < m[p] && m[p + 7] > m[p + 8] && m[p + 8] < m[p + 9] && m[p + 9] > m[p + 6]
            ) {
                val high = (m[p] + m[p + 2] + m[p + 7] + m[p + 9]) / 6
                if (m[p + 4] < high && m[p + 5] < high && m[p + 11] < high && m[p + 12] < high && m[p + 13] < high && m[p + 14] < high) {
                    // When an edge of the first pulse fell on a sample, the pulse spread over the next one too: the
                    // bits may then be read one sample later.
                    var msg = slice(p + 16)
                    if (msg == null && m[p + 1] > high) msg = slice(p + 17)
                    if (msg != null) {
                        decoded++
                        onMessage(msg, 20 * log10((high * 1.5 / 128.0).coerceAtLeast(1e-6)))
                        p += FRAME
                        continue
                    }
                }
            }
            p++
        }
        // Keep the tail: a frame may straddle two USB transfers.
        val keep = minOf(FRAME, total)
        System.arraycopy(m, total - keep, m, 0, keep)
        carry = keep
    }

    private fun slice(start: Int): ByteArray? {
        val msg = ByteArray(14)
        for (bit in 0 until 112) {
            val a = mags[start + 2 * bit]
            val b = mags[start + 2 * bit + 1]
            if (a > b) msg[bit / 8] = (msg[bit / 8].toInt() or (0x80 ushr (bit % 8))).toByte()
            conf[bit] = abs(a - b) / (a + b + 1e-3f)
            if (bit == 4) {
                val df = (msg[0].toInt() and 0xFF) shr 3
                if (df != 17 && df != 18) return null
            }
        }
        if (ModeS.parityOk(msg)) {
            clean[ModeS.icao(msg)] = clock
            if (clean.size > 2000) clean.entries.removeAll { clock - it.value > KNOWN }
            return msg
        }
        // The least certain bits after the downlink format, weakest first.
        var k = 0
        for (i in 5 until 112) order[k++] = i
        val weak = order.copyOf(k).sortedBy { conf[it] }.take(WEAK)
        fun flip(i: Int) { msg[i / 8] = (msg[i / 8].toInt() xor (0x80 ushr (i % 8))).toByte() }
        for (x in weak.indices) {
            flip(weak[x])
            if (ModeS.parityOk(msg) && known(msg)) return msg
            for (y in x + 1 until weak.size) {
                flip(weak[y])
                if (ModeS.parityOk(msg) && known(msg)) return msg
                flip(weak[y])
            }
            flip(weak[x])
        }
        return null
    }

    private fun known(msg: ByteArray): Boolean {
        val at = clean[ModeS.icao(msg)] ?: return false
        if (clock - at > KNOWN) return false
        mended++
        return true
    }

    companion object {
        private const val MAX = 65536
        /** Preamble (16 samples) and 112 bits (224 samples). */
        private const val FRAME = 16 + 224
        private const val WEAK = 10
        /** A mended message is kept for an aircraft heard cleanly within the last minute. */
        private const val KNOWN = 60L * 2_000_000
    }
}

/** One aircraft as built from its messages. */
class Aircraft(val icao: Int) {
    var callsign: String? = null
    var altitudeFt: Int? = null
    var speedKt: Double? = null
    var headingDeg: Double? = null
    var verticalFtMin: Int? = null
    var lat: Double? = null
    var lon: Double? = null
    var lastSeenMs = 0L
    var lastPositionMs = 0L
    var messages = 0
    var levelDb = -99.0
    internal var even: Triple<Int, Int, Long>? = null
    internal var odd: Triple<Int, Int, Long>? = null

    val hex: String get() = "%06X".format(icao)
}

/** Builds the aircraft table from decoded messages; a position needs an even and an odd frame less than 10 s apart. */
class AdsbTracker {
    val aircraft = HashMap<Int, Aircraft>()

    fun update(msg: ByteArray, levelDb: Double, nowMs: Long): Aircraft {
        val a = aircraft.getOrPut(ModeS.icao(msg)) { Aircraft(ModeS.icao(msg)) }
        a.lastSeenMs = nowMs
        a.messages++
        a.levelDb = levelDb
        when (val info = AdsbDecode.decode(msg)) {
            is AdsbInfo.Identification -> if (info.callsign.isNotEmpty()) a.callsign = info.callsign
            is AdsbInfo.Velocity -> {
                info.speedKt?.let { a.speedKt = it }
                info.headingDeg?.let { a.headingDeg = it }
                info.verticalFtMin?.let { a.verticalFtMin = it }
            }
            is AdsbInfo.Position -> {
                info.altitudeFt?.let { a.altitudeFt = it }
                val frame = Triple(info.latCpr, info.lonCpr, nowMs)
                if (info.odd) a.odd = frame else a.even = frame
                val e = a.even
                val o = a.odd
                if (e != null && o != null && abs(e.third - o.third) <= 10_000) {
                    Cpr.global(e.first, e.second, o.first, o.second, oddNewer = info.odd)?.let { (lat, lon) ->
                        // Reject a jump no airliner can make between two fixes: likely a CPR zone mix-up.
                        val ok = a.lat == null || nowMs - a.lastPositionMs > 30_000 ||
                            haversineKm(a.lat!!, a.lon!!, lat, lon) < 20
                        if (ok) { a.lat = lat; a.lon = lon; a.lastPositionMs = nowMs }
                    }
                }
            }
            is AdsbInfo.Other -> Unit
        }
        return a
    }

    /** Drops aircraft not heard for [maxAgeMs]. */
    fun prune(nowMs: Long, maxAgeMs: Long = 60_000) {
        aircraft.values.removeAll { nowMs - it.lastSeenMs > maxAgeMs }
    }

    companion object {
        fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val h = kotlin.math.sin(dLat / 2).let { it * it } +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * kotlin.math.sin(dLon / 2).let { it * it }
            return 2 * r * kotlin.math.asin(sqrt(h))
        }
    }
}
