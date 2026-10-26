package com.allnetworktools.data.sdr

import kotlin.math.atan2
import kotlin.math.roundToInt

/*
 * Tyre pressure sensors (TPMS) on 433.92 MHz (Europe) and 315 MHz (America, Asia). The packet formats are ported from
 * rtl_433 (src/devices/tpms_*.c, GPL-2.0-or-later), with its bit handling: rows of FSK bits sliced at the sensor's bit
 * time, a preamble search, then Manchester or differential Manchester decoding and the sensor's own checksum.
 */

/** One sensor transmission that passed its checksum. Pressure is what the sensor reports, absolute or gauge. */
data class TpmsReading(
    val protocol: String,
    val id: String,
    val pressureKpa: Double?,
    val temperatureC: Double?,
    val batteryOk: Boolean? = null,
    val moving: Boolean? = null,
    val wheel: Int? = null,
    val timeMs: Long = 0,
)

/** rtl_433's bit buffer operations, on rows holding one 0/1 byte per bit. */
internal object TpmsBits {
    fun pattern(hex: String, bits: Int): ByteArray = ByteArray(bits) { i ->
        val byte = hex.substring(i / 8 * 2, i / 8 * 2 + 2).toInt(16)
        ((byte shr (7 - i % 8)) and 1).toByte()
    }

    private fun at(row: ByteArray, i: Int): Int = if (i in row.indices) row[i].toInt() else 0

    /** First index at or after [from] where [pat] starts, else the row length. */
    fun search(row: ByteArray, from: Int, pat: ByteArray): Int {
        var i = maxOf(0, from)
        outer@ while (i + pat.size <= row.size) {
            for (k in pat.indices) if (row[i + k] != pat[k]) { i++; continue@outer }
            return i
        }
        return row.size
    }

    /** Pairs "01" → 1 and "10" → 0 until a pair breaks the rule; returns the bits and where reading stopped. */
    fun manchester(row: ByteArray, start: Int, max: Int): Pair<ByteArray, Int> {
        var len = row.size
        if (max > 0 && len > start + max * 2) len = start + max * 2
        val out = ArrayList<Byte>()
        var i = start
        while (i < len) {
            val b1 = at(row, i++)
            val b2 = at(row, i++)
            if (b1 == b2) break
            out += b2.toByte()
        }
        return out.toByteArray() to i
    }

    /** Differential Manchester: a transition mid-bit always, at the bit edge for a 0 only. */
    fun diffManchester(row: ByteArray, start: Int, max: Int): Pair<ByteArray, Int> {
        var len = row.size
        if (max > 0 && len > start + max * 2) len = start + max * 2
        val out = ArrayList<Byte>()
        var i = start
        var bit2 = 0
        // The first long pulse sets the clock; one short pulse may be skipped to get in step.
        while (i < len) {
            val bit1 = at(row, i++)
            bit2 = at(row, i++)
            val bit3 = at(row, i)
            if (bit1 != bit2) {
                if (bit2 != bit3) out += 0
                else { bit2 = bit1; i -= 1; break }
            } else {
                bit2 = 1 - bit1; i -= 2; break
            }
        }
        while (i < len) {
            val bit1 = at(row, i++)
            if (bit1 == bit2) break // clock missing
            bit2 = at(row, i++)
            out += (if (bit1 == bit2) 1 else 0).toByte()
        }
        return out.toByteArray() to i
    }

    /** Bytes MSB first from [from], the last one padded with zeros. */
    fun bytes(bits: ByteArray, from: Int = 0, count: Int = bits.size - from): IntArray {
        val out = IntArray((count + 7) / 8)
        for (k in 0 until count) {
            val i = from + k
            if (i < bits.size && bits[i].toInt() == 1) out[k / 8] = out[k / 8] or (0x80 shr (k % 8))
        }
        return out
    }

    fun crc8(b: IntArray, n: Int, poly: Int, init: Int): Int {
        var r = init
        for (i in 0 until n) {
            r = r xor b[i]
            repeat(8) { r = if (r and 0x80 != 0) ((r shl 1) xor poly) and 0xFF else (r shl 1) and 0xFF }
        }
        return r
    }

    fun crc16(b: IntArray, n: Int, poly: Int, init: Int): Int {
        var r = init
        for (i in 0 until n) {
            r = r xor (b[i] shl 8)
            repeat(8) { r = if (r and 0x8000 != 0) ((r shl 1) xor poly) and 0xFFFF else (r shl 1) and 0xFFFF }
        }
        return r
    }

