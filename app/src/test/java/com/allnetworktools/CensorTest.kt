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
