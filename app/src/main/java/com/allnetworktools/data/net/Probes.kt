package com.allnetworktools.data.net

import java.io.IOException
import java.net.ConnectException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.allnetworktools.R
import com.allnetworktools.util.S

data class PingReply(
    /** Address that answered: the target, or a router reporting "time to live exceeded". */
    val from: String?,
    val rttMs: Float?,
    val ttlExceeded: Boolean,
    val ttl: Int?,
)

private val FromRegex = Regex("""[Ff]rom ([0-9a-fA-F:.]+)""")
private val TimeRegex = Regex("""time[=<]([\d.]+)\s*ms""")
private val TtlRegex = Regex("""ttl=(\d+)""")

object Net {
    suspend fun resolve(host: String): InetAddress = withContext(Dispatchers.IO) { InetAddress.getByName(host.trim()) }

    suspend fun reverse(addr: InetAddress, timeoutMs: Long = 1500): String? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(timeoutMs) {
            runInterruptible { addr.canonicalHostName }.takeIf { it != addr.hostAddress }
        }
    }

    /**
     * One ICMP echo through the system `ping` binary, which apps may run without privileges.
     * [ttl] limits the hop count (traceroute); [timeoutS] is the reply wait.
     */
    suspend fun ping(addr: InetAddress, sizeBytes: Int = 56, timeoutS: Int = 2, ttl: Int? = null): PingReply = withContext(Dispatchers.IO) {
        val bin = if (addr is Inet6Address) "/system/bin/ping6" else "/system/bin/ping"
        val cmd = buildList {
            add(bin); add("-n"); add("-c"); add("1"); add("-W"); add(timeoutS.toString()); add("-s"); add(sizeBytes.toString())
            if (ttl != null) { add("-t"); add(ttl.toString()) }
            add(addr.hostAddress!!.substringBefore('%'))
        }
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        try {
            val out = runInterruptible {
                process.waitFor((timeoutS + 2).toLong(), TimeUnit.SECONDS)
                process.inputStream.bufferedReader().readText()
            }
            val line = out.lines().firstOrNull { "from" in it.lowercase() } ?: return@withContext PingReply(null, null, false, null)
            // "Destination Host Unreachable" comes from our own stack or a router: not an answer from the target.
            if ("unreachable" in line.lowercase()) return@withContext PingReply(null, null, false, null)
            PingReply(
                from = FromRegex.find(line)?.groupValues?.get(1)?.trimEnd(':'),
                rttMs = TimeRegex.find(line)?.groupValues?.get(1)?.toFloatOrNull(),
                ttlExceeded = "exceeded" in line.lowercase(),
                ttl = TtlRegex.find(line)?.groupValues?.get(1)?.toIntOrNull(),
            )
        } finally {
            process.destroy()
        }
    }

    enum class Tcp { Open, Closed, Filtered, Unreachable }

    data class TcpResult(val state: Tcp, val ms: Float)

    /** TCP connect probe: refused = closed (host alive), timeout = filtered. */
    suspend fun tcp(addr: InetAddress, port: Int, timeoutMs: Int, keepOpen: ((Socket) -> Unit)? = null): TcpResult = withContext(Dispatchers.IO) {
        val t0 = System.nanoTime()
        fun ms() = (System.nanoTime() - t0) / 1e6f
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(addr, port), timeoutMs)
            val r = TcpResult(Tcp.Open, ms())
            keepOpen?.invoke(socket)
            r
        } catch (e: ConnectException) {
            TcpResult(if (e.message?.contains("ECONNREFUSED") == true) Tcp.Closed else Tcp.Unreachable, ms())
        } catch (_: SocketTimeoutException) {
            TcpResult(Tcp.Filtered, ms())
        } catch (_: NoRouteToHostException) {
            TcpResult(Tcp.Unreachable, ms())
        } catch (_: IOException) {
            TcpResult(Tcp.Unreachable, ms())
        } finally {
            runCatching { socket.close() }
        }
    }

    /** Reads what a service volunteers on connect (SSH, FTP, SMTP…) or the HTTP Server header. */
    fun grabBanner(socket: Socket, port: Int, host: String): String? = runCatching {
        socket.soTimeout = 700
        if (port in HttpPorts) {
            socket.getOutputStream().write("HEAD / HTTP/1.0\r\nHost: $host\r\nUser-Agent: AllRadioTools\r\n\r\n".toByteArray())
        }
        val buf = ByteArray(512)
        val n = socket.getInputStream().read(buf)
        if (n <= 0) return null
        val text = String(buf, 0, n, Charsets.ISO_8859_1)
        if (text.startsWith("HTTP/")) {
            val status = text.lineSequence().first().substringAfter(' ').trim()
            val server = text.lineSequence().firstOrNull { it.startsWith("Server:", true) }?.substringAfter(':')?.trim()
            listOfNotNull(server, "HTTP $status").joinToString(" · ")
        } else {
            text.lineSequence().first().trim().filter { it.code in 32..126 }.take(60).ifEmpty { null }
        }
    }.getOrNull()

    val HttpPorts = setOf(80, 81, 443, 591, 3000, 5000, 7080, 8000, 8008, 8080, 8081, 8088, 8443, 8888, 9000, 9443)
}

