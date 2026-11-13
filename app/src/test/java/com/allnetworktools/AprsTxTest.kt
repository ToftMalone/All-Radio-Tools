package com.allnetworktools

import com.allnetworktools.data.sdr.AfskDemodulator
import com.allnetworktools.data.sdr.AprsReceiver
import com.allnetworktools.data.sdr.AprsReport
import com.allnetworktools.data.sdr.AprsTx
import com.allnetworktools.data.sdr.Ax25
import com.allnetworktools.data.sdr.AudioModulator
import com.allnetworktools.data.sdr.BufferAudio
import com.allnetworktools.data.sdr.TxMode
import com.allnetworktools.data.sdr.TxStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AprsTxTest {
    private val info = AprsTx.positionInfo(48.8566, 2.3522, "/[", "All Radio Tools test", 35.0)

    @Test fun positionTextFormat() {
        assertEquals("!4851.40N/00221.13E[/A=000114 All Radio Tools test", info)
        assertEquals("3352.00S", AprsTx.latitude(-33.8667))
        assertEquals("15112.00E", AprsTx.longitude(151.2))
    }

    @Test fun frameParsesBack() {
        val f = Ax25.parse(AprsTx.frame("F4ABC-9", info))
        assertNotNull(f)
        assertEquals("F4ABC-9", f!!.src)
        assertEquals("APZART", f.dst)
        assertEquals(listOf("WIDE1-1", "WIDE2-1"), f.path)
        assertEquals(info, String(f.info, Charsets.US_ASCII))
    }

    /** The audio goes through the app's own AFSK demodulator, FCS checked by its HDLC decoder. */
    @Test fun audioDecodesWithTheAfskDemodulator() {
        val got = ArrayList<ByteArray>()
        val d = AfskDemodulator(48_000.0) { got += it }
        val a = AprsTx.beaconAudio("F4ABC-9", info, 48_000)
        d.feedAudio(a, a.size)
        assertEquals(1, got.size)
        assertEquals("F4ABC-9", Ax25.parse(got[0])!!.src)
    }

    /** All the way: FM at the transmit rate, received by the APRS tool's receiver as the radio would hand it over. */
    @Test fun fmBeaconIsReceivedByTheAprsTool() {
        val a = AprsTx.beaconAudio("F4ABC-9", info, 40_000)
        val stream = TxStream(AudioModulator(BufferAudio(a, 40_000), TxMode.Fm, deviationHz = 3_000.0, voice = false), 2_000_000, 500_000.0)
        val got = ArrayList<AprsReport>()
        val rx = AprsReceiver { got += it }
        val buf = ByteArray(131_072)
        var guard = 0
        while (!stream.finished && guard++ < 200) { stream.fill(buf); rx.feed(buf, buf.size) }
        assertEquals(1, got.size)
        assertEquals("F4ABC-9", got[0].src)
        assertEquals(48.8566, got[0].lat!!, 0.001)
        assertEquals(2.3522, got[0].lon!!, 0.001)
    }
}
