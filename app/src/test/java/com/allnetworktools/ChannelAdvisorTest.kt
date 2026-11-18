package com.allnetworktools

import com.allnetworktools.data.ChannelAdvisor
import com.allnetworktools.data.ChannelLoad
import com.allnetworktools.data.WifiAp
import com.allnetworktools.data.WifiBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelAdvisorTest {
    private fun ap24(ssid: String, ch: Int, rssi: Int, bssid: String = "00:11:22:33:44:%02x".format(ch + rssi.and(0xF))) =
        WifiAp(ssid, bssid, rssi, 2407 + 5 * ch, 20, "WPA2", 4, 2407 + 5 * ch)

    private fun ap5(ssid: String, ch: Int, width: Int, centreCh: Int, rssi: Int, bssid: String) =
        WifiAp(ssid, bssid, rssi, 5000 + 5 * ch, width, "WPA2", 6, 5000 + 5 * centreCh)

    @Test fun crowdedSixSendsToEleven() {
        val own = ap24("Maison", 6, -45, "aa:aa:aa:aa:aa:01")
        val scan = listOf(own, ap24("Voisin1", 6, -55), ap24("Voisin2", 6, -60), ap24("Voisin3", 1, -70), ap24("Voisin4", 3, -80))
        val a = ChannelAdvisor.advise(scan, WifiBand.B24, "Maison", own.bssid)
        assertEquals(11, a.best!!.channel)
        assertEquals(6, a.current!!.channel)
        assertTrue(a.worthMoving)
        assertEquals(ChannelLoad.Free, a.best!!.load)
    }

    @Test fun theOwnNetworkDoesNotCountAgainstItself() {
        val own = ap24("Maison", 1, -35, "aa:aa:aa:aa:aa:01")
        val mesh = ap24("Maison", 1, -50, "aa:aa:aa:aa:aa:02")
        val a = ChannelAdvisor.advise(listOf(own, mesh, ap24("Voisin", 11, -60)), WifiBand.B24, "Maison", own.bssid)
        assertEquals(ChannelLoad.Free, a.current!!.load)
        assertFalse(a.worthMoving)
    }

    @Test fun partialOverlapIsWorseThanSharing() {
        // Same strength: one network on 6 (shared politely), one on 8 (partly over 6 and 11).
        val onSix = ChannelAdvisor.advise(listOf(ap24("A", 6, -60)), WifiBand.B24, null, null).ranked.first { it.channel == 6 }
        val onEight = ChannelAdvisor.advise(listOf(ap24("A", 8, -60)), WifiBand.B24, null, null).ranked.first { it.channel == 6 }
        assertTrue(onEight.loadDbm > onSix.loadDbm - 3)
    }

    @Test fun fiveGhzFollowsTheOwnWidthAndPrefersNonDfsOnATie() {
        val own = ap5("Maison", 36, 80, 42, -50, "aa:aa:aa:aa:aa:01")
        val a = ChannelAdvisor.advise(listOf(own, ap5("Voisin", 40, 80, 42, -55, "bb:bb:bb:bb:bb:01")), WifiBand.B5, "Maison", own.bssid)
        assertEquals(80, a.best!!.widthMhz)
        assertTrue(a.best!!.channel != 36)
        // All other blocks are empty: the non-DFS one (149) comes first.
        assertEquals(149, a.best!!.channel)
        assertTrue(a.worthMoving)
    }

    @Test fun theOwnNetworkIsFoundByNameWhenItsAccessPointIsNotInTheScan() {
        val a = ChannelAdvisor.advise(listOf(ap24("Maison", 6, -50, "aa:aa:aa:aa:aa:09"), ap24("Voisin", 6, -55)), WifiBand.B24, "\"Maison\"", "ff:ff:ff:ff:ff:ff")
        assertEquals(6, a.current!!.channel)
    }
}
