package com.allnetworktools

import android.net.wifi.ScanResult
import com.allnetworktools.data.Ad
import com.allnetworktools.data.EvilTwin
import com.allnetworktools.data.FollowLevel
import com.allnetworktools.data.FrOperator
import com.allnetworktools.data.Sighting
import com.allnetworktools.data.TowerQuery
import com.allnetworktools.data.TowerRepository
import com.allnetworktools.data.TrackerDetect
import com.allnetworktools.data.TrackerNet
import com.allnetworktools.data.TwinRisk
import com.allnetworktools.data.WifiAp
import com.allnetworktools.data.chooseOperator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "fr")
class NewToolsTest {
    private fun ap(ssid: String, bssid: String, rssi: Int, sec: String) = WifiAp(ssid, bssid, rssi, 5180, 80, sec, ScanResult.WIFI_STANDARD_11AX, 5210)

    // ---- Evil twin ----------------------------------------------------------------------------

    @Test fun openCopyOfProtectedNetworkIsHighRisk() {
        val r = EvilTwin.analyze(listOf(ap("Maison", "a4:3e:51:00:00:01", -60, "WPA2"), ap("Maison", "a4:3e:51:00:00:02", -65, "WPA2"), ap("Maison", "02:11:22:33:44:55", -40, "Ouvert"))).single()
        assertEquals(TwinRisk.High, r.risk)
        assertTrue("02:11:22:33:44:55" in r.flagged)
        assertFalse("a4:3e:51:00:00:01" in r.flagged)
    }

    @Test fun meshFromOneMakerIsNormal() {
        val r = EvilTwin.analyze(
            listOf(ap("Mesh", "a4:3e:51:00:00:01", -50, "WPA2/3"), ap("Mesh", "a6:3e:51:00:00:01", -52, "WPA2/3"), ap("Mesh", "a4:3e:51:10:00:07", -70, "WPA2/3")),
        ).single()
        assertEquals(TwinRisk.None, r.risk)
    }

    @Test fun strongerApFromAnotherMakerIsHighRisk() {
        val r = EvilTwin.analyze(listOf(ap("Bureau", "70:fc:8f:00:00:01", -70, "WPA2"), ap("Bureau", "70:fc:8f:00:00:02", -72, "WPA2"), ap("Bureau", "b8:27:eb:00:00:09", -45, "WPA2"))).single()
        assertEquals(TwinRisk.High, r.risk)
        assertEquals(setOf("b8:27:eb:00:00:09"), r.flagged)
    }

    @Test fun wpa3DowngradeIsSuspect() {
        val r = EvilTwin.analyze(listOf(ap("Box", "a4:3e:51:00:00:01", -60, "WPA3"), ap("Box", "a4:3e:51:00:00:02", -62, "WPA2"))).single()
        assertEquals(TwinRisk.Medium, r.risk)
    }

    @Test fun communityHotspotsAreOnlyNoted() {
        val r = EvilTwin.analyze(
            listOf(ap("FreeWifi_secure", "a4:3e:51:00:00:01", -60, "WPA2-EAP"), ap("FreeWifi_secure", "14:0c:76:00:00:01", -70, "WPA2-EAP"), ap("FreeWifi_secure", "68:a3:78:00:00:01", -80, "WPA2-EAP")),
        ).single()
        assertEquals(TwinRisk.Low, r.risk)
    }

    @Test fun apAppearingLaterIsNoted() {
        val t0 = 1_000_000L
        val list = listOf(ap("Box", "a4:3e:51:00:00:01", -60, "WPA2"), ap("Box", "a4:3e:51:00:00:02", -62, "WPA2"))
        val r = EvilTwin.analyze(list, mapOf("a4:3e:51:00:00:01" to t0, "a4:3e:51:00:00:02" to t0 + 120_000), mapOf("Box" to t0), t0 + 180_000).single()
        assertEquals(TwinRisk.Low, r.risk)
        assertEquals("Apparu pendant l'analyse", r.findings.single().title)
    }

    @Test fun fakeScenarioFlagsTheOpenFreeboxCopy() {
        val reports = EvilTwin.analyze(scan + twins)
        val free = reports.first { it.ssid == "Freebox-7A2C" }
        assertEquals(TwinRisk.High, free.risk)
        assertTrue("02:4a:91:c3:5e:10" in free.flagged)
        assertTrue("a4:3e:51:7c:2a:9f" !in free.flagged)
        assertEquals(TwinRisk.None, EvilTwin.analyze(scan).first { it.ssid == "Freebox-7A2C" }.risk)
    }

