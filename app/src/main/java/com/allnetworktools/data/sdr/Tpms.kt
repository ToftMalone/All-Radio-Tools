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

/** How a sensor's bursts become rows of bits, as in rtl_433's pulse slicers. */
internal enum class TpmsSlicer {
    /** Frequency keyed, one bit per bit time of each tone. */
    FskPcm,
    /** On/off keyed, one bit per bit time of carrier or silence. */
    OokPcm,
    /** On/off keyed Manchester, [TpmsProtocol.bitUs] the half-bit: rtl_433's "Manchester, zero bit" slicer. */
    OokManchesterZeroBit,
}

/** One sensor family: how it is sliced, at what bit time, and its decoder over a row of bits. */
internal class TpmsProtocol(val bitUs: Int, val decode: (ByteArray) -> List<TpmsReading>, val slicer: TpmsSlicer = TpmsSlicer.FskPcm, val resetUs: Int = 0)

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
        TpmsReading("Toyota (PMV-107J)", "%08x".format(id), (b[5] - 40.0) * 2.48, b[7] - 40.0, batteryOk = b[4] and 0x20 == 0)
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

    /** Honda (TRW PPA-GF33, 315 MHz): 50 µs FSK, a 23-bit marker, Manchester, CRC-8 0x07; pressure in 0.2 PSI. */
    private fun hondaTrw(row: ByteArray) = scan(row, TpmsBits.pattern("dae354", 23), 23 + 128, 2) { p ->
        val (bits, _) = TpmsBits.manchester(row, p, 64)
        if (bits.size < 64) return@scan null
        val b = TpmsBits.bytes(bits, 0, 64)
        if (TpmsBits.crc8(b, 7, 0x07, 0x00) != b[7]) return@scan null
        // TRW GQ4-44T / Elantra frames can fit this layout too, and then read as a few PSI: rtl_433 refuses those.
        if (b[0] in 1..49) return@scan null
        TpmsReading("Honda (TRW)", TpmsBits.hex(b[2], b[3], b[4], b[5]), b[0] * 0.2 * PSI, b[1] - 50.0)
    }

    /** Schrader (FCC MRXGG4): 68 bits, the first nibble a sync, CRC-8 0x07 from 0xf0; 25 mbar a step. */
    private fun schrader(row: ByteArray): List<TpmsReading> {
        if (row.size != 68) return emptyList()
        val b = TpmsBits.bytes(row, 4, 64)
        if (TpmsBits.crc8(b, 7, 0x07, 0xf0) != b[7]) return emptyList()
        val id = ((b[1] and 0x0f) shl 24) or (b[2] shl 16) or (b[3] shl 8) or b[4]
        return listOf(TpmsReading("Schrader", "%07X".format(id), b[5] * 2.5, b[6] - 50.0))
    }

    /** Schrader EG53MA4 (Opel, Saab, Vauxhall, Chevrolet and GM): 120 bits, a byte sum; temperature in °F. */
    private fun schraderEg53ma4(row: ByteArray): List<TpmsReading> {
        if (row.size != 120) return emptyList()
        val b = TpmsBits.bytes(row, 40, 80)
        if (b[1] == 0 && b[2] == 0 && b[4] == 0 && b[5] == 0 && b[7] == 0 && b[8] == 0) return emptyList()
        if ((0 until 9).sumOf { b[it] } and 0xff != b[9]) return emptyList()
        val id = (b[4] shl 16) or (b[5] shl 8) or b[6]
        return listOf(TpmsReading("Schrader (EG53MA4)", "%06X".format(id), b[7] * 2.5, (b[8] - 32) * 5 / 9.0))
    }

    /**
     * Schrader SMD3MA4 (Subaru) and 3039 (Nissan, Infiniti, Renault Koleos): a 0x555e preamble, 38 Manchester bits
     * read 10 as 1, a 2-bit sum; pressure in 0.2 PSI, no temperature. (Nissan's MRXNIS315G3 sends the same frame
     * with 0.25 PSI steps: they cannot be told apart.)
     */
    private fun schraderSmd3ma4(row: ByteArray): List<TpmsReading> {
        if (row.size < 36 / 2 + 2 * 38 || row.size >= 36 + 2 * 38 + 8) return emptyList()
        val pos = TpmsBits.search(row, 0, TpmsBits.pattern("555e", 16))
        if (pos >= row.size) return emptyList()
        val start = pos + 14
        if (start + 2 * 38 > row.size) return emptyList()
        val (bits, end) = TpmsBits.manchester(row, start, 38)
        if (end != start + 2 * 38) return emptyList()
        val b = TpmsBits.bytes(ByteArray(38) { (1 - bits[it]).toByte() })
        if (b[0] == 0 && b[1] == 0 && b[2] == 0 && b[3] == 0) return emptyList()
        var sum = 0
        for (i in 0 until 5) for (s in 0 until 4) sum += (b[i] shr (2 * s)) and 3
        if (sum and 3 != 1) return emptyList()
        val id = ((b[0] and 0x0f) shl 20) or (b[1] shl 12) or (b[2] shl 4) or (b[3] shr 4)
        val p = ((b[3] and 0x0f) shl 4) or (b[4] shr 4)
        return listOf(TpmsReading("Schrader (SMD3MA4)", "%06X".format(id), p * 0.2 * PSI, null))
    }

    /** Schrader MRXBC5A4 (BMW): 61 bits after a fixed 0x7fff, a 2-bit check over id and pressure; kPa. */
    private fun schraderMrxbc5a4(row: ByteArray): List<TpmsReading> {
        if (row.size != 61) return emptyList()
        if (TpmsBits.search(row, 0, TpmsBits.pattern("7fff", 16)) != 0) return emptyList()
        val b = TpmsBits.bytes(row, 16, 46)
        val id = ((b[0] and 0x1f) shl 19) or (b[1] shl 11) or (b[2] shl 3) or (b[3] shr 5)
        if (id == 0 || id == 0xFFFFFF) return emptyList()
        var even = 0
        var ones = 0
        for (i in 3 until 38) if ((b[i / 8] shr (7 - i % 8)) and 1 == 1) { ones++; if ((i - 3) % 2 == 0) even++ }
        if ((even + 2 * ones - 1) and 3 != (b[4] shr 2) and 3) return emptyList()
        val p = ((b[3] and 0x1f) shl 4) or (b[4] shr 4)
        val temp = ((b[4] and 0x03) shl 5) or (b[5] shr 3)
        // The 2-bit check lets one noise burst in four through: a tyre's range is the second filter.
        if (p > 450 || temp - 50 < -40 || temp - 50 > 85) return emptyList()
        return listOf(TpmsReading("Schrader (BMW)", "%06X".format(id), p.toDouble(), temp - 50.0))
    }

    /** Most specific first: when two formats accept the same packet, the CRC-checked one wins. */
    internal val protocols = listOf(
        TpmsProtocol(52, ::hyundaiVdo), TpmsProtocol(52, ::renault), TpmsProtocol(52, ::toyota), TpmsProtocol(52, ::porsche),
        TpmsProtocol(52, ::ford), TpmsProtocol(52, ::citroen), TpmsProtocol(52, ::truck),
        TpmsProtocol(50, ::kia), TpmsProtocol(50, ::elantra), TpmsProtocol(50, ::hondaTrw), TpmsProtocol(100, ::pmv107j),
        TpmsProtocol(25, ::bmw),
        // On/off keyed, as rtl_433 slices them (Schrader).
        TpmsProtocol(120, ::schrader, TpmsSlicer.OokManchesterZeroBit, 480),
        TpmsProtocol(123, ::schraderEg53ma4, TpmsSlicer.OokManchesterZeroBit, 300),
        TpmsProtocol(123, ::schraderMrxbc5a4, TpmsSlicer.OokManchesterZeroBit, 800),
        TpmsProtocol(120, ::schraderSmd3ma4, TpmsSlicer.OokPcm, 480),
    )

    val bitTimesUs = protocols.filter { it.slicer == TpmsSlicer.FskPcm }.map { it.bitUs }.distinct()

    /** Every reading in a row of FSK bits sliced at [bitUs], in both polarities; one per sensor id. */
    fun decodeRow(row: ByteArray, bitUs: Int): List<TpmsReading> {
        val inverted = ByteArray(row.size) { (1 - row[it]).toByte() }
        val out = LinkedHashMap<String, TpmsReading>()
        for (proto in protocols) {
            if (proto.slicer != TpmsSlicer.FskPcm || proto.bitUs != bitUs) continue
            for (r in proto.decode(row) + proto.decode(inverted)) out.putIfAbsent(r.id, r)
        }
        return out.values.toList()
    }

    /**
     * rtl_433's Manchester "zero bit" slicer over on/off pulses (carrier and silence lengths, in samples): a first 0,
     * then a data edge wherever more than one and a half half-bits have gone by since the last one: falling (end of
     * carrier) is a 1, rising a 0. A silence longer than [reset] ends the row.
     */
    fun zeroBitRows(pulses: IntArray, gaps: IntArray, half: Double, reset: Double): List<ByteArray> {
        val rows = ArrayList<ByteArray>()
        var bits = ArrayList<Byte>().also { it += 0 }
        var since = 0
        for (k in pulses.indices) {
            if (pulses[k] + since > half * 1.5) { bits += 1; since = 0 } else since += pulses[k]
            if (k == pulses.size - 1 || gaps[k] > reset) {
                if (bits.size > 1) rows += bits.toByteArray()
                bits = ArrayList<Byte>().also { it += 0 }
                since = 0
            } else if (gaps[k] + since > half * 1.5) { bits += 0; since = 0 } else since += gaps[k]
        }
        return rows
    }

    /** Readings from an on/off keyed burst given as carrier and silence lengths at [sampleRate]. */
    fun decodeOok(pulses: IntArray, gaps: IntArray, sampleRate: Double): List<TpmsReading> {
        val out = LinkedHashMap<String, TpmsReading>()
        for (proto in protocols) {
            val spb = sampleRate * proto.bitUs / 1e6
            val reset = sampleRate * proto.resetUs / 1e6
            val rows = when (proto.slicer) {
                TpmsSlicer.FskPcm -> continue
                TpmsSlicer.OokManchesterZeroBit -> zeroBitRows(pulses, gaps, spb, reset)
                TpmsSlicer.OokPcm -> {
                    // As rtl_433's PCM slicer: the bit time is refined on the longest run of one-bit pulses and
                    // gaps (the preamble), a silence adds at most reset / bit zeros (so a frame's last 0 is kept),
                    // and one longer than the reset ends the row.
                    var bit = spb
                    var best = 0
                    var k0 = 0
                    while (k0 < pulses.size) {
                        var w = 0
                        var c = 0
                        var k = k0
                        while (k < pulses.size && (pulses[k] / spb).roundToInt() == 1 && (gaps[k] / spb).roundToInt() == 1) { w += pulses[k] + gaps[k]; c += 2; k++ }
                        if (c >= 12 && c > best) { best = c; bit = w.toDouble() / c }
                        k0 = k + 1
                    }
                    val maxZeros = (reset / spb).toInt()
                    val rs = ArrayList<ByteArray>()
                    var cur = ArrayList<Byte>()
                    for (k in pulses.indices) {
                        repeat(maxOf(1, (pulses[k] / bit).roundToInt()).coerceAtMost(64)) { cur += 1 }
                        repeat(minOf((gaps[k] / bit).roundToInt(), maxZeros)) { cur += 0 }
                        if (k == pulses.size - 1 || gaps[k] > reset) { rs += cur.toByteArray(); cur = ArrayList() }
                    }
                    rs
                }
            }
            for (row in rows) for (r in proto.decode(row)) out.putIfAbsent(r.id, r)
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
    private val level = FloatArray(MAX_BURST)
    private var len = 0
    private val hist = FloatArray(PRE)
    private val histP = FloatArray(PRE)
    private var histPos = 0
    private var head = 0
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
                if (!inBurst) {
                    // The squelch opens a little after the burst starts: begin with the samples just before it.
                    inBurst = true
                    len = 0
                    val k = minOf(PRE, n.toInt())
                    for (j in k downTo 1) { val h = ((histPos - j) % PRE + PRE) % PRE; level[len] = histP[h]; freq[len++] = hist[h] }
                    head = k
                }
                quiet = 0
            }
            hist[histPos] = f
            histP[histPos] = p
            histPos = (histPos + 1) % PRE
            if (inBurst) {
                if (len < MAX_BURST) { level[len] = p; freq[len++] = f }
                if (!loud && ++quiet > END_GAP || len == MAX_BURST) {
                    inBurst = false
                    // Likewise it closes a little before the end: keep a short tail, or the last bits come out cut.
                    val kept = len - (quiet - TAIL).coerceIn(0, len)
                    burst(kept, head, minOf(TAIL, quiet, kept - head))
                    // A "burst" as long as the limit is more likely a louder noise floor: let the floor catch up.
                    if (len == MAX_BURST) floor += (power - floor) * 0.5f
                }
            }
            n++
        }
    }

    /** [count] samples in [freq], of which the first [head] and the last [tail] are from outside the squelch. */
    private fun burst(count: Int, head: Int, tail: Int) {
        val core = count - head - tail
        if (core < 200) return
        val now = System.currentTimeMillis()
        ook(count, head, core).forEach { emit(it, now) }
        // The two tones from the burst itself: the samples around it are noise, any frequency.
        val sorted = freq.copyOfRange(head, head + core).also { it.sort() }
        val lo = sorted[core / 10]
        val hi = sorted[core * 9 / 10]
        if (hi - lo < 0.05f) return
        val mid = (lo + hi) / 2
        // Runs of one level, then rows of bits at each bit time.
        val levels = ArrayList<Int>()
        val lengths = ArrayList<Int>()
        for (i in 0 until count) {
            val l = if (freq[i] > mid) 1 else 0
            if (levels.isNotEmpty() && levels.last() == l) lengths[lengths.lastIndex]++ else { levels += l; lengths += 1 }
        }
        for (us in Tpms.bitTimesUs) {
            val spb = sampleRate * us / 1e6
            // A run much shorter than a bit is a noise spike on the discriminator: it belongs to the run it interrupts,
            // which would otherwise come out as two short runs and lose a bit when they are rounded.
            val mv = ArrayList<Int>()
            val ml = ArrayList<Int>()
            for (k in levels.indices) {
                when {
                    mv.isNotEmpty() && lengths[k] < spb * GLITCH -> ml[ml.lastIndex] += lengths[k]
                    mv.isNotEmpty() && mv.last() == levels[k] -> ml[ml.lastIndex] += lengths[k]
                    else -> { mv += levels[k]; ml += lengths[k] }
                }
            }
            val row = ArrayList<Byte>()
            for (k in mv.indices) repeat((ml[k] / spb).roundToInt().coerceAtMost(64)) { row += mv[k].toByte() }
            for (r in Tpms.decodeRow(row.toByteArray(), us)) emit(r, now)
        }
        if (recent.size > 256) recent.entries.removeAll { n - it.value > sampleRate * 10 }
    }

    private fun emit(r: TpmsReading, now: Long) {
        // A sensor repeats its packet several times per burst: one reading per burst.
        val key = "${r.protocol}/${r.id}/${r.pressureKpa}/${r.temperatureC}"
        val last = recent[key]
        if (last != null && n - last < sampleRate * 2) return
        recent[key] = n
        onReading(r.copy(timeMs = now))
    }

    /**
     * The same burst read as on/off keying: carrier where the power is over the middle (in dB) between the noise
     * floor and the burst's strong samples. A frequency keyed burst is one long pulse and decodes as nothing.
     */
    private fun ook(count: Int, head: Int, core: Int): List<TpmsReading> {
        // The power of each sample over three: short and symmetric, so carrier and silence keep their lengths (the
        // squelch's own smoothing falls far slower than it rises, and would stretch every pulse).
        val pw = FloatArray(count) { i -> (level[maxOf(0, i - 1)] + level[i] + level[minOf(count - 1, i + 1)]) / 3 }
        System.arraycopy(pw, 0, level, 0, count)
        val sorted = level.copyOfRange(head, head + core).also { it.sort() }
        val strong = sorted[core * 9 / 10]
        if (strong < floor * SQUELCH) return emptyList()
        val thr = kotlin.math.sqrt(strong.toDouble() * maxOf(floor, 1e-12f)).toFloat()
        val pulses = ArrayList<Int>()
        val gaps = ArrayList<Int>()
        var i = 0
        while (i < count && level[i] <= thr) i++
        while (i < count) {
            var on = 0
            while (i < count && level[i] > thr) { on++; i++ }
            var off = 0
            while (i < count && level[i] <= thr) { off++; i++ }
            pulses += on
            // The burst ends in silence: as long as rtl_433's longest reset, so the last row closes.
            gaps += if (i >= count) Int.MAX_VALUE / 2 else off
        }
        if (pulses.size < 8) return emptyList()
        return Tpms.decodeOok(pulses.toIntArray(), gaps.toIntArray(), sampleRate)
    }

    companion object {
        /** Power over the noise floor that opens the squelch (10 dB). */
        private const val SQUELCH = 10f
        /** Samples (2 ms) the filters and the noise floor need to settle before the squelch may open. */
        private const val WARMUP = 512
        /** Quiet samples that end a burst (about 0.3 ms). */
        private const val END_GAP = 80
        /** Samples kept from before the squelch opened (0.25 ms at 250 kS/s), and after it closed. */
        private const val PRE = 64
        /** Runs shorter than this many bit times are spikes. */
        private const val GLITCH = 0.4
        private const val TAIL = 32
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