    fun hex(vararg v: Int): String = v.joinToString("") { "%02x".format(it and 0xFF) }
}

/** One sensor family: the bit time it is sliced at, and its decoder over a row of bits. */
internal class TpmsProtocol(val bitUs: Int, val decode: (ByteArray) -> List<TpmsReading>)

object Tpms {
    private const val PSI = 6.894757

    private fun scan(row: ByteArray, pat: ByteArray, need: Int, step: Int, at: (Int) -> TpmsReading?): List<TpmsReading> {
        val out = ArrayList<TpmsReading>()
        var pos = 0
        while (true) {
            pos = TpmsBits.search(row, pos, pat)
            if (pos + need > row.size) break
            at(pos + pat.size)?.let { out += it }
            pos += step
        }
        return out
    }

    private val pre16 = TpmsBits.pattern("aaa9", 16)
    private val pre24 = TpmsBits.pattern("aaaaa9", 24)
    private val pre32 = TpmsBits.pattern("aaaaaaa9", 32)

    /** Citroën, Peugeot, Fiat, Mitsubishi (VDO): 10 bytes, XOR of bytes 1 to 9 is zero. */
    private fun citroen(row: ByteArray) = scan(row, pre16, 178, 2) { p ->
        val (bits, _) = TpmsBits.manchester(row, p, 88)
        if (bits.size < 80) return@scan null
        val b = TpmsBits.bytes(bits, 0, 80)
        if (b[6] == 0 || b[7] == 0) return@scan null
        if ((1..9).fold(0) { a, i -> a xor b[i] } != 0) return@scan null
        TpmsReading("Citroën / Peugeot", TpmsBits.hex(b[1], b[2], b[3], b[4]), b[6] * 1.364, b[7] - 50.0)
    }

    /** Hyundai, Kia and others with Continental VDO sensors: 10 bytes, CRC-8 0x07 init 0xaa. */
    private fun hyundaiVdo(row: ByteArray) = scan(row, pre32, 112, 2) { p ->
        val (bits, _) = TpmsBits.manchester(row, p, 80)
        if (bits.size < 80) return@scan null
        val b = TpmsBits.bytes(bits, 0, 80)
        if (TpmsBits.crc8(b, 9, 0x07, 0xaa) != b[9]) return@scan null
        TpmsReading("Hyundai / Kia (VDO)", TpmsBits.hex(b[1], b[2], b[3], b[4]), b[6] * 1.375, b[7] - 50.0)
    }

    /** Renault and Dacia: 9 bytes, CRC-8 0x07, 24-bit little-endian id. */
    private fun renault(row: ByteArray) = scan(row, pre16, 160, 15) { p ->
        val (bits, _) = TpmsBits.manchester(row, p, 160)
        if (bits.size < 72) return@scan null
        val b = TpmsBits.bytes(bits, 0, 72)
        if (TpmsBits.crc8(b, 8, 0x07, 0x00) != b[8]) return@scan null
        TpmsReading("Renault / Dacia", TpmsBits.hex(b[5], b[4], b[3]), (((b[0] and 0x03) shl 8) or b[1]) * 0.75, b[2] - 30.0)
    }

    /** Ford (VDO): 8 bytes, sum of bytes 0 to 6; pressure in quarter PSI. */
    private fun ford(row: ByteArray) = scan(row, pre16, 144, 15) { p ->
        val (bits, _) = TpmsBits.manchester(row, p, 160)
        if (bits.size < 64) return@scan null
        val b = TpmsBits.bytes(bits, 0, 64)
        if ((0..6).sumOf { b[it] } and 0xFF != b[7]) return@scan null
        val mode = b[6] and 0x4c
        if (mode != 0x08 && mode != 0x04 && mode != 0x44 || b[6] and 0x90 != 0) return@scan null
        val psi = ((((b[6] and 0x20) shl 3) or b[4]) * 0.25)
        val temp = if (b[5] and 0x80 == 0) (b[5] and 0x7f) - 56.0 else null
        TpmsReading("Ford", TpmsBits.hex(b[0], b[1], b[2], b[3]), psi * PSI, temp, moving = mode == 0x44)
    }

