package com.allnetworktools.data.net

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.nio.ByteBuffer
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class DnsType(val code: Int) { A(1), AAAA(28), MX(15), TXT(16), CNAME(5), NS(2) }

data class DnsRecord(val type: String, val value: String, val ttl: Long)

data class DnsAnswer(val rcode: Int, val records: List<DnsRecord>, val ms: Float) {
    val rcodeName: String get() = when (rcode) {
        0 -> "NOERROR"; 1 -> "FORMERR"; 2 -> "SERVFAIL"; 3 -> "NXDOMAIN"; 4 -> "NOTIMP"; 5 -> "REFUSED"; else -> "RCODE $rcode"
    }
}

data class DnsServer(val name: String, val ip: String, val dohUrl: String?)

/** Minimal RFC 1035 client over UDP, or over HTTPS (RFC 8484) when the resolver offers it. */
object DnsClient {
    val Public = listOf(
        DnsServer("Cloudflare", "1.1.1.1", "https://cloudflare-dns.com/dns-query"),
        DnsServer("Google", "8.8.8.8", "https://dns.google/dns-query"),
        DnsServer("Quad9", "9.9.9.9", "https://dns.quad9.net/dns-query"),
    )

    const val PTR = 12

    fun query(name: String, type: DnsType): ByteArray = query(name, type.code)

    fun query(name: String, typeCode: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val id = Random.nextInt(0, 0xFFFF)
        out.write(byteArrayOf((id shr 8).toByte(), id.toByte(), 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0))
        name.trim().trimEnd('.').split('.').forEach { label ->
            val b = java.net.IDN.toASCII(label).toByteArray()
            out.write(b.size); out.write(b)
        }
        out.write(0)
        out.write(byteArrayOf((typeCode shr 8).toByte(), typeCode.toByte(), 0, 1))
        return out.toByteArray()
    }

    suspend fun resolve(name: String, type: DnsType, server: DnsServer, doh: Boolean, timeoutMs: Int = 4000, route: NetRoute = NetRoute.System): DnsAnswer = withContext(Dispatchers.IO) {
        val q = query(name, type)
        val t0 = System.nanoTime()
        val raw = if (doh && server.dohUrl != null) viaHttps(q, server.dohUrl, timeoutMs, route) else viaUdp(q, InetAddress.getByName(server.ip), timeoutMs, route)
        val ms = (System.nanoTime() - t0) / 1e6f
        parse(raw, ms)
    }

    /** "d.c.b.a.in-addr.arpa" (IPv4) or the nibbles of an IPv6 address under "ip6.arpa". */
    fun ptrName(addr: InetAddress): String {
        val b = addr.address
        return if (b.size == 4) b.reversed().joinToString(".") { (it.toInt() and 0xFF).toString() } + ".in-addr.arpa"
        else b.reversed().joinToString(".") { "%x.%x".format(it.toInt() and 0xF, (it.toInt() shr 4) and 0xF) } + ".ip6.arpa"
    }

    /**
     * The name a router or host goes by, asked of [route]'s own DNS server (the system's reverse lookup would go
     * through the default network instead). Null when there is none, or no usable server.
     */
    suspend fun reverse(addr: InetAddress, route: NetRoute, timeoutMs: Int = 1500): String? = withContext(Dispatchers.IO) {
        val server = route.dns.firstOrNull { !it.isLinkLocalAddress && !it.isAnyLocalAddress } ?: return@withContext null
        runCatching { parse(viaUdp(query(ptrName(addr), PTR), server, timeoutMs, route), 0f) }
            .getOrNull()?.records?.firstOrNull { it.type == "PTR" }?.value?.trimEnd('.')
    }

    private fun viaUdp(q: ByteArray, server: InetAddress, timeoutMs: Int, route: NetRoute): ByteArray = route.datagram().use { s ->
        s.soTimeout = timeoutMs
        s.send(DatagramPacket(q, q.size, server, 53))
        val buf = ByteArray(4096)
        val p = DatagramPacket(buf, buf.size)
        s.receive(p)
        buf.copyOf(p.length)
    }

    private fun viaHttps(q: ByteArray, url: String, timeoutMs: Int, route: NetRoute): ByteArray {
        val c = route.open(URL(url)) as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/dns-message")
            c.setRequestProperty("Accept", "application/dns-message")
            c.outputStream.use { it.write(q) }
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    private fun readName(buf: ByteBuffer, start: Int): Pair<String, Int> {
        val labels = mutableListOf<String>()
        var pos = start
        var end = -1
        var jumps = 0
        while (true) {
            val len = buf.get(pos).toInt() and 0xFF
            when {
                len == 0 -> { pos++; break }
                len and 0xC0 == 0xC0 -> {
                    if (end < 0) end = pos + 2
                    pos = ((len and 0x3F) shl 8) or (buf.get(pos + 1).toInt() and 0xFF)
                    if (++jumps > 20) break
                }
                else -> {
                    val bytes = ByteArray(len) { buf.get(pos + 1 + it) }
                    labels += String(bytes, Charsets.UTF_8)
                    pos += 1 + len
                }
            }
        }
        return labels.joinToString(".") to (if (end >= 0) end else pos)
    }

    fun parse(raw: ByteArray, ms: Float): DnsAnswer {
        val buf = ByteBuffer.wrap(raw)
        val rcode = raw[3].toInt() and 0x0F
        val qd = buf.getShort(4).toInt() and 0xFFFF
        val an = buf.getShort(6).toInt() and 0xFFFF
        var pos = 12
        repeat(qd) { pos = readName(buf, pos).second + 4 }
        val records = mutableListOf<DnsRecord>()
        repeat(an) {
            pos = readName(buf, pos).second
            val type = buf.getShort(pos).toInt() and 0xFFFF
            val ttl = buf.getInt(pos + 4).toLong() and 0xFFFFFFFFL
            val len = buf.getShort(pos + 8).toInt() and 0xFFFF
            val data = pos + 10
            val value: String? = when (type) {
                1 -> InetAddress.getByAddress(raw.copyOfRange(data, data + 4)).hostAddress
                28 -> InetAddress.getByAddress(raw.copyOfRange(data, data + 16)).hostAddress
                2, 5, 12 -> readName(buf, data).first
                15 -> "${buf.getShort(data).toInt() and 0xFFFF} ${readName(buf, data + 2).first}"
                16 -> buildString {
                    var p = data
                    while (p < data + len) {
                        val l = raw[p].toInt() and 0xFF
                        append(String(raw, p + 1, l, Charsets.UTF_8)); p += 1 + l
                    }
                }.let { "\"$it\"" }
                else -> null
            }
            val typeName = if (type == PTR) "PTR" else DnsType.entries.firstOrNull { it.code == type }?.name
            if (value != null && typeName != null) records += DnsRecord(typeName, value, ttl)
            pos = data + len
        }
        return DnsAnswer(rcode, records, ms)
    }
}
