package com.allnetworktools

import com.allnetworktools.data.sdr.TpmsBits
import com.allnetworktools.data.sdr.TpmsDemodulator
import com.allnetworktools.data.sdr.TpmsReading
import com.allnetworktools.data.sdr.TpmsReceiver
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic sensor packets, built the way rtl_433 reads them, sent as FSK through the demodulator. */
class TpmsTest {
    private fun bitsOf(hex: String, n: Int = hex.length * 4) = TpmsBits.pattern(hex, n).toList()

    private fun bitsOf(b: IntArray, n: Int = b.size * 8) = (0 until n).map { ((b[it / 8] shr (7 - it % 8)) and 1).toByte() }

    /** rtl_433's Manchester: a 1 is "01", a 0 is "10". */
    private fun manchester(bits: List<Byte>) = bits.flatMap { listOf((1 - it).toByte(), it) }

    /** rtl_433's differential Manchester: a transition at every bit edge, and mid-bit for a 0. */
    private fun diffManchester(bits: List<Byte>, prevLevel: Int): List<Byte> {
        var level = prevLevel
        val out = ArrayList<Byte>()
        for (b in bits) {
            level = 1 - level; out += level.toByte()
            if (b.toInt() == 0) level = 1 - level
            out += level.toByte()
        }
        return out
    }

    /** FSK at 250 kS/s, ±40 kHz, between stretches of noise; [invert] swaps the tones. */
    private fun fsk(raw: List<Byte>, bitUs: Double, invert: Boolean = false, seed: Int = 1): Pair<FloatArray, FloatArray> {
        val rate = 250_000.0
        val rnd = Random(seed)
        val spb = rate * bitUs / 1e6
        val gap = 4000
        val n = gap * 2 + (raw.size * spb).roundToInt()
        val re = FloatArray(n)
        val im = FloatArray(n)
        var ph = 0.0
        for (i in 0 until n) {
            val k = i - gap
            val sig = k >= 0 && k < raw.size * spb
            if (sig) {
                val bit = raw[(k / spb).toInt().coerceAtMost(raw.size - 1)].toInt()
                ph += 2 * PI * (if ((bit == 1) != invert) 40_000.0 else -40_000.0) / rate
                re[i] = 0.5f * cos(ph).toFloat(); im[i] = 0.5f * sin(ph).toFloat()
            }
            re[i] += (rnd.nextFloat() - 0.5f) * 0.03f
            im[i] += (rnd.nextFloat() - 0.5f) * 0.03f
        }
        return re to im
    }

    private fun receive(raw: List<Byte>, bitUs: Double, invert: Boolean = false): List<TpmsReading> {
        val out = ArrayList<TpmsReading>()
        val (re, im) = fsk(raw, bitUs, invert)
        val demod = TpmsDemodulator { out += it }
        var i = 0
        while (i < re.size) {
            val k = minOf(4096, re.size - i)
            demod.feed(re.copyOfRange(i, i + k), im.copyOfRange(i, i + k), k)
            i += k
        }
        return out
    }

    private fun List<TpmsReading>.one(): TpmsReading { assertEquals(toString(), 1, size); return first() }

    private val lead = bitsOf("aaaaaaaa")
    private val trailer = bitsOf("7e00")

    @Test fun renault() {
        val b = intArrayOf(0xd1, 0x2c, 0x44, 0x56, 0x34, 0x12, 0xff, 0xff, 0)
        b[8] = TpmsBits.crc8(b, 8, 0x07, 0x00)
        val r = receive(lead + bitsOf("aaa9") + manchester(bitsOf(b)) + trailer, 52.0).one()
        assertEquals("Renault / Dacia", r.protocol)
        assertEquals("123456", r.id)
        assertEquals(((0xd1 and 3) * 256 + 0x2c) * 0.75, r.pressureKpa!!, 1e-9)
        assertEquals(0x44 - 30.0, r.temperatureC!!, 1e-9)
    }

    @Test fun eitherTonePolarity() {
        val b = intArrayOf(0x23, 0xab, 0xcd, 0xef, 0x01, 0x02, 0xa5, 0x46, 0x5a, 0)
        b[9] = (1..8).fold(0) { a, i -> a xor b[i] }
        for (inv in listOf(false, true)) {
            val r = receive(lead + bitsOf("aaa9") + manchester(bitsOf(b)) + trailer, 52.0, invert = inv).one()
            assertEquals("Citroën / Peugeot", r.protocol)
            assertEquals("abcdef01", r.id)
            assertEquals(0xa5 * 1.364, r.pressureKpa!!, 1e-9)
            assertEquals(0x46 - 50.0, r.temperatureC!!, 1e-9)
        }
    }

