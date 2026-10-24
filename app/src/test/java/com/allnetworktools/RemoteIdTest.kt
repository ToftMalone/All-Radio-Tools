package com.allnetworktools

import com.allnetworktools.data.remoteid.DroneTracker
import com.allnetworktools.data.remoteid.IdType
import com.allnetworktools.data.remoteid.Message
import com.allnetworktools.data.remoteid.RemoteId
import com.allnetworktools.data.remoteid.Status
import com.allnetworktools.data.remoteid.UaType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Messages built byte by byte from ASTM F3411 (the layout opendroneid-core-c encodes). */
class RemoteIdTest {
    private fun msg(fill: ByteBuffer.() -> Unit) = ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN).apply(fill).array()
    private fun ascii(s: String, n: Int) = s.toByteArray(Charsets.US_ASCII).copyOf(n)
    private fun alt(m: Double) = ((m + 1000) / 0.5).toInt().toShort()

    private val basic = msg { put(0x02); put(0x12); put(ascii("1581F5FKD229001234", 20)) }
    private val location = msg {
        put(0x12); put(0x26) // airborne, height above ground, direction >= 180, speed x0.25
        put(90); put(40); put(3)
        putInt(488_584_000); putInt(22_945_000)
        putShort(alt(100.0)); putShort(alt(120.0)); putShort(alt(50.0))
        put(0x3A); put(0x34); putShort(1234); put(0x01); put(0)
    }
    private val system = msg {
        put(0x42); put(0x05) // EU classification, live GNSS operator location
        putInt(488_500_000); putInt(22_900_000)
        putShort(1); put(0); putShort(0); putShort(0)
        put(0x11) // open category, class C1
        putShort(alt(35.0)); putInt(0); put(0)
    }
    private val operator = msg { put(0x52); put(0); put(ascii("FRAoPeRaToR0001", 20)) }

    @Test fun decodesBasicIdFromLegacyServiceData() {
        val m = RemoteId.parseServiceData(byteArrayOf(0x0D, 7) + basic).single() as Message.BasicId
        assertEquals(IdType.Serial, m.idType)
        assertEquals(UaType.Multirotor, m.uaType)
        assertEquals("1581F5FKD229001234", m.id)
    }

    @Test fun decodesLocation() {
        val m = RemoteId.parseMessage(location).single() as Message.Location
        assertEquals(Status.Airborne, m.status)
        assertTrue(m.heightAgl)
        assertEquals(270, m.directionDeg)
        assertEquals(10.0, m.speedMs!!, 1e-9)
        assertEquals(1.5, m.verticalMs!!, 1e-9)
        assertEquals(48.8584, m.lat!!, 1e-7)
        assertEquals(2.2945, m.lon!!, 1e-7)
        assertEquals(100.0, m.altBaroM!!, 1e-9)
        assertEquals(120.0, m.altGeoM!!, 1e-9)
        assertEquals(50.0, m.heightM!!, 1e-9)
        assertEquals(1234, m.tenthsAfterHour)
    }

    @Test fun highSpeedUsesTheSecondScale() {
        val fast = location.copyOf().also { it[1] = 0x21; it[3] = 10 } // multiplier bit set
        val m = RemoteId.parseMessage(fast).single() as Message.Location
        assertEquals(10 * 0.75 + 63.75, m.speedMs!!, 1e-9)
    }

    @Test fun unknownValuesStayEmpty() {
        val blank = msg { put(0x12); put(0); put(0); put(255.toByte()); put(126) }
        val m = RemoteId.parseMessage(blank).single() as Message.Location
        assertNull(m.lat); assertNull(m.speedMs); assertNull(m.verticalMs); assertNull(m.altGeoM)
    }

    @Test fun decodesSystemAndOperator() {
        val s = RemoteId.parseMessage(system).single() as Message.System
        assertEquals(48.85, s.operatorLat!!, 1e-7)
        assertEquals(2.29, s.operatorLon!!, 1e-7)
        assertEquals(1, s.euCategory); assertEquals(1, s.euClass)
        assertEquals(35.0, s.operatorAltGeoM!!, 1e-9)
        assertEquals("FRAoPeRaToR0001", (RemoteId.parseMessage(operator).single() as Message.OperatorId).id)
    }

    @Test fun decodesMessagePackFromLongRangeAdvertising() {
        val pack = byteArrayOf(0xF2.toByte(), 25, 4) + basic + location + system + operator
        val msgs = RemoteId.parseServiceData(byteArrayOf(0x0D, 1) + pack)
        assertEquals(4, msgs.size)
        assertTrue(msgs[0] is Message.BasicId && msgs[1] is Message.Location && msgs[2] is Message.System && msgs[3] is Message.OperatorId)
    }

    @Test fun decodesWifiBeaconVendorElementAndNan() {
        val pack = byteArrayOf(0xF2.toByte(), 25, 2) + basic + location
        val ie = byteArrayOf(0xFA.toByte(), 0x0B, 0xBC.toByte(), 0x0D, 42) + pack
        val msgs = RemoteId.parseWifiVendorIe(ie)
        assertEquals(2, msgs.size)
        assertEquals("1581F5FKD229001234", (msgs[0] as Message.BasicId).id)
        assertTrue(RemoteId.parseWifiVendorIe(byteArrayOf(0x00, 0x50, 0xF2.toByte(), 0x0D, 42) + pack).isEmpty())
        assertEquals(2, RemoteId.parseNan(byteArrayOf(7) + pack).size)
    }

    @Test fun ignoresOtherServiceData() {
        assertTrue(RemoteId.parseServiceData(byteArrayOf(0x0C, 1) + basic).isEmpty())
        assertTrue(RemoteId.parseServiceData(byteArrayOf(0x0D, 1, 2, 3)).isEmpty())
    }

    @Test fun trackerMergesMessagesFromOneTransmitter() {
        val t = DroneTracker()
        assertTrue(t.add("AA", RemoteId.parseMessage(location), -70, 1_000))
        assertFalse(t.add("AA", RemoteId.parseMessage(basic) + RemoteId.parseMessage(system) + RemoteId.parseMessage(operator), -65, 2_000))
        val d = t.list(2_000).single()
        assertEquals("1581F5FKD229001234", d.title)
        assertEquals(48.8584, d.lat!!, 1e-7)
        assertEquals(48.85, d.operatorLat!!, 1e-7)
        assertEquals("FRAoPeRaToR0001", d.operatorId)
        assertEquals(1, d.euClass)
        assertEquals(-65, d.rssi)
        assertTrue(d.active(2_000))
        assertFalse(d.active(2_000 + 31_000))
        t.prune(2_000 + 31 * 60_000)
        assertTrue(t.list(0).isEmpty())
    }

    @Test fun distanceAndBearing() {
        // 0.0154° of latitude due north is about 1.7 km.
        val m = RemoteId.distanceM(48.8584, 2.2945, 48.8738, 2.2950)
        assertEquals(1712.0, m, 20.0)
        assertEquals(1.0, RemoteId.bearingDeg(48.8584, 2.2945, 48.8738, 2.2950), 2.0)
    }
}
