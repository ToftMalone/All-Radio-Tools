package com.allnetworktools

import com.allnetworktools.data.AdStructure
import com.allnetworktools.data.BleDevice
import com.allnetworktools.data.BleKind
import com.allnetworktools.data.BleSpam
import com.allnetworktools.data.BleSpamWatch
import com.allnetworktools.data.FlipperClue
import com.allnetworktools.data.FlipperColor
import com.allnetworktools.data.SpamReason
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
        assertEquals(SpamKind.AppleProximity, BleSpam.classify(airpods)?.kind)
        assertEquals(SpamKind.AppleAction, BleSpam.classify(actionModal)?.kind)
        assertEquals(SpamKind.FastPair, BleSpam.classify(fastPair)?.kind)
        assertEquals(SpamKind.SwiftPair, BleSpam.classify(swiftPair)?.kind)
        assertEquals(SpamKind.SamsungEasySetup, BleSpam.classify(galaxyWatch)?.kind)
        assertEquals(SpamKind.Lovespouse, BleSpam.classify(listOf(mfg(0xFFFF, "006DB643CE97FE427CE49C6C")))?.kind)
        // The iOS 17 crash frame: the same action with 00 00 10 hanging past its declared end.
        assertEquals(SpamKind.AppleCrash, BleSpam.classify(listOf(mfg(0x004C, "0F05C001601F1C" + "000010" + "AABBCC")))?.kind)
        // Those bytes inside the 7 declared ones are not the crash frame.
        assertEquals(SpamKind.AppleAction, BleSpam.classify(listOf(mfg(0x004C, "0F05C001000010")))?.kind)
        // A paired Fast Pair accessory sends an account-key filter, not a 3-byte model id: no card, no hit.
        assertNull(BleSpam.classify(listOf(AdStructure(0x16, hex("2CFE" + "0011223344556677")))))
        // An ordinary iBeacon and an empty advertisement are not pop-ups.
        assertNull(BleSpam.classify(listOf(mfg(0x004C, "0215" + "00".repeat(21)))))
        assertNull(BleSpam.classify(emptyList()))
    }

    @Test fun flipperByColourOrName() {
        assertEquals(FlipperColor.White to FlipperClue.Service, BleSpam.flipper(listOf(AdStructure(0x03, hex("8230"))), null))
        assertEquals(FlipperColor.Black to FlipperClue.Service, BleSpam.flipper(listOf(AdStructure(0x03, hex("8130"))), null))
        assertEquals(FlipperColor.Transparent to FlipperClue.Service, BleSpam.flipper(listOf(AdStructure(0x03, hex("8330"))), null))
        assertEquals(FlipperColor.Unknown to FlipperClue.Name, BleSpam.flipper(emptyList(), "Flipper Ufubih"))
        // Known only by its address block: the colour stays unknown.
        assertEquals(FlipperColor.Unknown to FlipperClue.Address, BleSpam.flipper(emptyList(), null, "80:E1:26:12:34:56"))
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

    @Test fun threeRealProductsOfThreeKindsAreNotAnAttack() {
        val w = BleSpamWatch()
        // AirPods, a headset in pairing mode and a Windows mouse in the same room, for a minute.
        val room = listOf(dev("AA:00:00:00:00:01", airpods, 0, rssi = -50), dev("BB:00:00:00:00:02", fastPair, 0, rssi = -60), dev("CC:00:00:00:00:03", swiftPair, 0, rssi = -70))
        for (t in 0..60) w.update(room.map { it.copy(lastSeen = t * 1000L) }, t * 1000L, t * 1000L)
        assertFalse(w.attacking)
        assertTrue(w.incidents.isEmpty())
    }

    @Test fun eightLongLivedAirPodsAreNotAnAttack() {
        val w = BleSpamWatch()
        // A crowded train: eight pairs, scattered levels, the same addresses all along.
        val train = (1..8).map { dev("AA:00:00:00:00:0$it", airpods, 0, rssi = -40 - 5 * it) }
        w.update(train, 500, 0)
        for (t in 11..70) w.update(train.map { it.copy(lastSeen = t * 1000L) }, t * 1000L, t * 1000L)
        assertFalse(w.attacking)
    }

    @Test fun oneAddressWalkingAListIsAnAttack() {
        val w = BleSpamWatch()
        // The same address asks for three different Apple actions in turn.
        for ((i, action) in listOf("01", "05", "09").withIndex()) {
            w.update(listOf(dev("DD:00:00:00:00:01", listOf(mfg(0x004C, "0F05C0${action}601F1C")), 20_000L + i * 1000)), 20_000L + i * 1000, 0)
        }
        assertTrue(w.attacking)
        assertTrue(SpamReason.Cycling in w.kinds[0].reasons)
        assertTrue(w.kinds[0].detail!!.contains("Watch Setup"))
    }

    @Test fun theCrashFrameIsAnAttackByItself() {
        val w = BleSpamWatch()
        w.update(listOf(dev("EE:00:00:00:00:01", listOf(mfg(0x004C, "0F05C001601F1C" + "000010" + "AABBCC")), 1_000)), 1_000, 0)
        assertTrue(w.attacking)
        assertEquals(setOf(SpamReason.Crafted), w.kinds[0].reasons)
    }

    @Test fun fourNewAddressesAtTheSameLevelAreOneRadio() {
        val w = BleSpamWatch()
        w.update((1..4).map { dev("F0:00:00:00:00:0$it", swiftPair, 30_000, rssi = -60 - it) }, 30_500, 0)
        assertTrue(SpamReason.OneRadio in w.kinds[0].reasons)
        // The same four, scattered: four objects, nothing to say.
        val v = BleSpamWatch()
        v.update((1..4).map { dev("F1:00:00:00:00:0$it", swiftPair, 30_000, rssi = -40 - 10 * it) }, 30_500, 0)
        assertFalse(v.attacking)
    }
}