/** Well-known TCP services, their label and a security note when exposing them is risky. */
object Services {
    private val names = mapOf(
        20 to "ftp-data", 21 to "ftp", 22 to "ssh", 23 to "telnet", 25 to "smtp", 53 to "domain", 67 to "dhcp", 80 to "http",
        81 to "http-alt", 88 to "kerberos", 110 to "pop3", 111 to "rpcbind", 119 to "nntp", 123 to "ntp", 135 to "msrpc",
        137 to "netbios-ns", 139 to "netbios-ssn", 143 to "imap", 161 to "snmp", 179 to "bgp", 389 to "ldap", 443 to "https",
        445 to "microsoft-ds", 465 to "smtps", 514 to "syslog", 515 to "printer", 548 to "afp", 554 to "rtsp", 587 to "submission",
        631 to "ipp", 636 to "ldaps", 853 to "dns-over-tls", 873 to "rsync", 993 to "imaps", 995 to "pop3s", 1080 to "socks",
        1194 to "openvpn", 1433 to "ms-sql", 1521 to "oracle", 1723 to "pptp", 1883 to "mqtt", 1900 to "upnp", 2049 to "nfs",
        2375 to "docker", 3000 to "http-dev", 3306 to "mysql", 3389 to "ms-wbt-server", 3689 to "daap", 4070 to "spotify",
        5000 to "upnp/http", 5001 to "https-alt", 5060 to "sip", 5222 to "xmpp", 5353 to "mdns", 5357 to "wsdapi", 5432 to "postgresql",
        5555 to "adb", 5900 to "vnc", 5985 to "winrm", 6379 to "redis", 6443 to "kubernetes", 7000 to "airplay", 7100 to "airplay",
        8000 to "http-alt", 8008 to "http (cast)", 8009 to "cast (tls)", 8080 to "http-proxy", 8081 to "http-alt", 8123 to "home-assistant",
        8443 to "https-alt", 8883 to "mqtt-tls", 8888 to "http-alt", 9000 to "http-alt", 9090 to "prometheus", 9100 to "jetdirect",
        9443 to "https-alt", 10000 to "webmin", 11211 to "memcached", 27017 to "mongodb", 32400 to "plex", 49152 to "upnp",
        62078 to "iphone-sync",
    )

    fun name(port: Int) = names[port] ?: "inconnu"

    fun warning(port: Int): String? = when (port) {
        139, 445 -> S(R.string.file_sharing_exposed_on_the_network)
        23 -> S(R.string.telnet_credentials_sent_in_clear)
        21 -> S(R.string.ftp_credentials_sent_in_clear)
        3389 -> S(R.string.remote_desktop_exposed)
        5900 -> S(R.string.vnc_exposed_check_the_password)
        5555 -> S(R.string.adb_debugging_open_takeover_possible)
        2375 -> S(R.string.docker_api_without_tls)
        6379, 11211, 27017 -> S(R.string.database_reachable_without_authentication)
        else -> null
    }

    /** nmap's most frequent TCP ports, trimmed to 100. */
    val Top100 = listOf(
        7, 9, 13, 21, 22, 23, 25, 26, 37, 53, 79, 80, 81, 88, 106, 110, 111, 113, 119, 135, 139, 143, 144, 179, 199, 389, 427,
        443, 444, 445, 465, 513, 514, 515, 543, 544, 548, 554, 587, 631, 646, 873, 990, 993, 995, 1025, 1026, 1027, 1028, 1029,
        1110, 1433, 1720, 1723, 1755, 1900, 2000, 2001, 2049, 2121, 2717, 3000, 3128, 3306, 3389, 3986, 4899, 5000, 5009, 5051,
        5060, 5101, 5190, 5357, 5432, 5631, 5666, 5800, 5900, 6000, 6001, 6646, 7070, 8000, 8008, 8009, 8080, 8081, 8443, 8888,
        9100, 9999, 10000, 32768, 49152, 49153, 49154, 49155, 49156, 49157,
    )


    /** Quick set used to fingerprint a LAN device. */
    val Fingerprint = listOf(21, 22, 23, 53, 80, 139, 443, 445, 515, 548, 554, 631, 1883, 3389, 5000, 5001, 5353, 5900, 7000, 8008, 8009, 8080, 8123, 8443, 9100, 32400, 62078)
}