    @Test fun hyundaiVdoWinsOverCitroenOnTheSamePacket() {
        val b = intArrayOf(0x21, 0x11, 0x22, 0x33, 0x44, 0x01, 0xa0, 0x48, 0x10, 0)
        b[9] = TpmsBits.crc8(b, 9, 0x07, 0xaa)
        val r = receive(lead + bitsOf("aaaaaaa9") + manchester(bitsOf(b)) + trailer, 52.0).one()
        assertEquals("Hyundai / Kia (VDO)", r.protocol)
        assertEquals(0xa0 * 1.375, r.pressureKpa!!, 1e-9)
    }

    @Test fun toyotaDifferentialManchester() {
        // Pressure 0x90 sent twice (once inverted), temperature 0x3c.
        val p = 0x90
        val tmp = 0x3c
        val b = intArrayOf(0x0a, 0x0b, 0x0c, 0x0d, 0x80 or (p shr 1), ((p and 1) shl 7) or (tmp shr 1), (tmp and 1) shl 7, p xor 0xff, 0)
        b[8] = TpmsBits.crc8(b, 8, 0x07, 0x80)
        val raw = lead + bitsOf("a9e0", 11) + diffManchester(bitsOf(b), 1) + trailer
        val r = receive(raw, 52.0).one()
        assertEquals("Toyota", r.protocol)
        assertEquals("0a0b0c0d", r.id)
        assertEquals((p * 0.25 - 7) * 6.894757, r.pressureKpa!!, 1e-6)
        assertEquals(tmp - 40.0, r.temperatureC!!, 1e-9)
    }

    @Test fun pmv107jAt100Microseconds() {
        val b = IntArray(9)
        b[0] = 0x2; b[1] = 0x34; b[2] = 0x56; b[3] = 0x78; b[4] = 0x88; b[5] = 0x60; b[6] = 0x60 xor 0xff; b[7] = 0x41
        b[8] = TpmsBits.crc8(b, 8, 0x13, 0x00)
        val data = bitsOf(intArrayOf(b[0] shl 6), 2) + bitsOf(b.copyOfRange(1, 9)) + listOf<Byte>(0)
        val r = receive(bitsOf("ff") + bitsOf("f8", 6) + diffManchester(data, 0) + trailer, 100.0).one()
        assertEquals("Toyota (PMV-107J)", r.protocol)
        assertEquals((0x60 - 40.0) * 2.48, r.pressureKpa!!, 1e-9)
        assertEquals(0x41 - 40.0, r.temperatureC!!, 1e-9)
        assertEquals(true, r.batteryOk)
    }

    @Test fun bmwAt25MicrosecondsWithInvertedManchester() {
        val b = intArrayOf(0x03, 0x23, 0xe1, 0x36, 0xa1, 0x4a, 0x3e, 0x01, 0x6b, 0x68, 0)
        b[10] = TpmsBits.crc8(b, 10, 0x2f, 0xaa)
        val inverted = bitsOf(b).map { (1 - it).toByte() }
        val r = receive(lead + bitsOf("aa59") + manchester(inverted) + trailer, 25.0).one()
        assertEquals("BMW", r.protocol)
        assertEquals("23e136a1", r.id)
        assertEquals(0x4a * 2.45, r.pressureKpa!!, 1e-9)
        assertEquals(0x3e - 52.0, r.temperatureC!!, 1e-9)
    }

    @Test fun kiaFordPorscheElantraTruck() {
        // Kia: 69 bits, the CRC's top 5 bits last.
        // As in rtl_433, the 5 bits sent must equal the whole CRC: pick a last byte whose CRC ends in three zeros.
        val k = intArrayOf(0xf9, 0x84, 0x91, 0x23, 0x45, 0x67, 0x8a, 0, 0)
        k[7] = (0..255).first { v -> k[7] = v; TpmsBits.crc8(k, 8, 0x07, 0x76) and 0x07 == 0 }
        k[8] = TpmsBits.crc8(k, 8, 0x07, 0x76)
        val kia = receive(lead + bitsOf("ed71") + manchester(bitsOf(k, 69)) + trailer, 50.0).one()
        assertEquals("Kia", kia.protocol)
        assertEquals(0x98 / 5.0 * 6.894757, kia.pressureKpa!!, 1e-6)

        val f = intArrayOf(0x11, 0x22, 0x33, 0x44, 0x88, 0x4b, 0x46, 0)
        f[7] = (0..6).sumOf { f[it] } and 0xff
        val ford = receive(lead + bitsOf("aaa9") + manchester(bitsOf(f)) + trailer, 52.0).one()
        assertEquals("Ford", ford.protocol)
        assertEquals(0x88 * 0.25 * 6.894757, ford.pressureKpa!!, 1e-6)
        assertEquals(0x4b - 56.0, ford.temperatureC!!, 1e-9)
        assertEquals(true, ford.moving)

        val p = intArrayOf(0x23, 0xd7, 0xad, 0x23, 0x62, 0x3b, 0xbb, 0x02, 0, 0)
        val crc = TpmsBits.crc16(p, 8, 0x1021, 0xffff)
        p[8] = crc shr 8; p[9] = crc and 0xff
        val porsche = receive(lead + bitsOf("33333332") + diffManchester(bitsOf(p), 0) + trailer, 52.0).one()
        assertEquals("Porsche", porsche.protocol)
        assertEquals(0x62 * 5 / 2 - 100.0, porsche.pressureKpa!!, 1e-9)

        val e = intArrayOf(0xb4, 0x46, 0xde, 0xad, 0xbe, 0xef, 0xc0, 0)
        e[7] = TpmsBits.crc8(e, 7, 0x07, 0x00)
        val elantra = receive(lead + bitsOf("7155") + manchester(bitsOf(e)) + trailer, 50.0).one()
        assertEquals("Hyundai / Honda (TRW)", elantra.protocol)
        assertEquals("deadbeef", elantra.id)
        assertEquals(0xb4 + 60.0, elantra.pressureKpa!!, 1e-9)

        val t = intArrayOf(0x05, 0x81, 0xb2, 0x81, 0x02, 0x33, 0x84, 0x1e, 0)
        t[8] = (0..7).fold(0) { a, i -> a xor t[i] }
        val truck = receive(lead + bitsOf("aaaaa9") + manchester(bitsOf("10", 4) + bitsOf(t)) + trailer, 52.0).one()
        assertEquals("Truck", truck.protocol)
        assertEquals(2, truck.wheel)
        assertEquals((0x3 shl 8 or 0x84).toDouble(), truck.pressureKpa!!, 1e-9)
        assertEquals(true, truck.batteryOk)
    }

