package com.allnetworktools

import com.allnetworktools.data.BleDevice
import com.allnetworktools.data.BleKind
import com.allnetworktools.data.Glasses
import com.allnetworktools.data.GlassesConfidence
import com.allnetworktools.data.GlassesWatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesTest {
    /** A device whose advertisement is [raw] (AD structures in hex). */
    private fun dev(raw: String, name: String? = null, rssi: Int = -60, address: String = "C4:7C:8D:1E:2B:3F") =
        BleDevice(address, name, rssi, null, BleKind.Unknown, null, true, 0L, raw = raw)

    /** Flags, then manufacturer data with [company] (little-endian) and a few bytes. */
    private fun manuf(company: Int) = "020106" + "07FF%02X%02X0102030405".format(company and 0xFF, company shr 8)

    @Test fun rayBanMetaByLuxottica() {
        val h = Glasses.match(dev(manuf(0x0D53)))
        assertNotNull(h)
        assertEquals(GlassesConfidence.High, h!!.confidence)
        assertEquals(true, h.camera)
        assertTrue(h.product.contains("Ray-Ban"))
    }

    @Test fun snapSpectaclesByCompanyOrService() {
        assertEquals("Snap Spectacles", Glasses.match(dev(manuf(0x03C2)))!!.product)
        // 16-bit service list with 0xFE45.
        assertEquals("Snap Spectacles", Glasses.match(dev("020106" + "030345FE"))!!.product)
    }

    @Test fun metaAloneIsLikelyAndAQuestNameSaysHeadset() {
        val m = Glasses.match(dev(manuf(0x058E)))!!
        assertEquals(GlassesConfidence.Medium, m.confidence)
        val quest = Glasses.match(dev(manuf(0x01AB), name = "Quest 3"))!!
        assertEquals(GlassesConfidence.Low, quest.confidence)
        assertTrue(quest.product.contains("Quest"))
        // Meta identifier and a Ray-Ban name: two signs, sure.
        val both = Glasses.match(dev(manuf(0x01AB), name = "Ray-Ban Meta 1A2B"))!!
        assertEquals(GlassesConfidence.High, both.confidence)
        assertEquals(2, both.evidence.size)
    }

    @Test fun heyCyanByItsService() {
        // 7905FFF0-B5CE-4E99-A40F-4B1E122D00D0, least significant byte first.
        val uuid = "D0002D121E4B0FA4994ECEB5F0FF0579"
        val h = Glasses.match(dev("020106" + "1107" + uuid))
        assertNotNull(h)
        assertEquals(GlassesConfidence.High, h!!.confidence)
        assertTrue(Glasses.match(dev("020106", name = "HeyCyan-0A1B")) != null)
    }

    @Test fun displayOnlyGlassesHaveNoCamera() {
        val h = Glasses.match(dev(manuf(0x10F9)))!!
        assertEquals(false, h.camera)
        assertEquals(GlassesConfidence.Low, h.confidence)
    }

    @Test fun ordinaryDevicesAreIgnored() {
        // Apple, Samsung, a Jieli chip (earbuds), and the identifiers other detectors got wrong: Nanjing Qinheng, hearX, Google.
        for (id in listOf(0x004C, 0x0075, 0x05D6, 0x07D7, 0x0BA7, 0x00E0)) assertNull("0x%04X".format(id), Glasses.match(dev(manuf(id))))
        assertNull(Glasses.match(dev("020106", name = "JBL Flip 6")))
    }

    @Test fun watchCountsOnlyCloseCameraGlasses() {
        val w = GlassesWatch()
        w.update(listOf(dev(manuf(0x0D53), rssi = -60, address = "A"), dev(manuf(0x0D53), rssi = -95, address = "B"), dev(manuf(0x10F9), rssi = -50, address = "C")), 1_000)
        assertEquals(3, w.seen.size)
        assertEquals(listOf("A"), w.nearby(2_000).map { it.hit.address })
        w.threshold = -100
        assertEquals(setOf("A", "B"), w.nearby(2_000).map { it.hit.address }.toSet())
        // Half a minute later, no longer "nearby", still listed.
        assertEquals(0, w.nearby(40_000).size)
        assertEquals(3, w.seen.size)
    }
}