    // ---- Trackers -------------------------------------------------------------------------------

    @Test fun classifiesFindMySeparatedAndNearOwner() {
        val separated = TrackerDetect.classify(Ad.parseHex("1EFF4C00121910" + "5A".repeat(22) + "0201"))!!
        assertEquals(TrackerNet.AppleFindMy, separated.net)
        assertEquals(true, separated.separated)
        val near = TrackerDetect.classify(Ad.parseHex("07FF4C0012022400"))!!
        assertEquals(false, near.separated)
        // Status byte bits 4–5: 0x10 an AirTag, 0x20 another Find My accessory, 0x30 AirPods, 0x00 an Apple device.
        assertEquals(com.allnetworktools.data.TrackerKind.AirTag, separated.kind)
        assertEquals(com.allnetworktools.data.TrackerKind.AppleDevice, near.kind)
        assertEquals(com.allnetworktools.data.TrackerKind.AirPods, TrackerDetect.classify(Ad.parseHex("07FF4C0012023100"))!!.kind)
        assertEquals(com.allnetworktools.data.TrackerKind.AppleDevice, TrackerDetect.classify(Ad.parseHex("07FF4C0012020000"))!!.kind)
        assertEquals(com.allnetworktools.data.TrackerKind.SmartTag, TrackerDetect.classify(Ad.parseHex("07165AFD10223344"))!!.kind)
        assertEquals(com.allnetworktools.data.TrackerKind.GalaxyBuds, TrackerDetect.classify(Ad.parseHex("07165AFD10223344" + "110947616C6178792042756473322050726F"))!!.kind)
        assertEquals(com.allnetworktools.data.TrackerKind.GalaxyBuds, TrackerDetect.classify(Ad.parseHex("110947616C6178792042756473322050726F" + "05FF750001FF"))!!.kind)
        // Recent AirTags announce themselves as accessories: the name read once connected tells.
        assertEquals(com.allnetworktools.data.TrackerKind.AirTag, TrackerDetect.kindFromProbe("AirTag", false))
        assertEquals(com.allnetworktools.data.TrackerKind.AirTag, TrackerDetect.kindFromProbe(null, true))
        assertEquals(com.allnetworktools.data.TrackerKind.AirPods, TrackerDetect.kindFromProbe("left", false))
        assertEquals(null, TrackerDetect.kindFromProbe("Chipolo ONE Spot", false))
        // AirPods Pro: empty device name, model read from the Find My accessory information service.
        assertEquals(com.allnetworktools.data.TrackerKind.AirPods, TrackerDetect.kindFromProbe(null, false, null, "AirPods Pro"))
        // AirPods Pro: empty device name, model read from the Find My accessory information service.
        assertEquals(com.allnetworktools.data.TrackerKind.AirPods, TrackerDetect.kindFromProbe(null, false, null, "AirPods Pro"))
        // An iPhone's Nearby Info frame (0x10) is not a tracker.
        assertNull(TrackerDetect.classify(Ad.parseHex("0AFF4C0010050B1C8E3D21")))
    }

    @Test fun classifiesGoogleSamsungAndTile() {
        assertEquals(true, TrackerDetect.classify(Ad.parseHex("1916AAFE41" + "AB".repeat(20) + "00"))!!.separated)
        assertEquals(false, TrackerDetect.classify(Ad.parseHex("1916AAFE40" + "AB".repeat(20) + "00"))!!.separated)
        assertEquals(TrackerNet.SamsungFind, TrackerDetect.classify(Ad.parseHex("07165AFD10223344"))!!.net)
        assertEquals(TrackerNet.Tile, TrackerDetect.classify(Ad.parseHex("0303EDFE"))!!.net)
        // Eddystone-URL is a beacon, not a tracker.
        assertNull(TrackerDetect.classify(Ad.parseHex("0303AAFE0E16AAFE10E7036578616D706C6507")))
    }

