package com.allnetworktools

import com.allnetworktools.data.net.DnsClient
import com.allnetworktools.data.net.DnsType
import com.allnetworktools.data.net.WakeOnLan
import com.allnetworktools.data.net.subnetHosts
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Parser checks against real responses captured from Cloudflare's resolver. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "fr")
class NetLogicTest {
    private fun b64(s: String) = Base64.getDecoder().decode(s)

    @Test
    fun parsesARecords() {
        val a = DnsClient.parse(b64("EjSBgAABAAYAAAAAB2FuZHJvaWQDY29tAAABAAHADAABAAEAAADkAATAstppwAwAAQABAAAA5AAEwLLaasAMAAEAAQAAAOQABMCy2pPADAABAAEAAADkAATAstpnwAwAAQABAAAA5AAEwLLaY8AMAAEAAQAAAOQABMCy2mg="), 12f)
        assertEquals(0, a.rcode)
        assertEquals(6, a.records.size)
        assertEquals("A", a.records[0].type)
        assertEquals("192.178.218.105", a.records[0].value)
        assertEquals(228L, a.records[0].ttl)
    }

    @Test
    fun parsesMxWithCompressedName() {
        val a = DnsClient.parse(b64("EjSBgAABAAEAAAAABmdvb2dsZQNjb20AAA8AAcAMAA8AAQAAAM0ACQAKBHNtdHDADA=="), 1f)
        assertEquals(listOf("10 smtp.google.com"), a.records.map { it.value })
    }

    @Test
    fun reportsNxDomain() {
        val a = DnsClient.parse(b64("EjSBgwABAAAAAQAAEG5vbmV4aXN0ZW50LXp6MXEDY29tAAABAAHAHQAGAAEAAAOEAD0BYQxndGxkLXNlcnZlcnMDbmV0AAVuc3RsZAx2ZXJpc2lnbi1ncnPAHWq6W+cAAAcIAAADhAAJOoAAAAOE"), 1f)
        assertEquals("NXDOMAIN", a.rcodeName)
        assertTrue(a.records.isEmpty())
    }

    @Test
    fun parsesCnameChain() {
        val a = DnsClient.parse(b64("EjSBgAABAAIAAAAAA3d3dwZnaXRodWIDY29tAAABAAHADAAFAAEAAA3zAALAEMAQAAEAAQAAAB8ABIxScgM="), 1f)
        assertEquals(listOf("CNAME" to "github.com", "A" to "140.82.114.3"), a.records.map { it.type to it.value })
    }

    @Test
    fun encodesQuery() {
        val q = DnsClient.query("android.com", DnsType.AAAA)
        assertEquals(12 + 13 + 4, q.size)
        assertEquals(28, q[q.size - 3].toInt())
    }

    @Test
    fun subnetOfA24() {
        val hosts = subnetHosts("192.168.1.42", 24)
        assertEquals(254, hosts.size)
        assertEquals("192.168.1.1", hosts.first())
        assertEquals("192.168.1.254", hosts.last())
    }

    @Test
    fun widerSubnetsAreCappedToThe24AroundUs() {
        assertEquals("10.0.37.1", subnetHosts("10.0.37.9", 16).first())
    }

    @Test
    fun parsesMacAddresses() {
        assertEquals("a4:3e:51:7c:2a:9f", WakeOnLan.format(WakeOnLan.parseMac("A4-3E-51-7C-2A-9F")!!))
        assertNull(WakeOnLan.parseMac("a4:3e:51"))
        assertNull(WakeOnLan.parseMac("zz:3e:51:7c:2a:9f"))
    }
}