    /** Toyota (Pacific PMV-C210): differential Manchester, CRC-8 0x07 init 0x80, pressure sent twice. */
    private fun toyota(row: ByteArray) = scan(row, TpmsBits.pattern("a9e0", 12), 156, 2) { p ->
        val start = p - 1 // the pattern's last bit belongs to the data
        val (bits, end) = TpmsBits.diffManchester(row, start, 80)
        if (end - start < 144 || bits.size < 72) return@scan null
        val b = TpmsBits.bytes(bits, 0, 72)
        if (TpmsBits.crc8(b, 8, 0x07, 0x80) != b[8]) return@scan null
        val p1 = ((b[4] and 0x7f) shl 1) or (b[5] shr 7)
        val temp = ((b[5] and 0x7f) shl 1) or (b[6] shr 7)
        if (p1 != (b[7] xor 0xff)) return@scan null
        TpmsReading("Toyota", TpmsBits.hex(b[0], b[1], b[2], b[3]), (p1 * 0.25 - 7.0) * PSI, temp - 40.0)
    }

    /** Toyota (Pacific PMV-107J, 315 MHz): 100 µs bits, differential Manchester, CRC-8 0x13. */
    private fun pmv107j(row: ByteArray) = scan(row, TpmsBits.pattern("f8", 6), 134, 2) { p ->
        val (bits, end) = TpmsBits.diffManchester(row, p, 70)
        if (end - p < 134 || bits.size < 66) return@scan null
        val b = IntArray(9)
        b[0] = (bits[0].toInt() shl 1) or bits[1].toInt()
        TpmsBits.bytes(bits, 2, 64).copyInto(b, 1)
        if (TpmsBits.crc8(b, 8, 0x13, 0x00) != b[8]) return@scan null
        if (b[5] != (b[6] xor 0xff)) return@scan null
        val id = (b[0] shl 26) or (b[1] shl 18) or (b[2] shl 10) or (b[3] shl 2) or (b[4] shr 6)
        TpmsReading("Toyota (PMV-107J)", "%07x".format(id), (b[5] - 40.0) * 2.48, b[7] - 40.0, batteryOk = b[4] and 0x20 == 0)
    }

    /** Kia Rio and some Hyundai: 50 µs bits, 0xed71 sync, 5-bit CRC-8 0x07 init 0x76. */
    private fun kia(row: ByteArray) = scan(row, TpmsBits.pattern("ed71", 16), 154, 2) { p ->
        val (bits, end) = TpmsBits.manchester(row, p, 138)
        if (end - p < 138) return@scan null
        val b = TpmsBits.bytes(bits, 0, 72)
        if (b[8] and 0x07.inv() and 0xFF != TpmsBits.crc8(b, 8, 0x07, 0x76)) return@scan null
        val pressure = ((b[0] shl 4) or (b[1] shr 4)) and 0xFF
        val temp = ((b[1] shl 4) or (b[2] shr 4)) and 0xFF
        val id = (b[2] shl 28) or (b[3] shl 20) or (b[4] shl 12) or (b[5] shl 4) or (b[6] shr 4)
        TpmsReading("Kia", "%08x".format(id), pressure / 5.0 * PSI, temp - 50.0)
    }

    /** BMW Gen4/Gen5 (HUF, Continental, Schrader) and Audi: 25 µs bits, inverted Manchester, CRC-8 0x2f init 0xaa. */
    private fun bmw(row: ByteArray): List<TpmsReading> {
        val pos = TpmsBits.search(row, 0, TpmsBits.pattern("aa59", 16))
        if (pos >= row.size) return emptyList()
        val (bits, _) = TpmsBits.manchester(row, pos + 16, 88)
        val len = when {
            bits.size >= 88 -> 11
            bits.size >= 64 -> 8
            else -> return emptyList()
        }
        val b = TpmsBits.bytes(ByteArray(len * 8) { (1 - bits[it]).toByte() })
        if (TpmsBits.crc8(b, len, 0x2f, 0xaa) != 0) return emptyList()
        return listOf(TpmsReading(if (len == 11) "BMW" else "Audi", TpmsBits.hex(b[1], b[2], b[3], b[4]), b[5] * 2.45, b[6] - 52.0))
    }

    /** Porsche Boxster/Cayman (987): differential Manchester, CRC-16 0x1021. */
    private fun porsche(row: ByteArray) = scan(row, TpmsBits.pattern("333320", 20), 100, 2) { p ->
        val (bits, _) = TpmsBits.diffManchester(row, p, 80)
        if (bits.size < 80) return@scan null
        val b = TpmsBits.bytes(bits, 0, 80)
        if (TpmsBits.crc16(b, 10, 0x1021, 0xffff) != 0) return@scan null
        TpmsReading("Porsche", TpmsBits.hex(b[0], b[1], b[2], b[3]), b[4] * 5 / 2 - 100.0, b[5] - 40.0)
    }

