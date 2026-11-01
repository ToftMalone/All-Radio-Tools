package com.allnetworktools

import com.allnetworktools.data.sdr.Ism
import com.allnetworktools.data.sdr.IsmBits
import com.allnetworktools.data.sdr.IsmPulse
import com.allnetworktools.data.sdr.IsmReading
import com.allnetworktools.data.sdr.IsmTrain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Lidl (Auriol), Hideki, Ecowitt WH31 and further Oregon Scientific sensors, from captures in rtl_433's sources where it has them. */
class IsmMoreTest {
    private fun bitsOf(hex: String, n: Int): List<Int> {
        val b = hex.chunked(2).map { it.toInt(16) }
        return (0 until n).map { (b[it / 8] shr (7 - it % 8)) and 1 }
    }

    private fun row(bits: List<Int>, rows: Int = 1): IsmBits {
        val b = IsmBits()
        repeat(rows) { r -> if (r > 0) b.addRow(); bits.forEach { b.addBit(it) } }
        return b
    }

    private fun field(r: IsmReading, name: String) = r.fields.first { it.name == name }.value

    /** PPM rows: a pulse, then a short or long gap per bit; one more pulse closes each row. */
    private fun ppm(bits: List<Int>, rows: Int, pulse: Float, zero: Float, one: Float, between: Float): IsmTrain {
        val p = ArrayList<IsmPulse>()
        repeat(rows) { r ->
            bits.forEach { p += IsmPulse(pulse, if (it == 1) one else zero) }
            p += IsmPulse(pulse, if (r == rows - 1) 1e6f else between)
        }
        return IsmTrain(false, p)
    }

    @Test fun auriolAft77b2FromItsCapture() {
        // rtl_433's example: {68} a5 cf 80 20 17 30 0c ac 90, 17.3 °C.
        val r = Ism.decodeBits("Auriol AFT", row(bitsOf("a5cf802017300cac90", 68))).single()
        assertEquals("Auriol-AFT77B2", r.model)
        assertEquals("248", r.id)
        assertEquals(17.3, field(r, "Temperature"), 1e-9)
        // One bit off and the sum or the LFSR digest says no.
        assertTrue(Ism.decodeBits("Auriol AFT", row(bitsOf("a5cf802017310cac90", 68))).isEmpty())
    }

    @Test fun auriolAfw2a1ThroughThePpmSlicer() {
        // rtl_433's example: {36} 90 80 ba a3 a0, id 144, 18.6 °C, 58 %; sent 12 times.
        val t = ppm(bitsOf("9080baa3a0", 36), 12, 500f, 576f, 1536f, 3000f)
        val r = Ism.decode(t).single { it.model == "Auriol-AFW2A1" }
        assertEquals("144", r.id)
        assertEquals(18.6, field(r, "Temperature"), 1e-9)
        assertEquals(58.0, field(r, "Humidity"), 1e-9)
        assertEquals(1, r.channel)
    }

    @Test fun auriolAhflAnd4ld5661FromTheirCaptures() {
        // {42} f2 00 ef 7c 41 40: 23.9 °C, 62 %, its 6-bit nibble sum 5.
        val a = Ism.decodeBits("Auriol AHFL", row(bitsOf("f200ef7c4140", 42), rows = 2)).single()
        assertEquals("242", a.id)
        assertEquals(23.9, field(a, "Temperature"), 1e-9)
        assertEquals(62.0, field(a, "Humidity"), 1e-9)
        // {52} 1a 80 88 f0 00 14 5: 13.6 °C and 325 bucket tips.
        val r = Ism.decodeBits("Auriol 4-LD", row(bitsOf("1a8088f0001450", 52))).single()
        assertEquals("1a", r.id)
        assertEquals(13.6, field(r, "Temperature"), 1e-9)
        assertEquals(325.0, field(r, "Rain"), 1e-9)
        assertEquals(true, r.battery)
    }

    /** NRZ bits to FSK marks and spaces of [us] each. */
    private fun pcm(b: List<Int>, us: Float): List<IsmPulse> {
        val p = ArrayList<IsmPulse>()
        var i = b.indexOf(1)
        while (i < b.size) {
            var a = i
            while (i < b.size && b[i] == 1) i++
            val marks = i - a
            a = i
            while (i < b.size && b[i] == 0) i++
            p += IsmPulse(marks * us, if (i >= b.size) 30_000f else (i - a) * us)
        }
        return p
    }

    @Test fun ecowittWh31eFromItsPayloads() {
        for ((payload, expect) in listOf(
            "30c381d55c2acf08354424" to listOf(195.0, 1.0, 6.9, 92.0),
            "30449213 3e0e65074504 5f".replace(" ", "") to listOf(68.0, 2.0, 13.1, 62.0),
        )) {
            val bits = bitsOf("aaaaaaaaaaaa2dd4$payload" + "00", 8 * (8 + payload.length / 2 + 1))
            val r = Ism.decode(IsmTrain(true, pcm(bits, 56f))).single { it.model == "Ecowitt-WH31E" }
            assertEquals(expect[0], r.id.toDouble(), 1e-9)
            assertEquals(expect[1], r.channel!!.toDouble(), 1e-9)
            assertEquals(expect[2], field(r, "Temperature"), 1e-9)
            assertEquals(expect[3], field(r, "Humidity"), 1e-9)
        }
    }

