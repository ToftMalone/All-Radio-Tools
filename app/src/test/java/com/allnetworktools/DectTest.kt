package com.allnetworktools

import com.allnetworktools.data.sdr.Dect
import com.allnetworktools.data.sdr.DectBurst
import com.allnetworktools.data.sdr.DectReceiver
import com.allnetworktools.data.sdr.DectTracker
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A base station's bursts, built as EN 300 175 lays them out, sent as GFSK through the receiver. */
class DectTest {
    private val rfpi = Dect.rfpiA(0x1234, 0x0ABCD)

    @Test
    fun aFieldAndIdentity() {
        val a = Dect.AField(Dect.makeAField(Dect.TA_NT, 0, rfpi.value))
        assertTrue(a.crcOk)
        assertEquals(Dect.TA_NT, a.ta)
        val r = Dect.Rfpi(a.tail)
        assertEquals(0, r.arc)
        assertEquals(0x1234, r.emc)
        assertEquals(0x0ABCD, r.fpn)
        assertTrue(!Dect.AField(Dect.makeAField(Dect.TA_NT, 0, rfpi.value) xor (1L shl 30)).crcOk)
        assertEquals(1_897_344_000L, Dect.Band.Europe.carrierHz(0))
        assertEquals(1_881_792_000L, Dect.Band.Europe.carrierHz(9))
    }

    /** HackRF bytes at 4.608 MS/s, the carrier 1.152 MHz above the tuning: the base in slot 3, a handset in slot 15. */
    @Test
    fun findsTheBase() {
        val rate = 4_608_000.0
        val frames = 12
        val n = (rate * (0.02 + frames * Dect.FRAME_S)).toInt()
        val freq = DoubleArray(n)
        val on = BooleanArray(n)
        val rnd = Random(2)
        fun burst(start: Int, sync: Long, aField: Long) {
            val bits = ArrayList<Int>()
            for (i in 31 downTo 0) bits += ((sync shr i) and 1).toInt()
            for (i in 63 downTo 0) bits += ((aField shr i) and 1).toInt()
            repeat(320) { bits += rnd.nextInt(2) }
            bits.forEachIndexed { k, b -> for (s in 0 until 4) { val i = start + 4 * k + s; freq[i] = if (b == 1) 288e3 else -288e3; on[i] = true } }
        }
        val slot = rate * Dect.FRAME_S / Dect.SLOTS
        val lead = (rate * 0.02).toInt()
        for (f in 0 until frames) {
            val base = lead + (f * rate * Dect.FRAME_S).toInt()
            val a = if (f % 4 == 0) Dect.makeAField(Dect.TA_NT, 0, rfpi.value) else Dect.makeAField(Dect.TA_QT, 0, rnd.nextLong() and 0xFFFFFFFFFFL)
            burst(base + (3 * slot).toInt(), Dect.SYNC_RFP, a)
            burst(base + (15 * slot).toInt(), Dect.SYNC_PP, Dect.makeAField(0, 0, 0x123456789L))
        }
        val out = ByteArray(2 * n)
        var ph = 0.0
        var sm = 0.0
        for (i in 0 until n) {
            sm += (freq[i] - sm) * 0.35 // the Gaussian filter, roughly
            ph += 2 * PI * (1_152_000.0 + 20_000.0 + sm) / rate
            val amp = if (on[i]) 50.0 else 0.0
            out[2 * i] = (amp * cos(ph) + (rnd.nextDouble() - 0.5) * 12).roundToInt().toByte()
            out[2 * i + 1] = (amp * sin(ph) + (rnd.nextDouble() - 0.5) * 12).roundToInt().toByte()
        }
        val bursts = ArrayList<DectBurst>()
        val rx = DectReceiver { bursts += it }
        var o = 0
        while (o < out.size) { val k = minOf(262144, out.size - o); rx.feed(out.copyOfRange(o, o + k), k); o += k }
        assertEquals(frames, bursts.count { it.fromBase })
        assertEquals(frames, bursts.count { !it.fromBase })
        val t = DectTracker()
        bursts.forEach { t.update(it, 4, 0L) }
        val bases = t.bases()
        assertEquals(1, bases.size)
        assertEquals(rfpi.value, bases[0].rfpi.value)
        // Frame 0 carries the identity, so every system information burst after it finds its base by its place in the frame.
        assertEquals(frames, bases[0].bursts)
        assertEquals(frames - frames / 4, bases[0].qt)
        assertEquals(setOf(4), bases[0].carriers)
        assertEquals(1, Integer.bitCount(bases[0].slots))
        assertEquals(frames, t.handsetBursts)
    }
}