    /** Hyundai Elantra, Honda Civic (TRW): 0x7155 sync, CRC-8 0x07. */
    private fun elantra(row: ByteArray) = scan(row, TpmsBits.pattern("7155", 16), 128, 15) { p ->
        val (bits, _) = TpmsBits.manchester(row, p, 64)
        if (bits.size < 64) return@scan null
        val b = TpmsBits.bytes(bits, 0, 64)
        if (TpmsBits.crc8(b, 8, 0x07, 0x00) != 0) return@scan null
        TpmsReading("Hyundai / Honda (TRW)", TpmsBits.hex(b[2], b[3], b[4], b[5]), b[0] + 60.0, b[1] - 50.0, batteryOk = b[6] and 0x02 == 0)
    }

    /** Solar truck sensors with a wheel number: XOR of 9 bytes is zero, pressure in kPa. */
    private fun truck(row: ByteArray) = scan(row, pre24, 160, 2) { p ->
        val (bits, _) = TpmsBits.manchester(row, p, 76)
        if (bits.size < 76) return@scan null
        val b = TpmsBits.bytes(bits, 4, 72)
        if (b[0] == 0 && b[1] == 0 && b[2] == 0 && b[3] == 0) return@scan null
        if (b.fold(0) { a, v -> a xor v } != 0) return@scan null
        // Up to 12 bar by the specification: a plain XOR also passes other sensors' packets read out of step.
        if ((((b[5] and 0x0f) shl 8) or b[6]) > 1300) return@scan null
        val flags = b[5] shr 4
        TpmsReading(
            "Truck", TpmsBits.hex(b[0], b[1], b[2], b[3]), (((b[5] and 0x0f) shl 8) or b[6]).toDouble(), b[7].toByte().toDouble(),
            batteryOk = when (flags and 0x3) { 3 -> true; 0 -> false; else -> null }, wheel = b[4],
        )
    }

    /** Most specific first: when two formats accept the same packet, the CRC-checked one wins. */
    internal val protocols = listOf(
        TpmsProtocol(52, ::hyundaiVdo), TpmsProtocol(52, ::renault), TpmsProtocol(52, ::toyota), TpmsProtocol(52, ::porsche),
        TpmsProtocol(52, ::ford), TpmsProtocol(52, ::citroen), TpmsProtocol(52, ::truck),
        TpmsProtocol(50, ::kia), TpmsProtocol(50, ::elantra), TpmsProtocol(100, ::pmv107j), TpmsProtocol(25, ::bmw),
    )

    val bitTimesUs = protocols.map { it.bitUs }.distinct()

    /** Every reading in a row of bits sliced at [bitUs], in both FSK polarities; one per sensor id. */
    fun decodeRow(row: ByteArray, bitUs: Int): List<TpmsReading> {
        val inverted = ByteArray(row.size) { (1 - row[it]).toByte() }
        val out = LinkedHashMap<String, TpmsReading>()
        for (proto in protocols) {
            if (proto.bitUs != bitUs) continue
            for (r in proto.decode(row) + proto.decode(inverted)) out.putIfAbsent(r.id, r)
        }
        return out.values.toList()
    }
}

/**
 * FSK bursts at [sampleRate] in, readings out: a squelch finds each burst, the frequency discriminator is sliced at the
 * middle of its two tones, and the runs become bits at every bit time the known sensors use.
 */
class TpmsDemodulator(private val sampleRate: Double = 250_000.0, private val onReading: (TpmsReading) -> Unit) {
    private var pr = 0f
    private var pi = 0f
    private var power = 0f
    private var floor = -1f
    private var inBurst = false
    private var quiet = 0
    private val freq = FloatArray(MAX_BURST)
    private var len = 0
    private val recent = HashMap<String, Long>()
    private var n = 0L

