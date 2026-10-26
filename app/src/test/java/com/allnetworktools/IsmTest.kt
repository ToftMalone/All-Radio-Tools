package com.allnetworktools

import com.allnetworktools.data.sdr.Ism
import com.allnetworktools.data.sdr.IsmPulse
import com.allnetworktools.data.sdr.IsmReading
import com.allnetworktools.data.sdr.IsmReceiver
import com.allnetworktools.data.sdr.IsmTrain
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IsmTest {
    private fun bits(bytes: IntArray, n: Int = bytes.size * 8) = (0 until n).map { (bytes[it / 8] shr (7 - it % 8)) and 1 }

    /** Nexus: 36 bits PPM (500 µs pulses, 1000 / 2000 µs gaps), four rows 3.9 ms apart. */
    private fun nexusTrain(): IsmTrain {
        val frame = bits(intArrayOf(0x5A, 0x90, 0xD7, 0xF3, 0x70), 36)
        val p = ArrayList<IsmPulse>()
        repeat(4) { r ->
            frame.forEach { b -> p += IsmPulse(500f, if (b == 1) 2000f else 1000f) }
            p += IsmPulse(500f, if (r == 3) 1e6f else 3900f)
        }
        return IsmTrain(false, p)
    }

    /** NRZ bits to marks and spaces of [us] each. */
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

    private fun tx29Bits(): List<Int> {
        val id = 0x2B
        val b = intArrayOf(0x90 or (id shr 2), ((id and 3) shl 6) or 6, 0x13, 0x37, 0)
        b[4] = Ism.crc8(b, 4, 0x31, 0)
        return bits(intArrayOf(0xAA, 0xAA, 0xAA, 0x2D, 0xD4)) + bits(b) + bits(intArrayOf(0x00))
    }

    @Test fun nexusFromOokPpm() {
        val r = Ism.decode(nexusTrain()).single()
        assertEquals("Nexus-TH", r.model)
        assertEquals("90", r.id)
        assertEquals(2, r.channel)
        assertEquals(21.5, r.fields.first { it.name == "Temperature" }.value, 1e-9)
        assertEquals(55.0, r.fields.first { it.name == "Humidity" }.value, 1e-9)
    }

    @Test fun lacrosseTx29FromFskPcm() {
        val r = Ism.decode(IsmTrain(true, pcm(tx29Bits(), 55f))).single()
        assertEquals("LaCrosse-TX29IT", r.model)
        assertEquals("43", r.id)
        assertEquals(21.3, r.fields.first { it.name == "Temperature" }.value, 1e-6)
    }

    @Test fun bresser6in1() {
        val msg = IntArray(18)
        intArrayOf(0x12, 0x34, 0x56, 0x78, 0x11, 0xFF, 0xFF, 0xFF, 0x00, 0x00, 0x21, 0x52, 0x55, 0xFF, 0xF0).copyInto(msg, 2)
        msg[17] = (0xff - msg.drop(2).take(15).sum()) and 0xff
        val d = Ism.lfsrDigest16(msg, 15, 0x8810, 0x5412, off = 2)
        msg[0] = d shr 8; msg[1] = d and 0xff
        val b = bits(intArrayOf(0xAA, 0xAA, 0xAA, 0xAA, 0x2D, 0xD4)) + bits(msg) + bits(intArrayOf(0))
        val r = Ism.decode(IsmTrain(true, pcm(b, 124f))).single()
        assertEquals("Bresser-6in1", r.model)
        assertEquals("12345678", r.id)
        assertEquals(21.5, r.fields.first { it.name == "Temperature" }.value, 1e-9)
        assertEquals(55.0, r.fields.first { it.name == "Humidity" }.value, 1e-9)
    }

    @Test fun badChecksumOrNoiseGiveNothing() {
        val t = nexusTrain()
        // One row only: Nexus has no checksum and needs the repetition.
        assertTrue(Ism.decode(IsmTrain(false, t.p.take(37))).isEmpty())
        val rnd = Random(2)
        repeat(50) {
            val p = List(60) { IsmPulse(rnd.nextInt(50, 3000).toFloat(), rnd.nextInt(50, 3000).toFloat()) }
            assertTrue(Ism.decode(IsmTrain(rnd.nextBoolean(), p)).isEmpty())
        }
    }

    /** From HackRF bytes: 2 MS/s, the sensor fs/4 + 30 kHz above the tuning. */
    private fun capture(segments: List<Pair<Float, Int>>, fsk: Boolean): ByteArray {
        val rate = 2e6
        val lead = 60_000
        val total = lead * 2 + segments.sumOf { (it.first * 2).roundToInt() }
        val out = ByteArray(total * 2)
        val rnd = Random(9)
        var ph = 0.0
        var i = 0
        fun put(level: Int, n: Int, on: Boolean) {
            repeat(n) {
                val f = rate / 4 + 30_000 + if (fsk) (if (level == 1) 35_000.0 else -35_000.0) else 0.0
                ph += 2 * PI * f / rate
                val a = if (on) 50.0 else 0.0
                out[2 * i] = (a * cos(ph) + rnd.nextInt(-3, 4)).roundToInt().toByte()
                out[2 * i + 1] = (a * sin(ph) + rnd.nextInt(-3, 4)).roundToInt().toByte()
                i++
            }
        }
        put(0, lead, false)
        for ((us, level) in segments) put(level, (us * 2).roundToInt(), fsk || level == 1)
        put(0, total - i, false)
        return out
    }

    private fun receive(bytes: ByteArray): List<IsmReading> {
        val got = ArrayList<IsmReading>()
        val rx = IsmReceiver({ r, _ -> got += r })
        var i = 0
        while (i < bytes.size) { val k = minOf(131_072, bytes.size - i); rx.feed(bytes.copyOfRange(i, i + k), k); i += k }
        return got
    }

    @Test fun ookFromHackRf() {
        val segs = nexusTrain().p.flatMap { listOf(it.pulseUs to 1, minOf(it.gapUs, 20_000f) to 0) }
        val r = receive(capture(segs, fsk = false)).single()
        assertEquals("Nexus-TH", r.model)
    }

    @Test fun fskFromHackRf() {
        val segs = tx29Bits().map { 55f to it }
        val r = receive(capture(segs, fsk = true)).single()
        assertEquals("LaCrosse-TX29IT", r.model)
    }
}
