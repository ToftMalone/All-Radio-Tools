package com.allnetworktools

import com.allnetworktools.data.net.Censor
import com.allnetworktools.data.net.CensorLists
import com.allnetworktools.data.net.Endpoint
import com.allnetworktools.data.net.Verdict
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CensorTest {
    @Test fun privateAndLoopbackAnswersAreBogons() {
        for (a in listOf("127.0.0.1", "0.0.0.0", "10.1.2.3", "192.168.1.1", "172.20.0.1", "169.254.1.1", "100.64.0.1", "::1", "fe80::1")) {
            assertTrue(a, Censor.bogon(InetAddress.getByName(a)))
        }
        for (a in listOf("1.1.1.1", "208.80.154.224", "172.32.0.1", "2606:4700::1111")) assertFalse(a, Censor.bogon(InetAddress.getByName(a)))
    }

    @Test fun readsOoniTestLists() {
        val json = """{"metadata":{"count":3},"results":[{"category_code":"NEWS","country_code":"FR","url":"https:\/\/www.lemonde.fr\/"},""" +
            """{"category_code":"GRP","country_code":"XX","url":"https://twitter.com/"},{"category_code":"X","url":"ftp://nope"}]}"""
        val l = Censor.parseOoni(json)
        assertEquals(listOf("https://www.lemonde.fr/" to "NEWS", "https://twitter.com/" to "GRP"), l)
        assertTrue(Censor.parseOoni("{}").isEmpty())
    }

    @Test fun listsAreCleanHostNames() {
        val all = CensorLists.sites.values.flatten()
        assertEquals("no duplicates", all.size, all.toSet().size)
        for (h in all) {
            assertTrue(h, h == h.lowercase() && '.' in h && '/' !in h && ':' !in h && ' ' !in h)
        }
        for (a in CensorLists.messaging + CensorLists.circumvention) {
            assertTrue(a.name, a.endpoints.isNotEmpty() || a.sites.isNotEmpty())
            for (e in a.endpoints) assertTrue("${a.name} ${e.host}", e.port in 1..65535 && e.host.isNotBlank())
        }
        // The encrypted-DNS check must find its resolver.
        assertTrue(CensorLists.circumvention.first { it.name == "DNS over HTTPS" }.endpoints.all { it.how == Endpoint.How.Doh })
    }

    @Test fun verdictsAreSortedIntoBlockedAndAnomalies() {
        assertTrue(Verdict.SniBlocked.blocked && Verdict.DnsTampered.blocked && Verdict.Intercepted.blocked)
        assertFalse(Verdict.Ok.blocked || Verdict.Down.blocked || Verdict.Inconclusive.blocked)
        assertTrue(Verdict.Partial.anomaly && Verdict.ProxyDetected.anomaly)
    }
}

class WifiCallingTest {
    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)

    @Test fun epdgNameFollows3gpp() {
        assertEquals("epdg.epc.mnc001.mcc208.pub.3gppnetwork.org", Censor.epdgName("208", "01"))
        assertEquals("epdg.epc.mnc410.mcc310.pub.3gppnetwork.org", Censor.epdgName("310", "410"))
    }

    @Test fun ikeSaInitIsWellFormed() {
        val spi = ByteArray(8) { (it + 1).toByte() }
        val m = Censor.ikeSaInit(spi, java.util.Random(1))
        // Header: our SPI, no responder SPI, SA first, IKEv2, IKE_SA_INIT, initiator, message 0, the whole length.
        assertTrue((0 until 8).all { m[it] == spi[it] } && (8 until 16).all { m[it].toInt() == 0 })
        assertEquals(33, m[16].toInt()); assertEquals(0x20, m[17].toInt()); assertEquals(34, m[18].toInt()); assertEquals(0x08, m[19].toInt())
        assertEquals(m.size, ((u16(m, 24) shl 16) or u16(m, 26)))
        // The payloads chain SA → KE → Nonce → end, their lengths adding up.
        var at = 28
        var next = m[16].toInt()
        val seen = ArrayList<Int>()
        while (next != 0) {
            seen += next
            val len = u16(m, at + 2)
            if (next == 33) {
                assertEquals(4, m[at + 4 + 7].toInt()) // four transforms
                assertEquals(len - 4, u16(m, at + 6)) // the proposal fills the SA
            }
            if (next == 34) { assertEquals(14, u16(m, at + 4)); assertEquals(8 + 256, len) }
            next = m[at].toInt()
            at += len
        }
        assertEquals(listOf(33, 34, 40), seen)
        assertEquals(m.size, at)
    }

    @Test fun recognisesTheGatewaysAnswer() {
        val spi = ByteArray(8) { 7 }
        val reply = ByteArray(40).also { r ->
            for (i in 0 until 8) r[i] = 7
            r[16] = 33; r[17] = 0x20; r[18] = 34; r[19] = 0x20 // response
        }
        assertTrue(Censor.isIkeReply(reply, reply.size, spi, natT = false))
        assertFalse(Censor.isIkeReply(reply, reply.size, ByteArray(8) { 8 }, natT = false))
        assertFalse(Censor.isIkeReply(reply, 20, spi, natT = false))
        // On port 4500 behind the four-zero-byte marker.
        val natT = ByteArray(4) + reply
        assertTrue(Censor.isIkeReply(natT, natT.size, spi, natT = true))
        assertFalse(Censor.isIkeReply(reply, reply.size, spi, natT = true))
    }
}

class VolteTest {
    private fun sim(allowed: Boolean? = true, switch: Boolean? = null, rat: Int?) =
        com.allnetworktools.data.net.SimVoice("Orange F", "208", "01", volteAllowed = allowed, volteSwitch = switch, voiceRat = rat)

    @Test fun voiceOnLteOr5gIsVolte() {
        assertEquals(Verdict.Ok, Censor.volte(sim(rat = 13)).verdict)
        assertEquals(Verdict.Ok, Censor.volte(sim(rat = 20)).verdict)
        assertEquals(Verdict.Ok, Censor.volte(sim(allowed = null, rat = 13)).verdict)
    }

    @Test fun operatorOffWins() {
        val r = Censor.volte(sim(allowed = false, rat = 13))
        assertEquals(Verdict.CarrierOff, r.verdict)
        assertTrue(r.verdict.blocked)
        assertEquals("not offered for this phone", r.steps.first().note)
    }

    @Test fun twoGOrThreeGIsAFallback() {
        val r = Censor.volte(sim(switch = false, rat = 3))
        assertEquals(Verdict.Fallback, r.verdict)
        assertTrue(r.verdict.anomaly)
        assertEquals(listOf("allowed", "off", "UMTS"), r.steps.map { it.note })
        assertEquals(Verdict.Fallback, Censor.volte(sim(rat = 16)).verdict)
    }

    @Test fun noServiceOrUnknownCannotConclude() {
        assertEquals(Verdict.Inconclusive, Censor.volte(sim(rat = 0)).verdict)
        assertEquals(Verdict.Inconclusive, Censor.volte(sim(allowed = null, rat = null)).verdict)
        assertEquals(Verdict.Ok, Censor.volte(sim(rat = Censor.RAT_IWLAN)).verdict)
        assertEquals(Verdict.Inconclusive, Censor.volte(sim(allowed = null, rat = Censor.RAT_IWLAN)).verdict)
    }
}