    @Test fun aBadChecksumIsRejectedAndNoiseDecodesNothing() {
        val b = intArrayOf(0xd1, 0x2c, 0x44, 0x56, 0x34, 0x12, 0xff, 0xff, 0)
        b[8] = TpmsBits.crc8(b, 8, 0x07, 0x00) xor 1
        assertTrue(receive(lead + bitsOf("aaa9") + manchester(bitsOf(b)) + trailer, 52.0).isEmpty())
        val rnd = Random(7)
        assertTrue(receive(List(2000) { rnd.nextInt(2).toByte() }, 52.0).isEmpty())
    }

    @Test fun repeatedPacketsInOneBurstGiveOneReading() {
        val b = intArrayOf(0xd1, 0x2c, 0x44, 0x56, 0x34, 0x12, 0xff, 0xff, 0)
        b[8] = TpmsBits.crc8(b, 8, 0x07, 0x00)
        val packet = lead + bitsOf("aaa9") + manchester(bitsOf(b)) + trailer
        assertEquals(1, receive(packet + packet + packet, 52.0).size)
    }

    /** The whole chain from HackRF bytes: 2 MS/s, the sensor fs/4 above the tuning, 8-bit samples. */
    @Test fun fromHackRfBytes() {
        val b = intArrayOf(0xd1, 0x2c, 0x44, 0x56, 0x34, 0x12, 0xff, 0xff, 0)
        b[8] = TpmsBits.crc8(b, 8, 0x07, 0x00)
        val raw = lead + bitsOf("aaa9") + manchester(bitsOf(b)) + trailer
        val rate = TpmsReceiver.SAMPLE_RATE.toDouble()
        val spb = rate * 52e-6
        val gap = 40_000
        val n = gap * 2 + (raw.size * spb).roundToInt()
        val bytes = ByteArray(n * 2)
        val rnd = Random(3)
        var ph = 0.0
        for (i in 0 until n) {
            val k = i - gap
            val sig = k >= 0 && k < raw.size * spb
            val f = rate / 4 + if (sig && raw[(k / spb).toInt().coerceAtMost(raw.size - 1)].toInt() == 1) 40_000.0 else -40_000.0
            ph += 2 * PI * f / rate
            val a = if (sig) 60.0 else 0.0
            bytes[2 * i] = (a * cos(ph) + rnd.nextInt(-3, 4)).roundToInt().toByte()
            bytes[2 * i + 1] = (a * sin(ph) + rnd.nextInt(-3, 4)).roundToInt().toByte()
        }
        val out = ArrayList<TpmsReading>()
        val rx = TpmsReceiver { out += it }
        var i = 0
        while (i < bytes.size) {
            val k = minOf(131072, bytes.size - i)
            rx.feed(bytes.copyOfRange(i, i + k), k)
            i += k
        }
        assertEquals("123456", out.one().id)
    }

    @Test fun bitHelpers() {
        assertEquals(listOf<Byte>(1, 0, 1), TpmsBits.manchester(byteArrayOf(0, 1, 1, 0, 0, 1, 1, 1), 0, 10).first.toList())
        assertEquals(0, TpmsBits.crc8(intArrayOf(0x31, TpmsBits.crc8(intArrayOf(0x31), 1, 0x07, 0)), 2, 0x07, 0))
        assertNull(null)
    }
}
