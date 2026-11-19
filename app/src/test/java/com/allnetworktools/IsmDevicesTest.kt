package com.allnetworktools

import com.allnetworktools.data.sdr.Ism
import com.allnetworktools.data.sdr.IsmBits
import com.allnetworktools.data.sdr.IsmPulse
import com.allnetworktools.data.sdr.IsmReading
import com.allnetworktools.data.sdr.IsmTrain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The devices of IsmMore, each from a frame built to its documented layout and run through its slicer. */
class IsmDevicesTest {
    private fun bits(bytes: IntArray, n: Int = bytes.size * 8) = (0 until n).map { (bytes[it / 8] shr (7 - it % 8)) and 1 }
    private fun field(r: IsmReading, name: String) = r.fields.first { it.name == name }
    private fun value(r: IsmReading, name: String) = field(r, name).value
    private fun text(r: IsmReading, name: String) = field(r, name).text

    /** Pulse-position: every bit a [pulse] then a gap, short for 0, long for 1. */
    private fun ppm(rows: List<List<Int>>, pulse: Float, zero: Float, one: Float, between: Float, lead: Float = 0f): IsmTrain {
        val p = ArrayList<IsmPulse>()
        if (lead > 0) p += IsmPulse(pulse, lead)
        rows.forEachIndexed { r, b ->
            b.forEach { x -> p += IsmPulse(pulse, if (x == 1) one else zero) }
            p += IsmPulse(pulse, if (r == rows.lastIndex) 1e6f else between) // the closing pulse
        }
        return IsmTrain(false, p)
    }

    /** Pulse-width: short pulse for 1, long for 0, a fixed gap. */
    private fun pwm(rows: List<List<Int>>, one: Float, zero: Float, gap: Float, between: Float): IsmTrain {
        val p = ArrayList<IsmPulse>()
        rows.forEachIndexed { r, b ->
            b.forEachIndexed { i, x -> p += IsmPulse(if (x == 1) one else zero, if (i == b.lastIndex) (if (r == rows.lastIndex) 1e6f else between) else gap) }
        }
        return IsmTrain(false, p)
    }

    /** NRZ bits to marks and spaces of [us] each. */
    private fun pcm(b: List<Int>, us: Float, fsk: Boolean): IsmTrain {
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
        return IsmTrain(fsk, p)
    }

    private fun one(t: IsmTrain, model: String): IsmReading = Ism.decode(t).filter { it.model.startsWith(model) }.single()

    @Test fun chaconDioKakuThroughThePpmSlicer() {
        // 26-bit house code, group 0, on, unit 5; sent inverted, Manchester: each bit as (bit, !bit).
        val id = 0x2A5B3C1
        val data = (25 downTo 0).map { (id shr it) and 1 } + listOf(0, 1) + (3 downTo 0).map { (5 shr it) and 1 }
        val raw = data.flatMap { listOf(it, 1 - it) }
        val p = ArrayList<IsmPulse>()
        p += IsmPulse(260f, 2650f) // the sync
        raw.forEach { x -> p += IsmPulse(260f, if (x == 1) 1300f else 280f) }
        p += IsmPulse(260f, 1e6f) // the closing pulse
        val r = one(IsmTrain(false, p), "Chacon")
        assertEquals("$id", r.id)
        assertEquals(6, r.channel)
        assertEquals("On", text(r, "Command"))
    }

    @Test fun somfyRtsThroughThePcmSlicer() {
        val clear = intArrayOf(0xA7, 0x20, 0x01, 0x2C, 0x56, 0x34, 0x12) // seed, Up, counter 300, address 0x123456
        var x = 0
        for (v in clear) x = x xor v
        clear[1] = clear[1] or ((x and 0xf) xor (x shr 4))
        val ob = IntArray(7)
        ob[0] = clear[0]
        for (i in 1..6) ob[i] = clear[i] xor ob[i - 1]
        val body = bits(ob).flatMap { listOf(1 - it, it) }
        val frame = bits(intArrayOf(0xf0, 0xf0, 0xff, 0x00), 25) + body + listOf(0)
        val r = one(pcm(frame, 604f, false), "Somfy")
        assertEquals("123456", r.id)
        assertEquals("Up", text(r, "Control"))
        assertEquals("#300", text(r, "Counter"))
    }

    @Test fun visonicPowerCodeThroughThePwmSlicer() {
        val m = intArrayOf(0x4A, 0x21, 0x9C, 0x40, 0x00)
        var lrc = 0
        for (i in 0 until 4) lrc = lrc xor m[i]
        m[4] = ((lrc shr 4) xor (lrc and 0xf)) shl 4 // the last nibble folds the LRC to zero
        val row = listOf(1) + bits(m, 36)
        val r = one(pwm(listOf(row, row, row), 400f, 800f, 400f, 2000f), "Visonic")
        assertEquals("4a219c", r.id)
        assertEquals("Alarm", text(r, "State"))
    }

    @Test fun infactoryThroughThePpmSlicer() {
        val tf = 72.5 // °F
        val raw = ((tf * 10).toInt() + 900)
        val b = intArrayOf(0x9C, 0x00, raw shr 4, ((raw and 0xF) shl 4) or 4, (5 shl 4) or 2) // 45 %, channel 2
        val msg = intArrayOf(b[0], (b[1] and 0x0F) or ((b[4] and 0x0F) shl 4), b[2], b[3])
        b[1] = ((Ism.crc4(msg, 4, 0x13, 0) xor (b[4] shr 4)) shl 4) or b[1]
        val t = ppm(listOf(bits(b)), 500f, 2000f, 4000f, 16000f, lead = 1000f)
        val r = one(t, "inFactory")
        assertEquals("156", r.id)
        assertEquals(2, r.channel)
        assertEquals((72.5 - 32) / 1.8, value(r, "Temperature"), 1e-6)
        assertEquals(45.0, value(r, "Humidity"), 1e-9)
    }

