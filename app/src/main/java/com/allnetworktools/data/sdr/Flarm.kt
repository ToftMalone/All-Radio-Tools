package com.allnetworktools.data.sdr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * FLARM, the collision warning gliders and light aircraft broadcast on 868.2 and 868.4 MHz in Europe: 2-FSK ±50 kHz,
 * 100 kchip/s Manchester, sync Manchester(0xF531FAB6), then 24 payload bytes and a CRC-16. Both air protocols are
 * decoded as SoftRF does (GPL-3.0; layouts and keys published by SoftRF and the Open Glider Network): the old one (V6,
 * ported from OnAir's core/src/flarm), words 1 to 5 XXTEA-scrambled with a key made from the sender's address and the
 * time in 64 s steps; and the one FLARM moved to in 2024 (V7), words 2 to 5 under XXTEA with a fixed key then a mask
 * made from the header and the time in 16 s steps. Either way decoding needs the phone's clock.
 */
object Flarm {
    const val SYNC = 0xF531FAB6.toInt()
    const val PAYLOAD = 24
    const val FRAME = 26
    const val CHIP_RATE = 100_000

    private val key1 = intArrayOf(0xe43276df.toInt(), 0xdca83759.toInt(), 0x9802b8ac.toInt(), 0x4675a56b, 0xfc78ea65.toInt(), 0x804b90ea.toInt(), 0xb76542cd.toInt(), 0x329dfa32)
    private const val KEY2 = 0x045d9f3b
    private const val KEY3 = 0x87b562f4.toInt()
    private const val DELTA = 0x9e3779b9.toInt()

    /** CRC-16/CCITT-FALSE: polynomial 0x1021, start 0xFFFF, MSB first. */
    fun crc16(b: IntArray, n: Int, start: Int = 0xFFFF): Int {
        var c = start
        for (i in 0 until n) {
            c = c xor (b[i] shl 8)
            repeat(8) { c = if (c and 0x8000 != 0) (c shl 1) xor 0x1021 else c shl 1 }
            c = c and 0xFFFF
        }
        return c
    }

    /**
     * The frame's CRC covers the last three sync bytes (31 FA B6, the nRF905 "address" FLARM was built on) before the
     * payload, as SoftRF computes it; it is sent high byte first.
     */
    fun frameCrc(f: IntArray): Int = crc16(f, PAYLOAD, crc16(intArrayOf(0x31, 0xFA, 0xB6), 3))

    fun checkCrc(f: IntArray): Boolean {
        val c = frameCrc(f)
        return f[24] == c ushr 8 && f[25] == c and 0xFF
    }

    /** XXTEA on [n] words, 6 rounds as FLARM uses: n > 0 encrypts, n < 0 decrypts. */
    fun btea(v: IntArray, n: Int, key: IntArray, rounds: Int = 6) {
        fun mx(sum: Int, y: Int, z: Int, p: Int, e: Int) =
            (((z ushr 5) xor (y shl 2)) + ((y ushr 3) xor (z shl 4))) xor ((sum xor y) + (key[(p and 3) xor e] xor z))
        if (n > 1) {
            var sum = 0
            var z = v[n - 1]
            var y: Int
            repeat(rounds) {
                sum += DELTA
                val e = (sum ushr 2) and 3
                for (p in 0 until n - 1) { y = v[p + 1]; v[p] += mx(sum, y, z, p, e); z = v[p] }
                y = v[0]; v[n - 1] += mx(sum, y, z, n - 1, e); z = v[n - 1]
            }
        } else if (n < -1) {
            val m = -n
            var sum = rounds * DELTA
            var y = v[0]
            var z: Int
            repeat(rounds) {
                val e = (sum ushr 2) and 3
                for (p in m - 1 downTo 1) { z = v[p - 1]; v[p] -= mx(sum, y, z, p, e); y = v[p] }
                z = v[m - 1]; v[0] -= mx(sum, y, z, 0, e); y = v[0]
                sum -= DELTA
            }
        }
    }

    private fun obscure(key: Int, seed: Int): Int {
        val m1 = seed * (key xor (key ushr 16))
        val m2 = seed * (m1 xor (m1 ushr 16))
        return m2 xor (m2 ushr 16)
    }

    /** [ts] is unix time in seconds (as an unsigned 32-bit value). */
    fun key(ts: Int, addr: Int): IntArray {
        val shifted = (addr shl 8) and 0xFFFFFF
        return IntArray(4) { i ->
            val ndx = if ((ts ushr 23) and 1 == 1) i + 4 else i
            obscure(key1[ndx] xor ((ts ushr 6) xor shifted), KEY2) xor KEY3
        }
    }

    private fun rd32(b: IntArray, o: Int) = b[o] or (b[o + 1] shl 8) or (b[o + 2] shl 16) or (b[o + 3] shl 24)
    private fun wr32(b: IntArray, o: Int, v: Int) { for (i in 0 until 4) b[o + i] = (v ushr (8 * i)) and 0xFF }
    private fun parity(b: IntArray) = b.take(PAYLOAD).fold(0) { a, x -> a xor Integer.bitCount(x) } and 1

    fun packetType(b: IntArray) = (rd32(b, 0) ushr 24) and 15
    fun packetAddr(b: IntArray) = rd32(b, 0) and 0xFFFFFF

    /** The decrypted V6 position packet. */
    data class V6(
        val addr: Int,
        val addrType: Int = 2,
        /** 10 bits signed, 0.1 m/s units shifted right by [smult]. */
        val vsRaw: Int = 0,
        val airborne: Boolean = true,
        val stealth: Boolean = false,
        val noTrack: Boolean = false,
        val gps: Int = 323,
        val aircraftType: Int = 1,
        /** The position's low bits, 128e-7 degree units: complete only around a reference position. */
        val lat19: Int = 0,
        val lon20: Int = 0,
        /** Metres above the ellipsoid. */
        val alt: Int = 0,
        val smult: Int = 0,
        val ns: IntArray = IntArray(4),
        val ew: IntArray = IntArray(4),
    ) {
        /** Asked not to be tracked, or hiding its identity: such aircraft are only counted. */
        val private get() = noTrack || stealth || addrType == 3
    }

    fun encodeV6(p: V6, ts: Int): IntArray {
        val w = IntArray(6)
        w[0] = (p.addr and 0xFFFFFF) or ((p.addrType and 7) shl 28)
        w[1] = (p.vsRaw and 0x3FF) or (if (p.airborne) 1 shl 12 else 0) or (if (p.stealth) 1 shl 13 else 0) or (if (p.noTrack) 1 shl 14 else 0) or
            ((p.gps and 0xFFF) shl 16) or ((p.aircraftType and 15) shl 28)
        w[2] = (p.lat19 and 0x7FFFF) or ((p.alt and 0x1FFF) shl 19)
        w[3] = (p.lon20 and 0xFFFFF) or ((p.smult and 3) shl 30)
        w[4] = (0 until 4).fold(0) { a, i -> a or ((p.ns[i] and 0xFF) shl (8 * i)) }
        w[5] = (0 until 4).fold(0) { a, i -> a or ((p.ew[i] and 0xFF) shl (8 * i)) }
        val b = IntArray(PAYLOAD)
        for (i in 0 until 6) wr32(b, 4 * i, w[i])
        if (parity(b) == 1) w[1] = w[1] or (1 shl 15)
        val e = IntArray(5) { w[it + 1] }
        btea(e, 5, key(ts, p.addr))
        for (i in 0 until 5) wr32(b, 4 + 4 * i, e[i])
        return b
    }

    /** Null when not V6 or when the parity fails, which a wrong key time also does half of the time. */
    fun decodeV6(b: IntArray, ts: Int): V6? {
        val w0 = rd32(b, 0)
        if ((w0 ushr 24) and 15 != 0) return null
        val e = IntArray(5) { rd32(b, 4 + 4 * it) }
        btea(e, -5, key(ts, w0 and 0xFFFFFF))
        val d = IntArray(PAYLOAD)
        wr32(d, 0, w0)
        for (i in 0 until 5) wr32(d, 4 + 4 * i, e[i])
        if (parity(d) != 0) return null
        val w1 = e[0]
        return V6(
            addr = w0 and 0xFFFFFF, addrType = (w0 ushr 28) and 7,
            vsRaw = ((w1 and 0x3FF) shl 22) shr 22,
            airborne = (w1 ushr 12) and 1 == 1, stealth = (w1 ushr 13) and 1 == 1, noTrack = (w1 ushr 14) and 1 == 1,
            gps = (w1 ushr 16) and 0xFFF, aircraftType = (w1 ushr 28) and 15,
            lat19 = e[1] and 0x7FFFF, alt = e[1] ushr 19,
            lon20 = e[2] and 0xFFFFF, smult = e[2] ushr 30,
            ns = IntArray(4) { (e[3] shl (24 - 8 * it)) shr 24 },
            ew = IntArray(4) { (e[4] shl (24 - 8 * it)) shr 24 },
        )
    }

    private fun pack(deg: Double, mask: Int): Int {
        val v = (deg * 1e7).roundToLong().toInt()
        return ((v shr 7) + (if (v and 0x40 != 0) (if (v < 0) -1 else 1) else 0)) and mask
    }

    fun packPosition(lat: Double, lon: Double) = pack(lat, 0x7FFFF) to pack(lon, 0xFFFFF)

    /** Completes the position around [refLat], [refLon] (good within about ±3° of latitude and ±6° of longitude). */
    fun unpackPosition(lat19: Int, lon20: Int, refLat: Double, refLon: Double): Pair<Double, Double> {
        val rl = (refLat * 1e7).roundToLong().toInt() shr 7
        val ro = (refLon * 1e7).roundToLong().toInt() shr 7
        var la = (lat19 - rl) and 0x7FFFF
        if (la >= 0x40000) la -= 0x80000
        var lo = (lon20 - ro) and 0xFFFFF
        if (lo >= 0x80000) lo -= 0x100000
        return (la + rl) * 128 / 1e7 to (lo + ro) * 128 / 1e7
    }

    /** Ground speed (m/s), track (degrees) and climb (m/s). */
    fun velocity(p: V6): Triple<Double, Double, Double> {
        val ns = p.ns.sum() / 4
        val ew = p.ew.sum() / 4
        val speed = sqrt((ew * ew + ns * ns).toDouble()) * (1 shl p.smult) / 4.0
        var track = 0.0
        if (ns != 0 || ew != 0) { track = Math.toDegrees(atan2(ew.toDouble(), ns.toDouble())); if (track < 0) track += 360.0 }
        return Triple(speed, track, p.vsRaw * (1 shl p.smult) / 10.0)
    }

    fun withVelocity(p: V6, speedMs: Double, trackDeg: Double, climbMs: Double): V6 {
        val s4 = (speedMs * 4).roundToInt().coerceAtMost(0x3FF)
        val smult = when { s4 and 0x200 != 0 -> 3; s4 and 0x100 != 0 -> 2; s4 and 0x80 != 0 -> 1; else -> 0 }
        val speed = s4 shr smult
        val a = Math.toRadians(trackDeg)
        val ns = (speed * cos(a)).toInt()
        val ew = (speed * sin(a)).toInt()
        val vs = ((climbMs * 10).roundToInt() shr smult).coerceIn(-512, 511)
        return p.copy(smult = smult, ns = IntArray(4) { ns }, ew = IntArray(4) { ew }, vsRaw = vs)
    }

    // ---- V7, the protocol since 2024 (SoftRF's legacy_v7_packet_t and legacy_decode) ----

    private const val KEY4 = 0x956F6C77.toInt()
    private val key5 = intArrayOf(0xA5F9B21C.toInt(), 0xAB3F9D12.toInt(), 0xC6F34E34.toInt(), 0xD72FA378.toInt())

    /** Longitude units (1e-7 degree) by whole degree of latitude from 14°; 52 below. */
    val lonDiv = intArrayOf(
        53, 53, 54, 54, 55, 55,
        56, 56, 57, 57, 58, 58, 59, 59, 60, 60,
        61, 61, 62, 62, 63, 63, 64, 64, 65, 65,
        67, 68, 70, 71, 73, 74, 76, 77, 79, 80,
        82, 83, 85, 86, 88, 89, 91, 94, 98, 101,
        105, 108, 112, 115, 119, 122, 126, 129, 137, 144,
        152, 159, 167, 174, 190, 205, 221, 236, 252,
        267, 299, 330, 362, 425, 489, 552, 616, 679, 743, 806, 806,
    )

    fun lonDivAt(lat: Double): Int { val i = minOf(abs(lat).toInt(), 89); return if (i < 14) 52 else lonDiv[i - 14] }

    /** The mask over words 2 to 5: the header words, the time in 16 s steps and a constant, stirred by two XXTEA-like passes over the bytes. */
    fun v7Mask(w0: Int, w1: Int, t16: Int): IntArray {
        val k = intArrayOf(w0, w1, t16, KEY4)
        val bk = IntArray(16) { (k[it / 4] ushr (8 * (it % 4))) and 0xFF }
        var x = bk[15]
        var sum = 0
        repeat(2) {
            sum += DELTA
            for (p in 0 until 16) {
                val z = x and 0xFF
                val y = bk[(p + 1) % 16]
                x = bk[p]
                x += (((z shr 5) xor (y shl 2)) + ((y shr 3) xor (z shl 4))) xor (sum xor y)
                bk[p] = x and 0xFF
            }
        }
        return IntArray(4) { i -> (0 until 4).fold(0) { a, j -> a or (bk[4 * i + j] shl (8 * j)) } }
    }

    /** A value of [mbits] mantissa bits and [ebits] exponent bits (and a sign bit above them, when there is one). */
    fun descale(v: Int, mbits: Int, ebits: Int): Int {
        val offset = 1 shl mbits
        val signbit = offset shl ebits
        var x = v and (signbit - 1)
        if (x >= offset) { val e = x ushr mbits; x = ((x and (offset - 1)) + offset shl e) - offset }
        return if (v and signbit != 0) -x else x
    }

    fun enscale(value: Int, mbits: Int, ebits: Int, signed: Boolean): Int {
        val offset = 1 shl mbits
        val signbit = offset shl ebits
        val max = signbit - 1
        val sign = if (signed && value < 0) signbit else 0
        val v = if (signed) abs(value) else value
        if (v < offset) return sign or v
        var e = 0
        var m = offset + v
        while (m > offset + offset - 1) { m = m ushr 1; e += offset; if (e > max) return sign or max }
        return sign or e or (m - offset)
    }

    /** The decrypted V7 position packet; positions stay as sent (20 low bits), completed later around the phone. */
    data class V7(
        val addr: Int,
        val addrType: Int,
        val stealth: Boolean,
        val noTrack: Boolean,
        /** The sender's clock, its last 4 bits (seconds). */
        val tstamp: Int,
        val aircraftType: Int,
        /** Metres above the ellipsoid. */
        val alt: Int,
        val lat20: Int,
        val lon20: Int,
        val speedMs: Double,
        val climbMs: Double,
        val courseDeg: Double,
        /** 1 still, 2 moving, 3 circling. */
        val airborne: Int,
    ) {
        val private get() = noTrack || stealth || addrType == 3
    }

    private fun bitsOf(w: IntArray, from: Int, len: Int): Int {
        var v = 0
        for (i in len - 1 downTo 0) { val b = from + i; v = (v shl 1) or ((w[b ushr 5] ushr (b and 31)) and 1) }
        return v
    }

    private fun putBits(w: IntArray, from: Int, len: Int, value: Int) {
        for (i in 0 until len) {
            val b = from + i
            val bit = (value ushr i) and 1
            w[b ushr 5] = (w[b ushr 5] and (1 shl (b and 31)).inv()) or (bit shl (b and 31))
        }
    }

    /** Null when not V7 or when the fields cannot be a real packet (which a wrong key time mostly gives). */
    fun decodeV7(b: IntArray, ts: Int): V7? {
        val w0 = rd32(b, 0)
        if ((w0 ushr 24) and 15 != 2) return null
        val w1 = rd32(b, 4)
        val v = IntArray(4) { rd32(b, 8 + 4 * it) }
        btea(v, -4, key5)
        val m = v7Mask(w0, w1, ts ushr 4)
        for (i in 0 until 4) v[i] = v[i] xor m[i]
        // Bit fields packed from bit 0 of word 2, least significant first.
        val type = bitsOf(v, 6, 5)
        val airborne = bitsOf(v, 102, 2)
        val course = bitsOf(v, 92, 10)
        if (type > 15 || airborne == 0 || course > 720) return null
        return V7(
            addr = w0 and 0xFFFFFF, addrType = (w0 ushr 28) and 3,
            stealth = (w1 ushr 22) and 1 == 1, noTrack = (w1 ushr 23) and 1 == 1,
            tstamp = bitsOf(v, 2, 4), aircraftType = type,
            alt = descale(bitsOf(v, 11, 13), 12, 1) - 1000,
            lat20 = bitsOf(v, 24, 20), lon20 = bitsOf(v, 44, 20),
            speedMs = descale(bitsOf(v, 73, 10), 8, 2) / 10.0,
            climbMs = descale(bitsOf(v, 83, 9), 6, 2) / 10.0,
            courseDeg = course / 2.0,
            airborne = airborne,
        )
    }

    /** A V7 packet as SoftRF's legacy_encode builds it. Used by the tests. */
    fun encodeV7(addr: Int, lat: Double, lon: Double, altM: Int, speedMs: Double, courseDeg: Double, climbMs: Double, ts: Int,
                 aircraftType: Int = 1, addrType: Int = 2, stealth: Boolean = false, noTrack: Boolean = false): IntArray {
        val w0 = (addr and 0xFFFFFF) or (2 shl 24) or ((addrType and 3) shl 28)
        val w1 = (if (stealth) 1 shl 22 else 0) or (if (noTrack) 1 shl 23 else 0) or (3 shl 24) or (3 shl 28)
        val v = IntArray(4)
        putBits(v, 2, 4, ts and 15)
        putBits(v, 6, 5, aircraftType)
        putBits(v, 11, 13, enscale(maxOf(0, altM + 1000), 12, 1, false))
        val la = (lat * 1e7).toLong().toInt()
        putBits(v, 24, 20, (la / 52 + (if (la and 0x40 != 0) (if (la < 0) -1 else 1) else 0)) and 0xFFFFF)
        putBits(v, 44, 20, ((lon * 1e7).toLong() / lonDivAt(lat)).toInt() and 0xFFFFF)
        putBits(v, 73, 10, enscale((speedMs * 10).roundToInt(), 8, 2, false))
        putBits(v, 83, 9, if (stealth) 0 else enscale((climbMs * 10).roundToInt(), 6, 2, true))
        putBits(v, 92, 10, (courseDeg * 2).toInt())
        putBits(v, 102, 2, if (speedMs >= 2) 2 else 1)
        putBits(v, 104, 6, 0x1d); putBits(v, 110, 5, 0x11)
        val m = v7Mask(w0, w1, ts ushr 4)
        for (i in 0 until 4) v[i] = v[i] xor m[i]
        btea(v, 4, key5)
        val b = IntArray(PAYLOAD)
        wr32(b, 0, w0); wr32(b, 4, w1)
        for (i in 0 until 4) wr32(b, 8 + 4 * i, v[i])
        return b
    }

    /** Completes a V7 position (20 low bits each) around [refLat], [refLon]. */
    fun unpackV7(lat20: Int, lon20: Int, refLat: Double, refLon: Double): Pair<Double, Double> {
        val rl = (refLat * 1e7).toLong().toInt() / 52
        var la = (lat20 - rl) and 0xFFFFF
        if (la >= 0x80000) la -= 0x100000
        val lat = (la + rl).toLong() * 52 / 1e7
        val div = lonDivAt(lat)
        val ro = (refLon * 1e7).toLong().toInt() / div
        var lo = (lon20 - ro) and 0xFFFFF
        if (lo >= 0x80000) lo -= 0x100000
        return lat to (lo + ro).toLong() * div / 1e7
    }

    /** Where [a] is, completed around the phone at [refLat], [refLon]. */
    fun position(a: FlarmAircraft, refLat: Double, refLon: Double) =
        if (a.v7) unpackV7(a.lat19, a.lon20, refLat, refLon) else unpackPosition(a.lat19, a.lon20, refLat, refLon)

    /** Payload → air chips (0/1), sync first: a 1 bit is the chip pair 01. */
    fun chips(payload: IntArray): IntArray {
        val f = payload.copyOf(FRAME)
        val c = frameCrc(f)
        f[24] = c ushr 8; f[25] = c and 0xFF
        val bytes = intArrayOf(0xF5, 0x31, 0xFA, 0xB6) + f
        val out = IntArray(bytes.size * 16)
        var k = 0
        for (b in bytes) for (i in 7 downTo 0) {
            val bit = (b ushr i) and 1
            out[k++] = 1 - bit; out[k++] = bit
        }
        return out
    }
}

/** One frame that passed its CRC, with the channel (0: 868.2, 1: 868.4 MHz) and its level over the noise. */
class FlarmFrame(val bytes: IntArray, val channel: Int, val snrDb: Double)

/**
 * Both channels from samples at 500 kS/s centred on 868.3 MHz: each is mixed to 0 Hz, low-passed and FM-demodulated;
 * at each of the 5 sampling phases a chip is the sum of the last 5 discriminator samples. A data bit is a pair of chips
 * (the second above the first is a 1), so the carrier offset needs no threshold. The sync is hunted at every phase and
 * both chip parities; the best phase of a match collects the 26 bytes.
 */
class FlarmDemodulator(private val onFrame: (FlarmFrame) -> Unit) {
    private inner class Chan(val idx: Int, offHz: Double) {
        val rotRe = FloatArray(SPS) { cos(-2 * PI * offHz * it / RATE).toFloat() }
        val rotIm = FloatArray(SPS) { sin(-2 * PI * offHz * it / RATE).toFloat() }
        val lp = FloatDecimator(1, 85_000.0 / RATE, 33)
        val mr = FloatArray(BLOCK); val mi = FloatArray(BLOCK)
        val yr = FloatArray(BLOCK); val yi = FloatArray(BLOCK)
        var pr = 1f; var pi = 0f
        var n = 0L
        var rot = 0
        val dring = FloatArray(SPS)
        var s = 0f
        val soft = Array(SPS) { FloatArray(RING) }
        val reg = Array(SPS) { IntArray(2) }
        val pw = FloatArray(RING)
        var pwAcc = 0f
        var blkSum = 0.0
        var blkN = 0
        val blocks = ArrayDeque<Double>()
        var nf = 0.0
        var grpOpen = false
        var grpN0 = 0L
        var best: Cand? = null
        val jobs = ArrayDeque<Cand>()
        val done = ArrayDeque<Long>()
    }

    private class Cand(val p: Int, val k0: Long, val inv: Boolean, val err: Int, val score: Float)

    private val ch = arrayOf(Chan(0, -100_000.0), Chan(1, 100_000.0))
    var syncs = 0
        private set
    var badCrc = 0
        private set

    fun feed(re: FloatArray, im: FloatArray, len: Int) {
        var off = 0
        while (off < len) {
            val m = minOf(BLOCK, len - off)
            for (c in ch) process(c, re, im, off, m)
            off += m
        }
    }

    private fun process(c: Chan, re: FloatArray, im: FloatArray, off: Int, len: Int) {
        for (i in 0 until len) {
            val a = re[off + i]; val b = im[off + i]
            c.mr[i] = a * c.rotRe[c.rot] - b * c.rotIm[c.rot]
            c.mi[i] = a * c.rotIm[c.rot] + b * c.rotRe[c.rot]
            c.rot = (c.rot + 1) % SPS
        }
        val m = c.lp.process(c.mr, c.mi, len, c.yr, c.yi)
        val kd = (RATE / (2 * PI) / 50_000.0).toFloat()
        for (i in 0 until m) {
            val sr = c.yr[i]; val si = c.yi[i]
            // arg(s · conj(prev))
            val d = kotlin.math.atan2(si * c.pr - sr * c.pi, sr * c.pr + si * c.pi) * kd
            c.pr = sr; c.pi = si
            val p = (c.n % SPS).toInt()
            val k = c.n / SPS
            val pwr = sr * sr + si * si
            c.pwAcc = if (p == 0) pwr else c.pwAcc + pwr
            if (p == SPS - 1) c.pw[(k and MASK).toInt()] = c.pwAcc
            c.blkSum += pwr
            if (++c.blkN >= NOISE_BLOCK) blockEnd(c)
            c.s += d - c.dring[p]
            c.dring[p] = d
            c.soft[p][(k and MASK).toInt()] = c.s
            val prev = c.soft[p][((k - 1) and MASK).toInt()]
            val r = (c.reg[p][(k and 1).toInt()] shl 1) or (if (c.s > prev) 1 else 0)
            c.reg[p][(k and 1).toInt()] = r
            val e = Integer.bitCount(r xor Flarm.SYNC)
            val ei = 32 - e
            if (c.n > 400 && (e <= 2 || ei <= 2) && c.done.none { abs(it - k) <= 6 }) {
                var score = 0f
                for (j in 0 until 32) score += abs(c.soft[p][((k - 2 * j) and MASK).toInt()] - c.soft[p][((k - 2 * j - 1) and MASK).toInt()])
                val cd = Cand(p, k, ei < e, minOf(e, ei), score)
                if (!c.grpOpen) { c.grpOpen = true; c.grpN0 = c.n; c.best = cd }
                else if (score > c.best!!.score) c.best = cd
            }
            if (c.grpOpen && c.n >= c.grpN0 + 3 * SPS) {
                c.grpOpen = false
                syncs++
                val b = c.best!!
                c.jobs.addLast(b)
                c.done.addLast(b.k0)
                while (c.done.size > 8) c.done.removeFirst()
            }
            while (c.jobs.isNotEmpty()) {
                val j = c.jobs.first()
                if (c.n < SPS * (j.k0 + DATA_CHIPS) + j.p + 1) break
                c.jobs.removeFirst()
                tryJob(c, j)
            }
            c.n++
        }
    }

    private fun tryJob(c: Chan, cd: Cand) {
        val raw = IntArray(Flarm.FRAME)
        for (i in 0 until DATA_CHIPS / 2) {
            val a = c.soft[cd.p][((cd.k0 + 1 + 2 * i) and MASK).toInt()]
            val b = c.soft[cd.p][((cd.k0 + 2 + 2 * i) and MASK).toInt()]
            val bit = (if (b > a) 1 else 0) xor (if (cd.inv) 1 else 0)
            raw[i shr 3] = ((raw[i shr 3] shl 1) or bit) and 0xFF
        }
        var f = raw
        var ok = Flarm.checkCrc(f)
        if (!ok) { f = IntArray(Flarm.FRAME) { raw[it] xor 0xFF }; ok = Flarm.checkCrc(f) }
        if (!ok) { if (cd.err <= 1) badCrc++; return }
        var pwr = 0.0
        var cnt = 0
        for (k in cd.k0 - 31..cd.k0 + DATA_CHIPS) { pwr += c.pw[(k and MASK).toInt()]; cnt++ }
        pwr /= cnt.toDouble() * SPS
        val nf = if (c.nf > 0) c.nf else 1e-12
        onFrame(FlarmFrame(f, c.idx, 10 * log10(maxOf(pwr - nf, nf * 0.01) / nf)))
    }

    private fun blockEnd(c: Chan) {
        c.blocks.addLast(c.blkSum / c.blkN)
        if (c.blocks.size > 250) c.blocks.removeFirst()
        if (c.blocks.size >= 3) c.nf = maxOf(c.blocks.sorted()[c.blocks.size / 10] * 1.05, 1e-12)
        c.blkSum = 0.0; c.blkN = 0
    }

    companion object {
        const val RATE = 500_000.0
        private const val SPS = 5
        private const val RING = 1024
        private const val MASK = (RING - 1).toLong()
        private const val DATA_CHIPS = Flarm.FRAME * 16
        private const val BLOCK = 8192
        private const val NOISE_BLOCK = 10_000
    }
}

/** One aircraft that broadcasts openly: the raw position bits stay, the screen completes them around the phone. */
data class FlarmAircraft(
    val id: Int,
    val addrType: Int,
    val type: Int,
    val altM: Int,
    val airborne: Boolean,
    val speedKmh: Double,
    val trackDeg: Double,
    val climbMs: Double,
    val lat19: Int,
    val lon20: Int,
    val channel: Int,
    val snrDb: Double,
    val packets: Int,
    val lastSeenMs: Long,
    /** Sent on the protocol since 2024: [lat19] and [lon20] then hold its 20-bit position fields. */
    val v7: Boolean = false,
) {
    val hexId get() = "%06X".format(id)
}

/**
 * Turns frames into aircraft, from either protocol. A wrong key time can still give a packet that looks right, and
 * noise can set any flag, so an aircraft counts only once a second packet from the same address agrees with the first
 * (type, privacy, rough position). Aircraft flagged no-track, stealth or anonymous are counted and nothing else is
 * kept: only a salted hash of their address, in memory, so they are not counted twice.
 */
class FlarmTracker {
    /** One decoded packet, either protocol, reduced to what is shown and compared. */
    private class Fix(
        val addr: Int, val addrType: Int, val private: Boolean, val type: Int, val alt: Int, val airborne: Boolean,
        val speedMs: Double, val trackDeg: Double, val climbMs: Double, val lat: Int, val lon: Int, val v7: Boolean,
    )

    private class Pending(val t: Long, val p: Fix, val channel: Int, val snr: Double)

    val aircraft = HashMap<Int, FlarmAircraft>()
    private val pending = HashMap<Long, List<Pending>>()
    private val privateSet = HashSet<Long>()
    private val undecoded = HashSet<Long>()
    private val salt = java.security.SecureRandom().nextLong()
    var privateCount = 0
        private set
    /** Senders on the newer protocol heard but not decoded yet (or whose packets never agreed: a wrong clock). */
    val newProtocolDevices get() = undecoded.size
    var packets = 0
        private set

    private fun hash(id: Int): Long {
        var h = id.toLong() xor salt
        h *= -0xae502812aa7333L; h = h xor (h ushr 33); h *= -0x3b314601e57a13adL; h = h xor (h ushr 33)
        return h
    }

    private fun near(a: Int, b: Int, mask: Int, within: Int): Boolean { val d = (a - b) and mask; return minOf(d, mask + 1 - d) < within }

    /** V6 positions are in 128e-7° steps (19 bits of latitude), V7 in 52e-7° or more (20 bits): about 5 km either way. */
    private fun agrees(q: Fix, f: Fix) =
        q.v7 == f.v7 && q.private == f.private && q.type == f.type &&
            near(q.lat, f.lat, if (q.v7) 0xFFFFF else 0x7FFFF, if (q.v7) 10_000 else 4000) &&
            near(q.lon, f.lon, 0xFFFFF, if (q.v7) 10_000 else 4000)

    private fun fix(p: Flarm.V6): Fix {
        val (speed, track, climb) = Flarm.velocity(p)
        return Fix(p.addr, p.addrType, p.private, p.aircraftType, p.alt, p.airborne, speed, track, climb, p.lat19, p.lon20, false)
    }

    private fun fix(p: Flarm.V7) =
        Fix(p.addr, p.addrType, p.private, p.aircraftType, p.alt, p.airborne >= 2, p.speedMs, p.courseDeg, p.climbMs, p.lat20, p.lon20, true)

    /** [unixMs] is the wall clock, which the keys need. */
    fun update(f: FlarmFrame, unixMs: Long) {
        val type = Flarm.packetType(f.bytes)
        if (type != 0 && type != 2) return
        val id = Flarm.packetAddr(f.bytes)
        val h = hash(id)
        val ts = (unixMs / 1000).toInt()
        // Near a key change the sender's clock may already be on the next step, or still on the last one.
        val d: List<Fix> = if (type == 0) {
            val frac = ts and 63
            listOfNotNull(ts, if (frac >= 52) ts + 64 else null, if (frac < 12) ts - 64 else null).mapNotNull { Flarm.decodeV6(f.bytes, it)?.let(::fix) }
        } else {
            val frac = ts and 15
            listOfNotNull(ts, if (frac >= 13) ts + 16 else null, if (frac < 3) ts - 16 else null).mapNotNull { Flarm.decodeV7(f.bytes, it)?.let(::fix) }
        }
        if (type == 2 && h !in privateSet && id !in aircraft) undecoded.add(h)
        if (d.isEmpty()) return
        aircraft[id]?.let { a ->
            val known = Fix(a.id, a.addrType, false, a.type, a.altM, a.airborne, 0.0, 0.0, 0.0, a.lat19, a.lon20, a.v7)
            d.firstOrNull { !it.private && agrees(it, known) }?.let { apply(it, unixMs, f.channel, f.snrDb); return }
        }
        if (h in privateSet) return
        pending.values.removeAll { unixMs - it.first().t > 60_000 }
        pending[h]?.let { list ->
            for (q in d) for (first in list) if (agrees(q, first.p)) {
                pending.remove(h)
                undecoded.remove(h)
                if (q.private) { privateSet.add(h); privateCount = privateSet.size; return }
                apply(first.p, first.t, first.channel, first.snr)
                apply(q, unixMs, f.channel, f.snrDb)
                return
            }
        }
        // A flagged candidate keeps nothing but the fields needed to match the next packet, and is dropped within a minute.
        pending[h] = d.map { Pending(unixMs, it, f.channel, f.snrDb) }
    }

    private fun apply(p: Fix, t: Long, channel: Int, snr: Double) {
        packets++
        val prev = aircraft[p.addr]
        aircraft[p.addr] = FlarmAircraft(
            p.addr, p.addrType, p.type, p.alt, p.airborne, p.speedMs * 3.6, p.trackDeg, p.climbMs,
            p.lat, p.lon, channel, snr, (prev?.packets ?: 0) + 1, t, p.v7,
        )
    }

    fun prune(nowMs: Long, keepMs: Long = 60_000) { aircraft.values.removeAll { nowMs - it.lastSeenMs > keepMs } }
}

/** HackRF bytes at 2 MS/s with 868.3 MHz at +fs/4 → 500 kS/s → both channels. */
class FlarmReceiver(onFrame: (FlarmFrame) -> Unit) {
    private val dec = Decimator(4, 150_000.0 / SAMPLE_RATE, 80)
    private val re = FloatArray(131072 / 2 / 4 + 8)
    private val im = FloatArray(re.size)
    val demod = FlarmDemodulator(onFrame)

    fun feed(buf: ByteArray, len: Int) {
        val n = dec.process(buf, len, re, im, 0)
        demod.feed(re, im, n)
    }

    companion object {
        const val SAMPLE_RATE = 2_000_000
        const val CENTRE_HZ = 868_300_000L
    }
}
