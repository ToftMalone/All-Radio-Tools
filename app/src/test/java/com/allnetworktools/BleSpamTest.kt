package com.allnetworktools

import com.allnetworktools.data.AdStructure
import com.allnetworktools.data.BleDevice
import com.allnetworktools.data.BleKind
import com.allnetworktools.data.BleSpam
import com.allnetworktools.data.BleSpamWatch
import com.allnetworktools.data.FlipperColor
import com.allnetworktools.data.SpamKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleSpamTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun mfg(id: Int, data: String) = AdStructure(0xFF, byteArrayOf(id.toByte(), (id shr 8).toByte()) + hex(data))
    private fun dev(addr: String, ads: List<AdStructure>, seen: Long, name: String? = null, rssi: Int = -50) =
        BleDevice(addr, name, rssi, null, BleKind.Unknown, null, true, seen, ads = ads)

    // Payloads as the Bluetooth-LE-Spam generators build them.
    private val airpods = listOf(mfg(0x004C, "071907022075AA3001000045121212000000000000000000000000"))
    private val actionModal = listOf(mfg(0x004C, "0F05C001601F1C"))
    private val fastPair = listOf(AdStructure(0x16, hex("2CFE" + "CD8256")))
    private val swiftPair = listOf(mfg(0x0006, "0300800153776966"))
    private val galaxyWatch = listOf(mfg(0x0075, "010002000101FF000043" + "01"))

    @Test fun signatures() {
        assertEquals(SpamKind.AppleProximity, BleSpam.classify(airpods))
        assertEquals(SpamKind.AppleAction, BleSpam.classify(actionModal))
        assertEquals(SpamKind.FastPair, BleSpam.classify(fastPair))
        assertEquals(SpamKind.SwiftPair, BleSpam.classify(swiftPair))
        assertEquals(SpamKind.SamsungEasySetup, BleSpam.classify(galaxyWatch))
        assertEquals(SpamKind.Lovespouse, BleSpam.classify(listOf(mfg(0xFFFF, "006DB643CE97FE427CE49C6C"))))
        // An ordinary iBeacon and an empty advertisement are not pop-ups.
        assertNull(BleSpam.classify(listOf(mfg(0x004C, "0215" + "00".repeat(21)))))
        assertNull(BleSpam.classify(emptyList()))
    }

    @Test fun flipperByColourOrName() {
        assertEquals(FlipperColor.White to false, BleSpam.flipper(listOf(AdStructure(0x03, hex("8230"))), null))
        assertEquals(FlipperColor.Black to false, BleSpam.flipper(listOf(AdStructure(0x03, hex("8130"))), null))
        assertEquals(FlipperColor.Transparent to false, BleSpam.flipper(listOf(AdStructure(0x03, hex("8330"))), null))
        assertEquals(FlipperColor.Unknown to true, BleSpam.flipper(emptyList(), "Flipper Ufubih"))
        assertNull(BleSpam.flipper(emptyList(), "AirPods"))
    }

    @Test fun aFewRealAirPodsAreNotAnAttack() {
        val w = BleSpamWatch()
        // Three pairs of AirPods sitting there for a minute, same addresses.
        val list = (1..3).map { dev("AA:00:00:00:00:0$it", airpods, 0) }
        w.update(list, 1_000, 0)
        w.update(list.map { it.copy(lastSeen = 60_000) }, 60_000, 60_000)
        assertFalse(w.attacking)
        assertEquals(1, w.kinds.size)
        assertTrue(w.incidents.isEmpty())
    }

    @Test fun aFloodOfNewAddressesIsAnAttackThenEnds() {
        val w = BleSpamWatch()
        // A spammer: a new address every 500 ms for 6 s.
        val heard = ArrayList<BleDevice>()
        for (k in 0 until 12) {
            heard += dev("C0:00:00:00:00:%02X".format(k), fastPair, 100_000L + k * 500)
            w.update(heard.toList(), 100_000L + k * 500, 1_700_000_000_000L + k * 500)
        }
        assertTrue(w.attacking)
        assertEquals(1, w.incidents.size)
        assertNull(w.incidents[0].endMs)
        assertTrue(w.incidents[0].peakFresh >= 6)
        // Quiet for 15 s: the attack is over and stays in the history.
        w.update(heard.toList(), 120_000, 1_700_000_020_000L)
        assertFalse(w.attacking)
        assertEquals(1, w.incidents.size)
        assertTrue(w.incidents[0].endMs != null)
    }

    @Test fun flippersAreListed() {
        val w = BleSpamWatch()
        w.update(listOf(dev("80:E1:26:00:00:01", listOf(AdStructure(0x03, hex("8130"))), 1_000, "Flipper Ufubih", -42)), 1_000, 0)
        assertEquals(1, w.flippers.size)
        assertEquals(FlipperColor.Black, w.flippers[0].color)
    }
}
