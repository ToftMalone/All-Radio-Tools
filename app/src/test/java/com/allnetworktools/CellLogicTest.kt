package com.allnetworktools

import com.allnetworktools.data.CellMeasure
import com.allnetworktools.data.RadioTech
import com.allnetworktools.ui.pages.bt.heatOf
import com.allnetworktools.ui.pages.bt.parsePayload
import com.allnetworktools.ui.pages.bt.rssiToMeters
import com.allnetworktools.ui.pages.bt.Heat
import com.allnetworktools.data.GattNames
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "fr")
class CellLogicTest {
    @Test fun nrRssiIsEstimatedFromRsrpAndRsrq() {
        // RSSI = RSRP + 10·log10(20) − RSRQ
        assertEquals(-68, com.allnetworktools.data.nrRssi(-92, -11))
        assertNull(com.allnetworktools.data.nrRssi(-92, null))
        assertNull(com.allnetworktools.data.nrRssi(null, -11))
    }

    @Test fun decodesGattValues() {
        assertEquals("80 %", GattNames.decode(GattNames.uuid(0x2A19), byteArrayOf(80)))
        assertEquals("72 bpm", GattNames.decode(GattNames.uuid(0x2A37), byteArrayOf(0, 72)))
        assertEquals("« Pixel »", GattNames.decode(GattNames.uuid(0x2A29), "Pixel".toByteArray()))
        assertArrayEquals(byteArrayOf(1, 0xA0.toByte()), parsePayload("01 a0", hex = true))
        assertNull(parsePayload("0x1", hex = true))
    }

    @Test fun proximityModel() {
        assertEquals(1f, rssiToMeters(-59f), 0.01f)
        assertEquals(Heat.Found, heatOf(-45f))
        assertEquals(Heat.Cold, heatOf(-80f))
    }

    @Test fun subSatellitePoint() {
        // Straight overhead: the satellite is above us.
        val (lat, lon) = com.allnetworktools.data.SatGeo.subPoint(48.85, 2.35, 0.0, 90.0, 20_180.0)
        assertEquals(48.85, lat, 0.01)
        assertEquals(2.35, lon, 0.01)
        // On the horizon due south a GEO satellite sits ~81° of arc away (on the equator or below it).
        val (lat2, _) = com.allnetworktools.data.SatGeo.subPoint(0.0, 0.0, 180.0, 0.0, 35_786.0)
        assertEquals(-81.3, lat2, 0.3)
    }

    @Test fun countryLookup() {
        val world = com.allnetworktools.data.WorldMap.parse(
            ApplicationProvider.getApplicationContext<android.content.Context>().assets.open("world/countries.txt").bufferedReader().readText(),
        )
        assertEquals("France", com.allnetworktools.data.WorldMap.countryAt(world, 46.5, 2.5))
        assertEquals("Brésil", com.allnetworktools.data.WorldMap.countryAt(world, -10.0, -52.0))
        assertNull(com.allnetworktools.data.WorldMap.countryAt(world, 0.0, -30.0))
        assertEquals("Océan Atlantique", com.allnetworktools.data.WorldMap.oceanAt(0.0, -30.0))
        assertEquals("La Manche", com.allnetworktools.data.WorldMap.oceanAt(51.0, 1.0))
        assertEquals("Mer du Nord", com.allnetworktools.data.WorldMap.oceanAt(55.0, 3.0))
        assertEquals("Mer Méditerranée", com.allnetworktools.data.WorldMap.oceanAt(38.0, 5.0))
        assertEquals("Golfe de Gascogne", com.allnetworktools.data.WorldMap.oceanAt(45.5, -4.0))
    }

    @Test fun bleFilters() {
        fun dev(name: String?, rssi: Int, hex: String) =
            com.allnetworktools.data.BleDevice.from("AA:BB:CC:DD:EE:0${rssi and 7}", name, rssi, null, true, 0, com.allnetworktools.data.Ad.parseHex(hex))
        val apple = dev(null, -60, "02011A0AFF4C0010050B1C8E3D21")
        val pixel = dev("Pixel Buds", -75, "03032CFE")
        val mesh = dev("Node", -90, "0303281802010 6".replace(" ", ""))
        val f = com.allnetworktools.ui.pages.bt.BleFilterState()
        assertTrue(listOf(apple, pixel, mesh).all(f::matches))
        assertEquals(setOf(com.allnetworktools.data.BleVendor.Mesh), mesh.vendors)
        f.raw = "0xff 4c00"
        assertEquals(listOf(apple), listOf(apple, pixel, mesh).filter(f::matches))
        f.raw = ""; f.nameMode = com.allnetworktools.ui.pages.bt.NameMode.Named; f.minRssi = -80
        assertEquals(listOf(pixel), listOf(apple, pixel, mesh).filter(f::matches))
        f.nameMode = com.allnetworktools.ui.pages.bt.NameMode.All; f.minRssi = com.allnetworktools.ui.pages.bt.RssiOff
        f.toggleExclude(com.allnetworktools.data.BleVendor.Google)
        assertEquals(listOf(apple, mesh), listOf(apple, pixel, mesh).filter(f::matches))
        f.toggleInclude(com.allnetworktools.data.BleVendor.Google)
        assertTrue(f.exclude.isEmpty())
        assertEquals(listOf(pixel), listOf(apple, pixel, mesh).filter(f::matches))
        f.include = emptySet()
        f.text = "apple"
        assertEquals(listOf(apple), listOf(apple, pixel, mesh).filter(f::matches))
    }
}
