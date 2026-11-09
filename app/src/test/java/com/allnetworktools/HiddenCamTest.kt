package com.allnetworktools

import com.allnetworktools.data.CamReason
import com.allnetworktools.data.CamSource
import com.allnetworktools.data.HiddenCam
import com.allnetworktools.data.net.LanDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Camera clues: maker, name, chip, signal, and the video ports of the local network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "fr")
class HiddenCamTest {
    @Test
    fun makersAndNames() {
        val hik = HiddenCam.assess(CamSource.Wifi, "44:19:b6:00:00:01", "", "Hangzhou Hikvision Digital Technology Co.,Ltd.", -70)!!
        assertTrue(hik.likely)
        assertEquals(setOf(CamReason.Maker), hik.reasons)
        // The network a cheap "mini" camera opens to be set up, heard from the next room.
        val mini = HiddenCam.assess(CamSource.Wifi, "a0:00:00:00:00:02", "HDWiFiCam-8F3A", null, -48)!!
        assertTrue(mini.likely)
        assertEquals(setOf(CamReason.Name, CamReason.Near), mini.reasons)
        listOf("V380-7712", "IPC-04A1", "Tapo_C200_1A2B", "ESP32-CAM", "MV1234567", "yi-cam", "Ma caméra").forEach { assertTrue(it, HiddenCam.isCameraName(it)) }
        listOf("Freebox-10EB54", "Livebox-7A2C", "SFR_7111", "egym-guest", "DAVID-DELL", "Pixel 9").forEach { assertFalse(it, HiddenCam.isCameraName(it)) }
        assertNull(HiddenCam.assess(CamSource.Wifi, "e4:9e:12:12:eb:55", "Freebox-10EB54", "Freebox Sas", -40))
    }

    @Test
    fun chipsAreOnlyPossible() {
        val esp = HiddenCam.assess(CamSource.Wifi, "24:0a:c4:00:00:03", "ESP_1A2B3C", "Espressif Inc.", -80)!!
        assertFalse(esp.likely)
        assertEquals(setOf(CamReason.Chip), esp.reasons)
        val tuya = HiddenCam.assess(CamSource.Ble, "dc:23:50:75:9c:27", "TY", null, -60)!!
        assertFalse(tuya.likely)
    }

    @Test
    fun localNetwork() {
        assertTrue(HiddenCam.assessLan(LanDevice("192.168.1.20"), setOf(80, 554))!!.likely)
        assertEquals(listOf(554), HiddenCam.assessLan(LanDevice("192.168.1.20"), setOf(80, 554))!!.ports)
        // ONVIF alone: a NAS or a router can answer there too.
        assertFalse(HiddenCam.assessLan(LanDevice("192.168.1.21"), setOf(8899))!!.likely)
        assertTrue(HiddenCam.assessLan(LanDevice("192.168.1.22", hostname = "ipc-salon.lan"), setOf(80))!!.likely)
        assertNull(HiddenCam.assessLan(LanDevice("192.168.1.23", hostname = "nas.lan"), setOf(80, 445)))
        assertNull(HiddenCam.assessLan(LanDevice("192.168.1.2", isSelf = true), setOf(554)))
    }

    @Test
    fun likelyAndNearFirst() {
        val a = HiddenCam.assess(CamSource.Wifi, "1", "ESP_1", "Espressif Inc.", -40)!!
        val b = HiddenCam.assess(CamSource.Wifi, "2", "IPC-1", null, -80)!!
        val c = HiddenCam.assess(CamSource.Wifi, "3", "IPC-2", null, -50)!!
        assertEquals(listOf(c, b, a), HiddenCam.sort(listOf(a, b, c)))
    }
}
