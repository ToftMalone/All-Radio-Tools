package com.allnetworktools

import androidx.test.core.app.ApplicationProvider
import com.allnetworktools.data.Exclusions
import com.allnetworktools.data.Guard
import com.allnetworktools.data.NetSighting
import com.allnetworktools.data.NetType
import com.allnetworktools.data.OuiTable
import com.allnetworktools.data.Wardrive
import com.allnetworktools.data.WardriveDb
import com.allnetworktools.data.WigleClient
import com.allnetworktools.data.WigleCsv
import java.io.ByteArrayOutputStream
import java.io.StringWriter
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Wardriving: what is left out, makers, the database, and the file WiGLE takes in. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "fr")
class WardriveTest {
    private fun s(mac: String, level: Int, lat: Double, type: NetType = NetType.Wifi, name: String = "Box", t: Long = 1_700_000_000_000L) =
        NetSighting(mac, name, if (type == NetType.Wifi) 2437 else 0, "[WPA2-PSK-CCMP][ESS]", type, level, lat, 2.35, 35.0, 4f, t)

    @Test
    fun exclusions() {
        val ex = Exclusions(listOf("aa-bb-cc", "11:22:33:44:55:66", "nonsense", "AABB"))
        assertTrue(ex.excludes("AA:BB:CC:01:02:03"))
        assertTrue(ex.excludes("11:22:33:44:55:66"))
        assertFalse(ex.excludes("11:22:33:44:55:67"))
        assertFalse(ex.excludes("AA:BB:CD:01:02:03"))
        assertEquals("AA:BB:CC", Exclusions.pretty("aabbcc"))
        assertEquals("11:22:33:44:55:66", Exclusions.pretty("1122.3344.5566"))
        assertNull(Exclusions.pretty("AA:BB:CC:DD"))
        assertNull(Exclusions.pretty("GG:BB:CC"))
    }

    @Test
    fun makers() {
        val gz = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write("0007CB\tFREEBOX SAS\nE49E12\tShenzhen SDMC Technology CO.,Ltd.\n".toByteArray()) } }.toByteArray()
        val oui = OuiTable { gz.inputStream() }
        assertEquals("Freebox Sas", oui.vendor("00:07:cb:12:34:56"))
        assertEquals("Shenzhen SDMC Technology CO.,Ltd.", oui.vendor("e4:9e:12:01:02:03"))
        // A locally administered (random) address has no maker.
        assertNull(oui.vendor("02:07:CB:12:34:56"))
        assertNull(oui.vendor("00:00:01:00:00:00"))
    }

    @Test
    fun capabilitiesAsWigleShowsThem() {
        assertEquals("[WPA2][RSN][ESS][MFPC]", Wardrive.shortCaps("[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS][MFPC]"))
        assertEquals(Guard.Wpa3, Wardrive.guardOf("[RSN-SAE-CCMP][ESS]"))
        assertEquals(Guard.Wpa, Wardrive.guardOf("[WPA2-PSK-CCMP][ESS]"))
        assertEquals(Guard.Wep, Wardrive.guardOf("[WEP][ESS]"))
        assertEquals(Guard.Open, Wardrive.guardOf("[ESS]"))
    }

    @Test
    fun csvRows() {
        val row = WigleCsv.row(s("e4:9e:12:12:eb:55", -66, 48.8566, name = "Chez \"moi\", 2e"))
        assertEquals(
            "e4:9e:12:12:eb:55,\"Chez \"\"moi\"\", 2e\",[WPA2-PSK-CCMP][ESS],2023-11-14 22:13:20,6,2437,-66,48.85660000,2.35000000,35.0,4.0,,,WIFI\n",
            row,
        )
        val ble = WigleCsv.row(s("dc:23:50:75:9c:27", -48, 48.8566, NetType.Ble, "TY"))
        assertTrue(ble, ble.startsWith("dc:23:50:75:9c:27,TY,Misc [LE],2023-11-14 22:13:20,0,7936,-48,"))
        assertTrue(ble.endsWith(",,,BLE\n"))
        val head = WigleCsv.header("1.6.3").lines()
        assertTrue(head[0].startsWith("WigleWifi-1.6,appRelease=1.6.3,"))
        assertEquals("MAC,SSID,AuthMode,FirstSeen,Channel,Frequency,RSSI,CurrentLatitude,CurrentLongitude,AltitudeMeters,AccuracyMeters,RCOIs,MfgrId,Type", head[1])
    }

    @Test
    fun database() {
        val db = WardriveDb(ApplicationProvider.getApplicationContext(), null)
        val first = db.write(listOf(s("aa:bb:cc:00:00:01", -70, 48.0), s("aa:bb:cc:00:00:02", -80, 48.0), s("dc:23:50:75:9c:27", -50, 48.0, NetType.Ble, "TY")))
        assertEquals(2, first[NetType.Wifi])
        assertEquals(1, first[NetType.Ble])
        // Met again: no new network, one more sighting; a hidden name does not wipe the known one.
        val again = db.write(listOf(s("aa:bb:cc:00:00:01", -60, 48.001, name = "")))
        assertTrue(again.isEmpty())
        assertEquals(3L, db.counts().total)
        assertEquals(4L, db.sightingsAfter(0))
        val out = StringWriter()
        val last = db.exportCsv(0, out)
        val lines = out.toString().lines().filter { it.isNotEmpty() }
        assertEquals(4, lines.size)
        assertTrue(lines.last(), lines.last().startsWith("aa:bb:cc:00:00:01,Box,"))
        assertEquals(db.lastSightingId(), last)
        // Nothing after the last one sent.
        assertNull(WigleClient.build(db, last, "test"))
        val file = WigleClient.build(db, 2, "test")!!
        val text = GZIPInputStream(file.first.inputStream()).bufferedReader().readText()
        assertEquals(4, text.lines().count { it.isNotEmpty() })
        assertEquals(last, file.second)
        // Excluding a maker removes its networks and their sightings.
        assertEquals(2, db.purge(Exclusions(listOf("AA:BB:CC"))))
        assertEquals(1L, db.counts().total)
        assertEquals(1L, db.sightingsAfter(0))
        db.clear()
        assertEquals(0L, db.counts().total)
    }
}