    @Test fun separatedTagThatTravelsWithYouIsFollowing() {
        val sig = TrackerDetect.classify(Ad.parseHex("1EFF4C00121910" + "5A".repeat(22) + "0201"))!!
        val walk = (0..12).map { Sighting(it * 60_000L, 48.85 + it * 0.001, 2.35, -60) }
        val a = TrackerDetect.assess(sig, walk)
        assertEquals(FollowLevel.Following, a.level)
        assertTrue(a.spreadM!! > 1000)
        // Same tag, but staying in one place for 12 minutes (a neighbour's keys): only worth watching.
        val still = (0..12).map { Sighting(it * 60_000L, 48.85, 2.35, -60) }
        assertEquals(FollowLevel.Watch, TrackerDetect.assess(sig, still).level)
        // A tag announcing its owner is nearby.
        val near = TrackerDetect.classify(Ad.parseHex("07FF4C0012022400"))!!
        assertEquals(FollowLevel.WithOwner, TrackerDetect.assess(near, walk).level)
    }

    // ---- Operators and ANFR data -------------------------------------------------------------------

    @Test fun operatorFollowsTheSimThenTheNetwork() {
        assertEquals(FrOperator.Free, chooseOperator("20815", "20801")!!.operator)
        assertTrue(chooseOperator("20815", "20801")!!.fromSim)
        // MVNO SIM (Transatel 208 22) on Orange's network: Orange sites.
        assertEquals(FrOperator.Orange, chooseOperator("20822", "20801")!!.operator)
        assertEquals(FrOperator.Bouygues, chooseOperator(null, "20820")!!.operator)
        assertEquals(FrOperator.Sfr, chooseOperator("20810", null)!!.operator)
        assertNull(chooseOperator("23415", "23415"))
    }

    @Test fun parsesAnfrRecordsIntoSites() {
        val json = javaClass.classLoader!!.getResource("anfr_free_paris.json")!!.readText()
        val q = TowerQuery(FrOperator.Free, 48.8566, 2.3522, 600)
        val r = TowerRepository.parse(q, json, 0)
        assertEquals(56, r.emitterCount)
        assertEquals(11, r.sites.size)
        assertFalse(r.truncated)
        assertTrue(r.sites.zipWithNext().all { (a, b) -> a.distanceTo(q.lat, q.lon) <= b.distanceTo(q.lat, q.lon) })
        assertTrue(r.sites.all { it.distanceTo(q.lat, q.lon) <= 650 })
        val pompidou = r.sites.first { it.supportId == 579309L }
        assertTrue("CENTRE GEORGES POMPIDOU" in pompidou.address)
        assertEquals(53.0, pompidou.heightM!!, 0.0)
        assertTrue(pompidou.emitters.any { it.system == "5G NR 700" })
        assertEquals(listOf(70, 160), pompidou.emitters.first { it.system == "5G NR 700" }.azimuths)
        assertNotNull(r.dataUpdated)
    }

    @Test fun ignoresRecordsOfOtherOperators() {
        val json = javaClass.classLoader!!.getResource("anfr_free_paris.json")!!.readText()
        val r = TowerRepository.parse(TowerQuery(FrOperator.Orange, 48.8566, 2.3522, 600), json, 0)
        assertTrue(r.sites.isEmpty())
    }

    // ---- Galaxy SmartTag (reverse engineering of the SmartTag 2) --------------------------------

    /** Frames captured with nRF Connect: same epoch 0xC2 and identifier, three states. */
    private fun smartTagFrame(status: String) = Ad.parseHex("02010403025AFD17165AFD${status}C24A037F21348D05197CC6BE000000ED90DFA7")

    @Test fun readsSmartTagStates() {
        val lost = TrackerDetect.classify(smartTagFrame("11"))!!
        assertEquals(TrackerNet.SamsungFind, lost.net)
        assertEquals(true, lost.separated)
        assertEquals("Déconnecté du propriétaire depuis moins de 15 min", lost.state)
        assertEquals("C2:7F21348D05197CC6", lost.linkKey)
        assertEquals(true, TrackerDetect.classify(smartTagFrame("12"))!!.separated)
        val home = TrackerDetect.classify(smartTagFrame("15"))!!
        assertEquals(false, home.separated)
        assertEquals(FollowLevel.WithOwner, TrackerDetect.assess(home, listOf(Sighting(0, null, null, -50))).level)
    }

