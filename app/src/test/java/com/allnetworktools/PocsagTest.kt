package com.allnetworktools

import com.allnetworktools.data.sdr.Pocsag
import com.allnetworktools.data.sdr.PocsagBitClock
import com.allnetworktools.data.sdr.PocsagFramer
import com.allnetworktools.data.sdr.PocsagMessage
import com.allnetworktools.data.sdr.PocsagNetworks
import com.allnetworktools.data.sdr.PocsagReceiver
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PocsagTest {
    /** A page as a POCSAG transmitter sends it: 576 preamble bits, then batches of a sync word and 16 codewords. */
    private fun transmission(pages: List<Triple<Int, Int, List<Int>>>): List<Boolean> {
        val words = ArrayList<Int>()
        for ((ric, function, payload) in pages) {
            // The address goes in its own frame: two codewords per frame.
            while (words.size % 16 != (ric and 7) * 2) words += Pocsag.IDLE
            words += Pocsag.encode((((ric ushr 3) and 0x3FFFF) shl 2) or function)
            payload.forEach { words += Pocsag.encode((1 shl 20) or it) }
        }
        words += Pocsag.IDLE
        while (words.size % 16 != 0) words += Pocsag.IDLE
        val bits = ArrayList<Boolean>()
        repeat(576) { bits += it % 2 == 0 }
        fun word(w: Int) { for (k in 31 downTo 0) bits += (w ushr k) and 1 == 1 }
        words.chunked(16).forEach { batch -> word(Pocsag.SYNC); batch.forEach(::word) }
        repeat(64) { bits += it % 2 == 0 }
        return bits
    }

    /** Payload codewords: the bits of [text] packed 20 to a codeword, first sent first. */
    private fun pack(bits: List<Boolean>): List<Int> = bits.chunked(20).map { chunk ->
        var v = 0
        for (k in 0 until 20) v = (v shl 1) or (if (k < chunk.size && chunk[k]) 1 else 0)
        v
    }

    private fun alphaBits(text: String) = (text + "\u0004").flatMap { c -> (0 until 7).map { (c.code shr it) and 1 == 1 } }

    private fun numericBits(digits: String) = digits.flatMap { c ->
        val v = "0123456789*U -)(".indexOf(c)
        (0 until 4).map { (v shr it) and 1 == 1 }
    }

    private val pages = listOf(
        Triple(1_234_567, 3, pack(alphaBits("Intervention 12 rue des Lilas, 2e etage"))),
        Triple(204_808, 0, pack(numericBits("0612345678"))),
        Triple(999_999, 1, emptyList()),
    )

    private fun check(got: List<PocsagMessage>) {
        assertEquals(got.toString(), 3, got.size)
        assertEquals(1_234_567, got[0].ric)
        assertEquals('D', got[0].functionLetter)
        assertEquals(PocsagMessage.Kind.Alpha, got[0].kind)
        assertEquals("Intervention 12 rue des Lilas, 2e etage", got[0].text)
        assertEquals(204_808, got[1].ric)
        assertEquals(PocsagMessage.Kind.Numeric, got[1].kind)
        assertEquals("0612345678", got[1].text)
        assertEquals(999_999, got[2].ric)
        assertEquals(PocsagMessage.Kind.Tone, got[2].kind)
    }

    @Test fun bchFixesTwoBitErrors() {
        val cw = Pocsag.encode(0x12345)
        assertEquals(0, Pocsag.syndrome(cw))
        assertEquals(cw to 0, Pocsag.correct(cw))
        assertEquals(cw to 1, Pocsag.correct(cw xor (1 shl 17)))
        assertEquals(cw to 2, Pocsag.correct(cw xor (1 shl 3) xor (1 shl 30)))
        assertEquals(cw to 1, Pocsag.correct(cw xor 1))
        assertNull(Pocsag.correct(cw xor 0b111 shl 9))
        assertEquals(0, Pocsag.syndrome(Pocsag.SYNC))
        assertEquals(0, Pocsag.syndrome(Pocsag.IDLE))
    }

    @Test fun framerReadsMessagesWithErrorsAndEitherPolarity() {
        for (invert in listOf(false, true)) {
            val got = ArrayList<PocsagMessage>()
            val framer = PocsagFramer(1200, 466.075) { got += it }
            val bits = transmission(pages).toMutableList()
            // Two errors in some codewords, one in the sync word.
            for (i in listOf(600, 640, 700, 1000, 1010, 1300)) bits[i] = !bits[i]
            bits.forEach { framer.bit(it != invert) }
            check(got)
            assertTrue(got[0].corrected > 0)
        }
    }

    @Test fun chanceSyncInNoiseGivesNothing() {
        val got = ArrayList<PocsagMessage>()
        val framer = PocsagFramer(1200, 466.075) { got += it }
        val rnd = Random(11)
        repeat(2_000_000) { framer.bit(rnd.nextBoolean()) }
        assertTrue(got.toString(), got.isEmpty())
    }

    /** FSK ±4.5 kHz through an FM discriminator at 25 kS/s, at every bit rate. */
    @Test fun bitClockAtEveryBaud() {
        for (baud in PocsagReceiver.BAUDS) {
            val rate = 25_000.0
            val bits = transmission(pages)
            val spb = rate / baud
            val n = (bits.size * spb).roundToInt()
            val rnd = Random(baud)
            // A small frequency error and some noise; the clock is 0.1 % off too.
            val fm = FloatArray(n) { i ->
                val b = bits[((i * 1.001) / spb).toInt().coerceAtMost(bits.size - 1)]
                ((if (b) 4500.0 else -4500.0) + 700.0).toFloat() * (2 * PI / rate).toFloat() + (rnd.nextFloat() - 0.5f) * 0.6f
            }
            val got = ArrayList<PocsagMessage>()
            val framer = PocsagFramer(baud, 466.075) { got += it }
            val clock = PocsagBitClock(rate, baud) { framer.bit(it) }
            var i = 0
            while (i < n) { val k = minOf(1000, n - i); clock.feed(fm.copyOfRange(i, i + k), k); i += k }
            check(got)
        }
    }

    /** Two channels of e*Message France at once, from HackRF bytes, one page on each. */
    @Test fun receiverHearsSeveralChannels() {
        val channels = PocsagNetworks.France.channelsHz
        val rx = ArrayList<PocsagMessage>()
        val receiver = PocsagReceiver(channels) { rx += it }
        val rate = PocsagReceiver.SAMPLE_RATE.toDouble()
        val a = transmission(listOf(pages[0]))
        val b = transmission(listOf(pages[1]))
        val baud = 1200
        val spb = rate / baud
        val n = (maxOf(a.size, b.size) * spb).roundToInt() + 20_000
        val fa = (channels[0] - receiver.centerHz).toDouble()
        val fb = (channels[2] - receiver.centerHz).toDouble()
        val rnd = Random(5)
        val bytes = ByteArray(n * 2)
        var pa = 0.0
        var pb = 0.0
        for (i in 0 until n) {
            val ia = (i / spb).toInt()
            pa += 2 * PI * (fa + if (ia < a.size && a[ia]) 4500.0 else -4500.0) / rate
            pb += 2 * PI * (fb + if (ia < b.size && b[ia]) 4500.0 else -4500.0) / rate
            val sa = if (ia < a.size) 30.0 else 0.0
            val sb = if (ia < b.size) 30.0 else 0.0
            bytes[2 * i] = (sa * cos(pa) + sb * cos(pb) + rnd.nextInt(-4, 5)).roundToInt().toByte()
            bytes[2 * i + 1] = (sa * sin(pa) + sb * sin(pb) + rnd.nextInt(-4, 5)).roundToInt().toByte()
        }
        var i = 0
        while (i < bytes.size) { val k = minOf(131072, bytes.size - i); receiver.feed(bytes.copyOfRange(i, i + k), k); i += k }
        assertEquals(rx.toString(), 2, rx.size)
        val byFreq = rx.associateBy { it.freqMhz }
        assertEquals("Intervention 12 rue des Lilas, 2e etage", byFreq[466.025]!!.text)
        assertEquals("0612345678", byFreq[466.075]!!.text)
        assertTrue(rx.all { it.baud == 1200 })
    }

    @Test fun networksFitOneCapture() {
        PocsagNetworks.all.forEach { net ->
            val c = PocsagReceiver.centre(net.channelsHz)
            assertTrue(net.channelsHz.all { kotlin.math.abs(it - c) in 100_000..PocsagReceiver.MAX_SPAN_HZ / 2 })
        }
    }
}
