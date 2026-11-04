package com.allnetworktools

import com.allnetworktools.data.sdr.Aprs
import com.allnetworktools.data.sdr.LoraAprs
import com.allnetworktools.data.sdr.LoraPhy
import com.allnetworktools.data.sdr.MeshFrame
import com.allnetworktools.data.sdr.MeshRadio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Random
import kotlin.math.roundToInt

class LoraAprsTest {
    // A tracker's position beacon as LoRa_APRS_Tracker sends it: prefix, then the TNC2 line (compressed position).
    private val beacon = byteArrayOf(0x3C, 0xFF.toByte(), 0x01) + "F4ABC-7>APLRT1,WIDE1-1:!/4cX*TKjK>  GLoRa tracker".toByteArray(Charsets.ISO_8859_1)

    @Test fun parsesTnc2() {
        val f = LoraAprs.parse(beacon)!!
        assertEquals("F4ABC-7", f.src)
        assertEquals("APLRT1", f.dst)
        assertEquals(listOf("WIDE1-1"), f.path)
        val r = Aprs.decode(f)!!
        assertEquals("F4ABC-7", r.src)
        assertEquals(true, r.lat != null && r.lon != null)
        assertNull(LoraAprs.parse("not aprs at all".toByteArray()))
        assertNull(LoraAprs.parse(byteArrayOf(0x3C, 0xFF.toByte(), 0x01) + "F4ABC>APLRT1".toByteArray()))
    }

    @Test fun hearsBothChannelsThroughTheHackRf() {
        val band = LoraAprs.EU
        for ((hz, sf, cr) in listOf(Triple(433_775_000L, 12, 1), Triple(434_855_000L, 9, 3))) {
            val bw = 125_000.0
            val sym = LoraTx.symbols(beacon, sf, cr, ldro = LoraPhy.lowDataRate(sf, bw))
            val (re, im) = LoraTx.waveform(
                sym, sf, bw, 2_000_000.0, syncWord = LoraAprs.SYNC_WORD, preamble = 8, delayChips = 333.3, cfoHz = 4_000.0,
                ppm = 4_000.0 / hz * 1e6, mixHz = (hz - band.loHz).toDouble(), amplitude = 30.0,
            )
            val rnd = Random(hz)
            val buf = ByteArray(2 * re.size)
            for (i in re.indices) {
                buf[2 * i] = (re[i] + 5 * rnd.nextGaussian()).roundToInt().coerceIn(-127, 127).toByte()
                buf[2 * i + 1] = (im[i] + 5 * rnd.nextGaussian()).roundToInt().coerceIn(-127, 127).toByte()
            }
            val got = ArrayList<MeshFrame>()
            val radio = MeshRadio(band) { got += it }
            var i = 0
            while (i < buf.size) { val k = minOf(262144, buf.size - i); radio.feed(buf.copyOfRange(i, i + k), k); i += k }
            radio.close()
            val ok = got.filter { it.frame.crcOk }
            assertEquals("$hz", 1, ok.size)
            assertEquals("$hz", hz, ok[0].hz)
            assertEquals("$hz", LoraAprs.SYNC_WORD, ok[0].frame.syncWord)
            assertEquals("$hz", "F4ABC-7", LoraAprs.parse(ok[0].frame.payload)!!.src)
        }
    }
}
