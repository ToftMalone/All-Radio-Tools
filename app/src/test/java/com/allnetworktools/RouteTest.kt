package com.allnetworktools

import com.allnetworktools.data.net.DnsClient
import com.allnetworktools.data.net.IcmpPacket
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pieces of the network-bound tools that need no phone: ICMP packets and reverse DNS. */
class RouteTest {
    @Test fun echoRequestAsAPingSocketWantsIt() {
        val v4 = IcmpPacket.echoRequest(false, 7, 56)
        assertEquals(64, v4.size)
        assertEquals(8, v4[0].toInt())
        assertEquals(0, v4[1].toInt())
        assertEquals(7, v4[7].toInt())
        val v6 = IcmpPacket.echoRequest(true, 0x0102, 8)
        assertEquals(128, v6[0].toInt() and 0xFF)
        assertEquals(1, v6[6].toInt()); assertEquals(2, v6[7].toInt())
    }

    @Test fun onlyTheEchoReplyOfOurSequenceCounts() {
        fun pkt(type: Int, seq: Int) = ByteArray(16).also { it[0] = type.toByte(); it[6] = (seq shr 8).toByte(); it[7] = seq.toByte() }
        assertTrue(IcmpPacket.isReply(false, pkt(0, 1), 16, 1))
        assertTrue(IcmpPacket.isReply(true, pkt(129, 1), 16, 1))
        // A destination unreachable (3) or time exceeded (11) is an error message, not an answer.
        assertFalse(IcmpPacket.isReply(false, pkt(3, 1), 16, 1))
        assertFalse(IcmpPacket.isReply(false, pkt(11, 1), 16, 1))
        // Our own request echoed back, another sequence, a runt.
        assertFalse(IcmpPacket.isReply(false, pkt(8, 1), 16, 1))
        assertFalse(IcmpPacket.isReply(false, pkt(0, 2), 16, 1))
        assertFalse(IcmpPacket.isReply(false, pkt(0, 1), 4, 1))
        // The v4 reply type is not a v6 one.
        assertFalse(IcmpPacket.isReply(true, pkt(0, 1), 16, 1))
    }

    @Test fun reverseNames() {
        assertEquals("8.8.8.8.in-addr.arpa", DnsClient.ptrName(InetAddress.getByName("8.8.8.8")))
        assertEquals("1.2.0.192.in-addr.arpa", DnsClient.ptrName(InetAddress.getByName("192.0.2.1")))
        assertEquals(
            "1.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.8.b.d.0.1.0.0.2.ip6.arpa",
            DnsClient.ptrName(InetAddress.getByName("2001:db8::1")),
        )
    }

    @Test fun readsAPtrAnswer() {
        // Header (1 question, 1 answer), the question for 8.8.8.8, and "dns.google" with its name pointer-free.
        val q = DnsClient.query(DnsClient.ptrName(InetAddress.getByName("8.8.8.8")), DnsClient.PTR)
        val target = byteArrayOf(3, 'd'.code.toByte(), 'n'.code.toByte(), 's'.code.toByte(), 6, 'g'.code.toByte(), 'o'.code.toByte(), 'o'.code.toByte(), 'g'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(), 0)
        val answer = byteArrayOf(0xC0.toByte(), 12, 0, 12, 0, 1, 0, 0, 0x0E, 0x10, 0, target.size.toByte()) + target
        val resp = q.copyOf().also { it[2] = 0x81.toByte(); it[3] = 0x80.toByte(); it[7] = 1 } + answer
        val r = DnsClient.parse(resp, 1f)
        assertEquals(0, r.rcode)
        assertEquals(1, r.records.size)
        assertEquals("PTR", r.records[0].type)
        assertEquals("dns.google", r.records[0].value)
        assertEquals(3600L, r.records[0].ttl)
    }
}