    fun feed(re: FloatArray, im: FloatArray, count: Int) {
        for (i in 0 until count) {
            val r = re[i]
            val q = im[i]
            val p = r * r + q * q
            power += (p - power) * 0.25f
            // Noise floor: the average power between bursts, frozen while one lasts; it settles fast while the filters fill.
            if (floor < 0) floor = power
            if (!inBurst) floor += (power - floor) * (if (n < WARMUP) 0.05f else 1e-3f)
            val f = atan2(q * pr - r * pi, r * pr + q * pi)
            pr = r; pi = q
            val loud = n > WARMUP && power > floor * SQUELCH && power > 1e-7f
            if (loud) {
                if (!inBurst) { inBurst = true; len = 0 }
                quiet = 0
            }
            if (inBurst) {
                if (len < MAX_BURST) freq[len++] = f
                if (!loud && ++quiet > END_GAP || len == MAX_BURST) {
                    inBurst = false
                    burst(len - quiet.coerceAtMost(len))
                    // A "burst" as long as the limit is more likely a louder noise floor: let the floor catch up.
                    if (len == MAX_BURST) floor += (power - floor) * 0.5f
                }
            }
            n++
        }
    }

    private fun burst(count: Int) {
        if (count < 200) return
        val sorted = freq.copyOf(count).also { it.sort() }
        val lo = sorted[count / 10]
        val hi = sorted[count * 9 / 10]
        if (hi - lo < 0.05f) return
        val mid = (lo + hi) / 2
        // Runs of one level, then rows of bits at each bit time.
        val levels = ArrayList<Int>()
        val lengths = ArrayList<Int>()
        for (i in 0 until count) {
            val l = if (freq[i] > mid) 1 else 0
            if (levels.isNotEmpty() && levels.last() == l) lengths[lengths.lastIndex]++ else { levels += l; lengths += 1 }
        }
        val now = System.currentTimeMillis()
        for (us in Tpms.bitTimesUs) {
            val spb = sampleRate * us / 1e6
            val row = ArrayList<Byte>()
            for (k in levels.indices) repeat((lengths[k] / spb).roundToInt().coerceAtMost(64)) { row += levels[k].toByte() }
            for (r in Tpms.decodeRow(row.toByteArray(), us)) {
                // A sensor repeats its packet several times per burst: one reading per burst.
                val key = "${r.protocol}/${r.id}/${r.pressureKpa}/${r.temperatureC}"
                val last = recent[key]
                if (last != null && n - last < sampleRate * 2) continue
                recent[key] = n
                onReading(r.copy(timeMs = now))
            }
        }
        if (recent.size > 256) recent.entries.removeAll { n - it.value > sampleRate * 10 }
    }

    companion object {
        /** Power over the noise floor that opens the squelch (10 dB). */
        private const val SQUELCH = 10f
        /** Samples (2 ms) the filters and the noise floor need to settle before the squelch may open. */
        private const val WARMUP = 512
        /** Quiet samples that end a burst (about 0.3 ms). */
        private const val END_GAP = 80
        /** 60 ms: longer than any sensor's packet train. */
        private const val MAX_BURST = 15_000
    }
}

/** HackRF bytes at 2 MS/s tuned fs/4 below the sensors' frequency in; checked readings out. */
class TpmsReceiver(onReading: (TpmsReading) -> Unit) {
    private val d1 = Decimator(8, 0.06, taps = 48)
    private val re = FloatArray(32768)
    private val im = FloatArray(32768)
    private val demod = TpmsDemodulator(SAMPLE_RATE / 8.0, onReading)

    fun feed(buf: ByteArray, len: Int) {
        val k = d1.process(buf, len, re, im, 0)
        demod.feed(re, im, k)
    }

    companion object {
        const val SAMPLE_RATE = 2_000_000
        const val EUROPE_HZ = 433_920_000L
        const val AMERICA_HZ = 315_000_000L
    }
}

/** What is known about one sensor, from its successive transmissions. */
class TpmsSensor(val protocol: String, val id: String) {
    var last: TpmsReading? = null
    var count = 0
    var firstSeenMs = 0L
    var lastSeenMs = 0L
}

class TpmsTracker {
    val sensors = LinkedHashMap<String, TpmsSensor>()

    fun update(r: TpmsReading) {
        val s = sensors.getOrPut("${r.protocol}/${r.id}") { TpmsSensor(r.protocol, r.id).also { it.firstSeenMs = r.timeMs } }
        s.last = r
        s.count++
        s.lastSeenMs = r.timeMs
    }

    /** Cars drive past: sensors not heard for half an hour are forgotten. */
    fun prune(nowMs: Long) {
        sensors.values.removeAll { nowMs - it.lastSeenMs > 30 * 60_000 }
    }
}