    private fun reflect8(x: Int): Int { var r = 0; for (i in 0 until 8) r = r or (((x shr i) and 1) shl (7 - i)); return r }

    @Test fun hidekiTs04ThroughTheDmcSlicer() {
        // Logical bytes: channel 1 and rolling code 5, length 7, 21.5 °C (sign and battery bits set), 55 %.
        val pk = intArrayOf(0x25, 0x0E, 0x1E, 0x15, 0xC2, 0x55, 0x00)
        val air = IntArray(9)
        for (i in 0 until 7) air[i] = reflect8(pk[i])
        air[7] = air.take(7).fold(0) { a, x -> a xor x }
        air[8] = Ism.crc8(air, 8, 0x07, 0x00)
        // Sync 0000 0110 1 as sent, then each byte inverted with its even parity bit.
        val raw = ArrayList(listOf(0, 0, 0, 0, 0, 1, 1, 0, 1))
        for (x in air) {
            for (k in 7 downTo 0) raw += 1 - ((x shr k) and 1)
            raw += 1 - (Integer.bitCount(x) and 1)
        }
        if (raw.size % 2 == 0) raw += 0
        // Differential Manchester: a 1 is two short half-bits, a 0 one long bit, marks and spaces alternating.
        val sym = ArrayList<Float>()
        for (b in raw) if (b == 1) { sym += 520f; sym += 520f } else sym += 1040f
        if (sym.size % 2 == 1) sym += 1e6f else sym[sym.lastIndex] = 1e6f
        val t = IsmTrain(false, (0 until sym.size / 2).map { IsmPulse(sym[2 * it], sym[2 * it + 1]) })
        val r = Ism.decode(t).single { it.model.startsWith("Hideki") }
        assertEquals("Hideki-TS04", r.model)
        assertEquals("5", r.id)
        assertEquals(1, r.channel)
        assertEquals(21.5, field(r, "Temperature"), 1e-9)
        assertEquals(55.0, field(r, "Humidity"), 1e-9)
    }

    private fun reflect4(x0: Int): Int { var x = ((x0 and 0xCC) shr 2) or ((x0 and 0x33) shl 2); x = ((x and 0xAA) shr 1) or ((x and 0x55) shl 1); return x and 0xFF }

    /** The Oregon checksum (sum of nibbles before [idx], swapped), put where the sensor puts it. */
    private fun osSum(m: IntArray, idx: Int) {
        var sum = 0
        var i = 0
        while (i < idx - 1) { sum += (m[i shr 1] shr 4) + (m[i shr 1] and 0x0f); i += 2 }
        if (idx and 1 != 0) {
            sum += m[idx shr 1] shr 4
            m[idx shr 1] = (m[idx shr 1] and 0xf0) or (sum and 0x0f)
            m[(idx + 1) shr 1] = (m[(idx + 1) shr 1] and 0x0f) or (sum and 0xf0)
        } else m[idx shr 1] = ((sum and 0x0f) shl 4) or ((sum and 0xf0) shr 4)
    }

    @Test fun oregonThn132nV21() {
        // 0xEC40, channel 1, house code 0x2A, 21.5 °C; 64 bits, checksum over 12 nibbles.
        val m = intArrayOf(0xEC, 0x40, 0x1A, 0x20, 0x51, 0x20, 0, 0)
        osSum(m, 12)
        // As the slicer gives it: preamble, the 5599 sync, then each bit as a Manchester pair (01 is a 1), nibbles reflected.
        val raw = ArrayList(bitsOf("ff55555599", 40))
        for (i in 0 until 64) { val x = (reflect4(m[i / 8]) shr (7 - i % 8)) and 1; raw += 1 - x; raw += x }
        raw += 0; raw += 0
        val r = Ism.decodeBits("Oregon", row(raw)).single()
        assertEquals("Oregon-THN132N", r.model)
        assertEquals(1, r.channel)
        assertEquals("42", r.id)
        assertEquals(21.5, field(r, "Temperature"), 1e-9)
        // A checksum off by one is refused.
        m[6] = m[6] xor 0x10
        val bad = ArrayList(bitsOf("ff55555599", 40))
        for (i in 0 until 64) { val x = (reflect4(m[i / 8]) shr (7 - i % 8)) and 1; bad += 1 - x; bad += x }
        bad += 0; bad += 0
        assertTrue(Ism.decodeBits("Oregon", row(bad)).isEmpty())
    }

    @Test fun oregonWgr800V3() {
        // 0x1984 anemometer: from the east, gust 12.3 m/s, average 8.4 m/s; checksum over 17 nibbles.
        val m = intArrayOf(0x19, 0x84, 0x10, 0x20, 0x40, 0x03, 0x21, 0x48, 0x00, 0x00)
        osSum(m, 17)
        val raw = bitsOf("00000005", 32) + (0 until 80).map { (reflect4(m[it / 8]) shr (7 - it % 8)) and 1 }
        val r = Ism.decodeBits("Oregon", row(raw)).single()
        assertEquals("Oregon-WGR800", r.model)
        assertEquals(8.4 * 3.6, field(r, "Wind speed"), 1e-9)
        assertEquals(12.3 * 3.6, field(r, "Wind gust"), 1e-9)
        assertEquals(90.0, field(r, "Wind direction"), 1e-9)
    }
}