    @Test fun followsASmartTagAcrossAddressRotations() {
        val c = com.allnetworktools.ui.pages.bt.UnknownTrackersController(null, null)
        fun dev(addr: String, status: String) = com.allnetworktools.data.BleDevice.from(addr, null, -60, null, false, 0, smartTagFrame(status))
        val pos = com.allnetworktools.data.PositionSet()
        c.feed(listOf(dev("4E:D0:11:22:33:44", "11")), pos, 0)
        // A state change forces a new private address, within the same epoch.
        c.feed(listOf(dev("79:39:55:66:77:88", "12")), pos, 60_000)
        assertEquals(1, c.candidates.size)
        val t = c.candidates.values.single()
        assertEquals(2, t.addresses.size)
        assertEquals("Déconnecté du propriétaire depuis 15 min ou plus", t.signal.state)
    }

    @Test fun areaQueriesUseTheVisibleBox() {
        val q = TowerQuery.area(FrOperator.Free, 48.85, 2.34, 48.86, 2.36, 48.855, 2.35)
        assertEquals(listOf(48.85, 2.34, 48.86, 2.36), q.box!!.toList())
        assertEquals(q, TowerQuery.area(FrOperator.Free, 48.85, 2.34, 48.86, 2.36, 48.855, 2.35))
    }

    // ---- Wi-Fi security --------------------------------------------------------------------------

    private class FakeProbes(
        val http: Triple<Int, Int, String?>? = Triple(204, 0, null),
        val answers: Map<String, List<String>> = mapOf("one.one.one.one" to listOf("1.1.1.1", "1.0.0.1"), "dns.google" to listOf("8.8.8.8")),
        val hijackNx: String? = null,
        val intercept: Boolean = false,
        val hop: String? = "192.168.1.254",
    ) : com.allnetworktools.data.NetProbes {
        override suspend fun connectivityCheck() = http
        override suspend fun systemResolve(host: String) = answers[host] ?: hijackNx?.let { listOf(it) } ?: emptyList()
        override suspend fun udpQuery(server: String, host: String) =
            if (server == "192.0.2.53") (if (intercept) listOf("1.1.1.1") else null) else answers[host]
        override suspend fun firstHop() = hop
        override suspend fun tcpOpen(host: String, port: Int) = port == 80
    }

    private val cleanLink = com.allnetworktools.data.LinkSnapshot(true, "192.168.1.254", emptyList(), listOf("192.168.1.254"), "192.168.1.254", false, null, true, false)

    @Test fun auditsTheConnectedNetwork() {
        val conn = com.allnetworktools.data.WifiConnection("Box", "a4:3e:51:00:00:01", -50, 2437, null, null, 4, "WPA2-Personnel (PSK)", "192.168.1.2", 24, "192.168.1.254", emptyList(), null, null)
        val c = com.allnetworktools.data.NetworkAudit.checks(conn, "[WPA2-PSK-TKIP+CCMP][RSN-PSK-CCMP][WPS][ESS]", cleanLink, setOf(80))
        val titles = c.map { it.title }
        assertTrue("WPS activé" in titles)
        assertTrue("Chiffrement TKIP" in titles)
        assertTrue("Trames de gestion non protégées" in titles)
        assertTrue("DNS en clair" in titles)
        assertTrue("Administration de la box en HTTP" in titles)
        val s = com.allnetworktools.data.NetworkAudit.score(c)
        assertTrue(s < 40)
        val wpa3 = conn.copy(security = "WPA3-Personnel (SAE)")
        val good = com.allnetworktools.data.NetworkAudit.checks(wpa3, "[RSN-SAE-CCMP][MFPR][ESS]", cleanLink.copy(privateDns = true), setOf(443))
        assertEquals(100, com.allnetworktools.data.NetworkAudit.score(good))
    }

    @Test fun flagsAnIntermediaryOnThePath() {
        val ok = com.allnetworktools.data.GatewaySnapshot(0, "192.168.1.254", "192.168.1.254", listOf("192.168.1.254"), listOf("fe80::1"), "192.168.1.254", "b")
        assertTrue(com.allnetworktools.data.MitmWatch.checks(ok).none { it.level == com.allnetworktools.data.CheckLevel.Bad })
        val spoofed = ok.copy(timeMs = 15_000, firstHop = "192.168.1.37", ipv6Routers = listOf("fe80::1", "fe80::bad"))
        assertTrue(com.allnetworktools.data.MitmWatch.checks(spoofed).any { it.title == "Un appareil s'intercale" })
        val ev = com.allnetworktools.data.MitmWatch.changes(ok, spoofed)
        assertEquals(2, ev.size)
        // Roaming to another access point is not reported as an attack.
        assertTrue(com.allnetworktools.data.MitmWatch.changes(ok, spoofed.copy(bssid = "other")).isEmpty())
    }
}