    @Test fun inkbirdIth20rThroughTheFskSlicer() {
        val m = IntArray(19)
        m[0] = 0x55; m[1] = 0x03; m[2] = 0x81; m[3] = 0x10 // subtype
        m[4] = 1; m[5] = 0x21; m[6] = 0x43; m[7] = 90; m[8] = 0x34; m[9] = 0x12
        m[10] = 235; m[11] = 0 // 23.5 °C
        m[12] = 0x0A; m[13] = 0x01 // the probe, 26.6 °C
        m[14] = 0xEE; m[15] = 0x01 // 49.4 %
        var crc = 0x86F4
        for (i in 0 until 16) { crc = crc xor m[i]; repeat(8) { crc = if (crc and 1 != 0) (crc shr 1) xor 0xA001 else crc shr 1 } }
        m[16] = crc and 0xff; m[17] = crc shr 8
        val b = bits(intArrayOf(0xaa, 0xaa, 0xaa, 0x2d, 0xd4)) + bits(m) + listOf(0)
        val r = one(pcm(b, 100f, true), "Inkbird")
        assertEquals("${0x1234}", r.id)
        assertEquals(23.5, value(r, "Temperature"), 1e-9)
        assertEquals(49.4, value(r, "Humidity"), 1e-9)
    }

    @Test fun ecowittWs90ThroughTheFskSlicer() {
        val b = IntArray(32)
        b[0] = 0x90; b[1] = 0x0A; b[2] = 0xBC; b[3] = 0xDE
        b[4] = 0x01; b[5] = 0xF4 // 5000 lux
        b[6] = 150 // 3.0 V
        b[7] = 0x00; b[8] = 0xF5 // 245 → -15.5 °C… raw 0x0F5 = 245, (245-400)/10
        b[9] = 80; b[10] = 23; b[11] = 180; b[12] = 41; b[13] = 12
        b[14] = 0x03; b[15] = 0xF5 // 1013 hPa
        b[19] = 0x00; b[20] = 0x7B // 12.3 mm
        for (i in listOf(16, 17, 18) + (21..29)) b[i] = 0x5A // what the station sends there, not long zero runs
        b[30] = Ism.crc8(b, 30, 0x31, 0x00)
        b[31] = b.take(31).sum() and 0xff
        val bits = bits(intArrayOf(0xaa, 0xaa, 0x2d, 0xd4)) + bits(b) + listOf(0)
        val r = one(pcm(bits, 58f, true), "Ecowitt WS90")
        assertEquals("0abcde", r.id)
        assertEquals(-15.5, value(r, "Temperature"), 1e-9)
        assertEquals(1013.0, value(r, "Pressure"), 1e-9)
        assertEquals(12.3, value(r, "Rain"), 1e-9)
        assertEquals(2.3, value(r, "Wind"), 1e-9)
    }

    @Test fun bresserLeakageFromItsBits() {
        val m = IntArray(18)
        intArrayOf(0x11, 0x22, 0x33, 0x44, (5 shl 4) or 0x08 or 2, 0x80 or 0x30).copyInto(m, 2)
        val crc = Ism.crc16(m, 5, 0x1021, 0x0000, 2)
        m[0] = crc shr 8; m[1] = crc and 0xff
        val b = bits(intArrayOf(0xaa, 0xaa, 0x2d, 0xd4)) + bits(m) + listOf(0)
        val r = one(pcm(b, 124f, true), "Bresser water")
        assertEquals("11223344", r.id)
        assertEquals("Leak!", text(r, "State"))
    }

    @Test fun itronScmPlusFromItsBits() {
        val b = IntArray(16)
        intArrayOf(0x16, 0xA3, 0x1E, 0xAB, 0x04, 0x10, 0x20, 0x5B, 0x00, 0x00, 0x1A, 0xE3, 0x49, 0x00).copyInto(b)
        val crc = Ism.crc16(b, 12, 0x1021, 0x0971, 2)
        b[14] = crc shr 8; b[15] = crc and 0xff
        val ib = IsmBits()
        bits(b).forEach { ib.addBit(it) }
        val r = Ism.decodeBits("Itron ERT SCM+", ib).single()
        assertEquals("${0x0410205B}", r.id)
        assertEquals(6883.0, value(r, "Consumption"), 1e-9)
        assertTrue(r.model.contains("Water"))
    }

    @Test fun tfaDropFromItsBits() {
        val b = IntArray(9)
        intArrayOf(0x31, 0x23, 0x45, 0x00, 0x2A, 0x00, 0x01).copyInto(b)
        b[7] = Ism.lfsrDigest8Reflect(b, 7, 0x31, 0xf4)
        val ib = IsmBits()
        repeat(2) { r -> if (r > 0) ib.addRow(); bits(b, 66).forEach { ib.addBit(1 - it) } } // sent inverted
        val r = Ism.decodeBits("TFA 30.3233", ib).single()
        assertEquals("12345", r.id)
        assertEquals(((0x01 shl 8 or 0x2A) + 10) * 0.254, value(r, "Rain"), 1e-9)
    }

    @Test fun noiseGivesNoneOfTheNewDevices() {
        val rnd = java.util.Random(11)
        repeat(300) {
            val p = List(40 + rnd.nextInt(200)) { IsmPulse(30f + rnd.nextInt(2500), 30f + rnd.nextInt(5000)) }
            assertTrue(Ism.decode(IsmTrain(rnd.nextBoolean(), p)).isEmpty())
        }
    }
}
