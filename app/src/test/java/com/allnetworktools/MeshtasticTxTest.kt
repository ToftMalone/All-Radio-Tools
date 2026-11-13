package com.allnetworktools

import com.allnetworktools.data.sdr.LoraEncoder
import com.allnetworktools.data.sdr.LoraModulator
import com.allnetworktools.data.sdr.MeshFrame
import com.allnetworktools.data.sdr.MeshRadio
import com.allnetworktools.data.sdr.Meshtastic
import com.allnetworktools.data.sdr.MeshtasticTx
import com.allnetworktools.data.sdr.TxStream
import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshtasticTxTest {
    private val node = 0x2a5b3c4d

    /** The transmit bit layer in the app is the test transmitter checked against gr-lora_sdr. */
    @Test fun encoderMatchesTheReferenceTransmitter() {
        val rnd = Random(3)
        for (len in listOf(3, 17, 60, 120)) {
            val p = ByteArray(len).also(rnd::nextBytes)
            for (cr in 1..4) assertArrayEquals(LoraTx.symbols(p, 11, cr), LoraEncoder.symbols(p, 11, cr))
            assertArrayEquals(LoraTx.symbols(p, 12, 1, ldro = true), LoraEncoder.symbols(p, 12, 1, ldro = true))
        }
    }

    @Test fun packetsDecodeWithTheMeshtasticDecoder() {
        val user = MeshtasticTx.user(node, "All Radio Tools 3c4d", "3c4d")
        val pkt = MeshtasticTx.packet(node, 12345, "LongFast", Meshtastic.DEFAULT_PSK, MeshtasticTx.data(Meshtastic.PORT_NODEINFO, user))
        val p = Meshtastic.decode(pkt)
        assertNotNull(p)
        assertEquals(node, p!!.header.from)
        assertEquals(Meshtastic.BROADCAST, p.header.to)
        assertEquals(3, p.header.hopLimit)
        assertEquals(3, p.header.hopStart)
        assertEquals(Meshtastic.PORT_NODEINFO, p.data!!.port)
        val u = Meshtastic.user(p.data!!.payload)!!
        assertEquals("!2a5b3c4d", u.id)
        assertEquals("All Radio Tools 3c4d", u.longName)
        assertEquals("3c4d", u.shortName)
        assertEquals(MeshtasticTx.HW_PRIVATE, u.hwModel)
    }

    /** A reply is a directed packet whose Data carries the request's id; a traceroute reply keeps the route it was given. */
    @Test fun repliesAreDirectedAndAnswerTheRequest() {
        val asker = 0x1234abcd
        val route = MeshtasticTx.routeDiscovery(listOf(0x11111111, 0x22222222))
        val pkt = MeshtasticTx.packet(node, 77, "LongFast", Meshtastic.DEFAULT_PSK, MeshtasticTx.data(Meshtastic.PORT_TRACEROUTE, route, requestId = 555), to = asker)
        val p = Meshtastic.decode(pkt)!!
        assertEquals(asker, p.header.to)
        assertEquals(node, p.header.from)
        assertEquals(Meshtastic.PORT_TRACEROUTE, p.data!!.port)
        assertEquals(555, p.data!!.requestId)
        assertEquals(listOf(0x11111111, 0x22222222), Meshtastic.route(p.data!!.payload)!!.towards)
        val ack = Meshtastic.decode(MeshtasticTx.packet(node, 78, "LongFast", Meshtastic.DEFAULT_PSK, MeshtasticTx.data(Meshtastic.PORT_ROUTING, MeshtasticTx.routingAck(), requestId = 9), to = asker))!!
        assertEquals(0, Meshtastic.routingError(ack.data!!.payload))
        assertEquals(9, ack.data!!.requestId)
    }

    @Test fun positionPrecision() {
        val exact = Meshtastic.position(MeshtasticTx.position(48.8566, 2.3522, 35, 1_700_000_000, 32))!!
        assertEquals(48.8566, exact.lat!!, 1e-6)
        assertEquals(2.3522, exact.lon!!, 1e-6)
        assertEquals(35, exact.altitudeM)
        assertEquals(1_700_000_000L, exact.timeS)
        val rough = Meshtastic.position(MeshtasticTx.position(48.8566, 2.3522, null, 1_700_000_000, 13))!!
        assertEquals(13, rough.precisionBits)
        // About 6 km cells: close, not exact.
        assertEquals(48.8566, rough.lat!!, 0.06)
        assertTrue(kotlin.math.abs(rough.lat!! - 48.8566) > 1e-5)
    }

    /** All the way: LongFast at 2 MS/s as the HackRF would send it, through the Meshtastic tool's receiver. */
    @Test fun longFastIsReceivedByTheMeshtasticTool() {
        val pos = MeshtasticTx.position(48.8566, 2.3522, 35, 1_700_000_000, 32)
        val pkt = MeshtasticTx.packet(node, 777, "LongFast", Meshtastic.DEFAULT_PSK, MeshtasticTx.data(Meshtastic.PORT_POSITION, pos))
        val band = Meshtastic.EU868
        val stream = TxStream(LoraModulator(LoraEncoder.symbols(pkt, 11, 1), 11, 250_000.0, 2_000_000), 2_000_000, (869_525_000L - band.loHz).toDouble())
        val got = ArrayList<MeshFrame>()
        val radio = MeshRadio(band) { got += it }
        val buf = ByteArray(131_072)
        var guard = 0
        // Some silence first, as a receiver would hear before the frame.
        radio.feed(ByteArray(131_072), 131_072)
        while (!stream.finished && guard++ < 100) { stream.fill(buf); radio.feed(buf, buf.size) }
        radio.close()
        assertEquals(1, got.size)
        assertTrue(got[0].frame.crcOk)
        val p = Meshtastic.decode(got[0].frame.payload)!!
        assertEquals(node, p.header.from)
        val position = Meshtastic.position(p.data!!.payload)!!
        assertEquals(48.8566, position.lat!!, 1e-6)
    }
}
