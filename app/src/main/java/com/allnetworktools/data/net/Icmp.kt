package com.allnetworktools.data.net

import android.net.Network
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.net.Inet6Address
import java.net.InetAddress

/** One ICMP echo through a network that is not the system's default one. */
object Icmp {
    private const val IPPROTO_IP = 0
    private const val IP_TTL = 2
    private const val IPPROTO_IPV6 = 41
    private const val IPV6_UNICAST_HOPS = 16

    /**
     * One echo to [addr] through [network], bound by the system itself: no `ping` binary, so it works through a
     * network that is not the default one. A router that drops the packet for a spent [ttl] is reported, but without
     * its address (an ICMP socket only tells the error, not who sent it). A silent target gives a reply with no time.
     */
    fun echo(network: Network, addr: InetAddress, size: Int, timeoutMs: Int, ttl: Int?): PingReply {
        val v6 = addr is Inet6Address
        val sock = Os.socket(
            if (v6) OsConstants.AF_INET6 else OsConstants.AF_INET, OsConstants.SOCK_DGRAM,
            if (v6) OsConstants.IPPROTO_ICMPV6 else OsConstants.IPPROTO_ICMP,
        )
        try {
            network.bindSocket(sock)
            if (ttl != null) {
                if (v6) Os.setsockoptInt(sock, IPPROTO_IPV6, IPV6_UNICAST_HOPS, ttl) else Os.setsockoptInt(sock, IPPROTO_IP, IP_TTL, ttl)
            }
            val seq = 1
            val pkt = IcmpPacket.echoRequest(v6, seq, size)
            val t0 = System.nanoTime()
            fun ms() = (System.nanoTime() - t0) / 1e6f
            Os.sendto(sock, pkt, 0, pkt.size, 0, addr, 0)
            val buf = ByteArray(2048)
            while (true) {
                val left = timeoutMs - ms().toInt()
                if (left <= 0) return PingReply(null, null, false, null)
                val p = StructPollfd().apply { fd = sock; events = OsConstants.POLLIN.toShort() }
                if (Os.poll(arrayOf(p), left) == 0) return PingReply(null, null, false, null)
                val n = try {
                    Os.recvfrom(sock, buf, 0, buf.size, 0, null as java.net.InetSocketAddress?)
                } catch (e: ErrnoException) {
                    // The hop count ran out, or the network says there is no way: the router's address is not given.
                    return if (ttl != null && e.errno == OsConstants.EHOSTUNREACH) PingReply(null, ms(), true, null, hidden = true)
                    else PingReply(null, null, false, null)
                }
                if (IcmpPacket.isReply(v6, buf, n, seq)) return PingReply(addr.hostAddress, ms(), false, ttl)
            }
        } finally {
            runCatching { Os.close(sock) }
        }
    }
}
