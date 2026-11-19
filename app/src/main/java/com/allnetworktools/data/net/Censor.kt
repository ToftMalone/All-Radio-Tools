package com.allnetworktools.data.net

import java.io.EOFException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/*
 * A censorship probe in the spirit of OONI Probe, measured from this phone over its current network (Wi-Fi or mobile
 * data). OONI compares each measurement with one taken by its control servers; here the control is what a resolver
 * reached over HTTPS (DNS-over-HTTPS, which the network cannot read nor rewrite) says, and what the server itself
 * proves with its certificate. That tells most blocking apart:
 *
 * - DNS: the network's resolver says the name does not exist, or answers a private or loopback address, or an address
 *   whose server cannot show a certificate for the name, while the encrypted resolver answers properly.
 * - IP: the true address does not take a TCP connection.
 * - SNI: the TLS handshake naming the site is cut (reset, or silence) while the same handshake to the same address
 *   naming another site goes through: something reads the name and drops the connection.
 * - Interception: the handshake completes with a certificate the phone does not trust.
 *
 * A site that is down everywhere looks like an IP block from here; the verdict says "IP blocked or down".
 */

enum class Suite { Web, Messaging, Circumvention, Middlebox }

enum class Verdict {
    Ok, Partial, DnsBlocked, DnsTampered, TcpBlocked, SniBlocked, TlsBlocked, Intercepted, ProxyDetected, Down, Inconclusive;

    val blocked get() = this in setOf(DnsBlocked, DnsTampered, TcpBlocked, SniBlocked, TlsBlocked, Intercepted)
    val anomaly get() = this in setOf(Partial, ProxyDetected)
}

/** One step of a test: what was tried ("DNS", "TCP 443 1.2.3.4", "TLS"…), whether it worked, and a short note. */
data class Step(val what: String, val ok: Boolean, val note: String)

data class CensorResult(
    val suite: Suite,
    val name: String,
    val target: String,
    val category: String?,
    val verdict: Verdict,
    val steps: List<Step>,
    /** The site answers when its address comes from encrypted DNS: changing DNS is enough to get through. */
    val viaEncryptedDns: Boolean = false,
    val ms: Long = 0,
)

/** One endpoint of an app: a host name or address, a port, and how far to go with it. */
data class Endpoint(val host: String, val port: Int = 443, val how: How = How.Tls) {
    enum class How { Tcp, Tls, TlsAnyCert, Doh }
}

data class AppTarget(val name: String, val endpoints: List<Endpoint>, val sites: List<String> = emptyList())

object Censor {
    private const val TIMEOUT = 7000

    enum class Fail { Timeout, Refused, Reset, NoName, Cert, Unreachable, Other }

    class Out(val fail: Fail?, val note: String) { val ok get() = fail == null }

    private fun ok(note: String = "OK") = Out(null, note)

    private fun classify(e: Throwable): Out {
        val m = (e.message ?: "").lowercase()
        val f = when {
            e is SocketTimeoutException -> Fail.Timeout
            e is UnknownHostException -> Fail.NoName
            e is NoRouteToHostException -> Fail.Unreachable
            e is java.net.PortUnreachableException -> Fail.Refused
            e is SSLHandshakeException && (e.cause is CertificateException || e.cause is CertPathValidatorException || "certif" in m || "trust anchor" in m || "subject alternative" in m || "hostname" in m) -> Fail.Cert
            e is ConnectException && "refused" in m -> Fail.Refused
            "reset" in m || "broken pipe" in m || e is EOFException || "eof" in m || "closed" in m || "shutdown" in m -> Fail.Reset
            e is ConnectException && ("unreachable" in m || "no route" in m) -> Fail.Unreachable
            e is SocketException && "timed out" in m -> Fail.Timeout
            else -> Fail.Other
        }
        val note = when (f) {
            Fail.Timeout -> "timeout"; Fail.Refused -> "refused"; Fail.Reset -> "reset"; Fail.NoName -> "no address"
            Fail.Cert -> "untrusted certificate"; Fail.Unreachable -> "unreachable"; Fail.Other -> e.javaClass.simpleName
        }
        return Out(f, note)
    }

    // ---------------------------------------------------------------- primitives

    /** What the network's own resolver says (the phone's DNS). */
    suspend fun systemDns(host: String): Pair<List<InetAddress>, Out> = withContext(Dispatchers.IO) {
        val r = withTimeoutOrNull(TIMEOUT.toLong()) {
            coroutineScope { async(Dispatchers.IO) { runCatching { InetAddress.getAllByName(host).toList() } }.await() }
        } ?: return@withContext emptyList<InetAddress>() to Out(Fail.Timeout, "timeout")
        r.fold({ it to ok(it.joinToString(" ") { a -> a.hostAddress ?: "" }) }, { emptyList<InetAddress>() to classify(it) })
    }

    /**
     * What resolvers reached over HTTPS say: the control. Null when none answers (encrypted DNS blocked too); an empty
     * list when the name does not exist.
     */
    suspend fun encryptedDns(host: String): List<String>? {
        for (s in DnsClient.Public) {
            val a = runCatching { DnsClient.resolve(host, DnsType.A, s, doh = true, timeoutMs = TIMEOUT) }.getOrNull() ?: continue
            if (a.rcode == 3) return emptyList()
            if (a.rcode != 0) continue
            return a.records.filter { it.type == "A" }.map { it.value }
        }
        return null
    }

    fun bogon(a: InetAddress): Boolean = when (a) {
        is Inet4Address -> {
            val b = a.address.map { it.toInt() and 0xff }
            b[0] == 0 || b[0] == 10 || b[0] == 127 || (b[0] == 169 && b[1] == 254) || (b[0] == 172 && b[1] in 16..31) ||
                (b[0] == 192 && b[1] == 168) || (b[0] == 100 && b[1] in 64..127) || b[0] >= 224
        }
        is Inet6Address -> a.isLoopbackAddress || a.isAnyLocalAddress || a.isLinkLocalAddress || a.isSiteLocalAddress
        else -> false
    }

    suspend fun tcp(ip: String, port: Int): Out = withContext(Dispatchers.IO) {
        runCatching { Socket().use { it.connect(InetSocketAddress(InetAddress.getByName(ip), port), TIMEOUT) }; ok() }.getOrElse(::classify)
    }

    private val trustAll: SSLSocketFactory by lazy {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }.socketFactory
    }

    /**
     * A TLS handshake to [ip] naming [sni]. With [verify], the certificate must be trusted and match the name;
     * otherwise only the handshake counts (an app with its own certificate authority, or a decoy name).
     */
    suspend fun tls(ip: String, port: Int, sni: String, verify: Boolean = true): Out = withContext(Dispatchers.IO) {
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(InetAddress.getByName(ip), port), TIMEOUT)
        } catch (e: Exception) {
            raw.close()
            val o = classify(e)
            return@withContext Out(o.fail, "TCP ${o.note}")
        }
        try {
            raw.soTimeout = TIMEOUT
            val f = if (verify) SSLSocketFactory.getDefault() as SSLSocketFactory else trustAll
            (f.createSocket(raw, sni, port, true) as SSLSocket).use { s ->
                s.sslParameters = s.sslParameters.apply {
                    serverNames = listOf(SNIHostName(sni))
                    // The TLS layer checks the certificate's name itself, as a browser does.
                    if (verify) endpointIdentificationAlgorithm = "HTTPS"
                }
                s.startHandshake()
                ok(s.session.protocol)
            }
        } catch (e: Exception) {
            classify(e)
        } finally {
            runCatching { raw.close() }
        }
    }

    private val ipLiteral = Regex("^[0-9.]+$|:")

    private fun hostOf(url: String): String =
        url.trim().removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore('?').substringBefore(':').lowercase()

    // ---------------------------------------------------------------- websites

    /** OONI's Web Connectivity, with encrypted DNS and the site's own certificate as the control. */
    suspend fun site(url: String, category: String?, name: String = hostOf(url), suite: Suite = Suite.Web): CensorResult {
        val t0 = System.currentTimeMillis()
        val host = hostOf(url)
        val steps = ArrayList<Step>()
        fun done(v: Verdict, viaDoh: Boolean = false) = CensorResult(suite, name, url, category, v, steps.toList(), viaDoh, System.currentTimeMillis() - t0)
        if (host.isEmpty()) return done(Verdict.Inconclusive)

        val (sysAll, sysOut) = systemDns(host)
        val sys = sysAll.filterIsInstance<Inet4Address>().ifEmpty { sysAll }
        val ctl = encryptedDns(host)
        steps += Step("DNS", sysOut.ok && sys.none(::bogon), sysOut.note)
        steps += Step("DoH", ctl != null && ctl.isNotEmpty(), when { ctl == null -> "no answer"; ctl.isEmpty() -> "NXDOMAIN"; else -> ctl.joinToString(" ") })

        if (ctl != null && ctl.isEmpty() && !sysOut.ok) return done(Verdict.Down)
        if (ctl == null && !sysOut.ok) return done(Verdict.Inconclusive)

        // The fast path: the network's answer leads to the true server.
        val sysIp = sys.firstOrNull { !bogon(it) }?.hostAddress
        var sysTls: Out? = null
        if (sysIp != null) {
            val t = tls(sysIp, 443, host)
            sysTls = t
            steps += Step("TLS $sysIp", t.ok, t.note)
            if (t.ok) return done(Verdict.Ok)
        }

        // Something failed: which layer? The network's answer is a lie when it is no answer, a private address, or
        // another server than the encrypted resolver's that cannot prove it is the site.
        val ctlIp = ctl?.firstOrNull()
        val dnsLie = !sysOut.ok || sys.any(::bogon) ||
            (sysIp != null && ctl != null && ctl.isNotEmpty() && sysIp !in ctl && sysTls?.fail != null && sysTls.fail != Fail.Timeout)
        if (ctlIp == null) return done(if (dnsLie) Verdict.DnsTampered else Verdict.Inconclusive)

        val t = tcp(ctlIp, 443)
        steps += Step("TCP 443 $ctlIp", t.ok, t.note)
        if (!t.ok) return done(if (dnsLie) Verdict.DnsBlocked else Verdict.TcpBlocked)

        val h = tls(ctlIp, 443, host)
        steps += Step("TLS $ctlIp", h.ok, h.note)
        if (h.ok) return done(if (!sysOut.ok) Verdict.DnsBlocked else if (dnsLie) Verdict.DnsTampered else Verdict.Ok, viaDoh = dnsLie || !sysOut.ok)

        if (h.fail == Fail.Cert) return done(Verdict.Intercepted)
        // Is it the name? The same address, another name.
        val decoy = tls(ctlIp, 443, DECOY, verify = false)
        steps += Step("TLS $ctlIp SNI $DECOY", decoy.ok, decoy.note)
        val v = when {
            decoy.ok && (h.fail == Fail.Reset || h.fail == Fail.Timeout) -> Verdict.SniBlocked
            dnsLie -> Verdict.DnsTampered
            else -> Verdict.TlsBlocked
        }
        return done(v)
    }

    private const val DECOY = "example.com"

    // ---------------------------------------------------------------- apps: messaging and circumvention

    suspend fun app(suite: Suite, a: AppTarget): CensorResult {
        val t0 = System.currentTimeMillis()
        val steps = ArrayList<Step>()
        var dnsBad = 0
        val fails = ArrayList<Fail?>()
        for (e in a.endpoints) {
            val named = !ipLiteral.containsMatchIn(e.host)
            var ip = e.host
            if (named) {
                val (all, o) = systemDns(e.host)
                val v4 = all.filterIsInstance<Inet4Address>().ifEmpty { all }
                val good = v4.firstOrNull { !bogon(it) }?.hostAddress
                if (!o.ok || good == null) {
                    dnsBad++
                    steps += Step("DNS ${e.host}", false, if (o.ok) "→ ${v4.firstOrNull()?.hostAddress}" else o.note)
                    // The true address, to tell a DNS block from a dead endpoint.
                    val ctl = encryptedDns(e.host)?.firstOrNull()
                    if (ctl == null) { fails += Fail.NoName; continue }
                    ip = ctl
                } else ip = good
            }
            val o = when (e.how) {
                Endpoint.How.Tcp -> tcp(ip, e.port)
                Endpoint.How.Tls -> tls(ip, e.port, e.host)
                Endpoint.How.TlsAnyCert -> tls(ip, e.port, e.host, verify = false)
                Endpoint.How.Doh -> doh(e.host)
            }
            steps += Step("${e.how.name.removeSuffix("AnyCert")} ${e.host}:${e.port}", o.ok, o.note)
            fails += o.fail
        }
        for (s in a.sites) {
            val r = site("https://$s/", null, s, suite)
            steps += Step("Web $s", r.verdict == Verdict.Ok, r.verdict.name)
            fails += when (r.verdict) {
                Verdict.Ok -> null
                Verdict.DnsBlocked, Verdict.DnsTampered -> { dnsBad++; Fail.NoName }
                Verdict.Intercepted -> Fail.Cert
                Verdict.SniBlocked, Verdict.TlsBlocked -> Fail.Reset
                Verdict.TcpBlocked -> Fail.Timeout
                else -> Fail.Other
            }
        }
        val good = fails.count { it == null }
        val bad = fails.filterNotNull()
        val verdict = when {
            fails.isEmpty() -> Verdict.Inconclusive
            good == fails.size -> if (dnsBad > 0) Verdict.DnsTampered else Verdict.Ok
            good > 0 -> Verdict.Partial
            // Nothing got through: the most telling failure names it.
            Fail.Cert in bad -> Verdict.Intercepted
            bad.all { it == Fail.NoName } -> Verdict.DnsBlocked
            bad.count { it == Fail.Reset } * 2 >= bad.size -> Verdict.TlsBlocked
            bad.all { it == Fail.Other } -> Verdict.Inconclusive
            else -> Verdict.TcpBlocked
        }
        return CensorResult(suite, a.name, a.endpoints.firstOrNull()?.host ?: a.sites.firstOrNull() ?: "", null, verdict, steps, ms = System.currentTimeMillis() - t0)
    }

    private suspend fun doh(server: String): Out {
        val s = DnsClient.Public.firstOrNull { it.dohUrl?.contains(server) == true } ?: return Out(Fail.Other, "unknown")
        return runCatching { DnsClient.resolve("example.com", DnsType.A, s, doh = true, timeoutMs = TIMEOUT) }
            .fold({ if (it.rcode == 0 && it.records.isNotEmpty()) ok("${it.ms.toInt()} ms") else Out(Fail.Other, it.rcodeName) }, ::classify)
    }


    // ---------------------------------------------------------------- middleboxes

    /**
     * OONI's HTTP Header Field Manipulation: a plain-HTTP request to an echo server, with a header of our own; a
     * transparent proxy shows by headers it adds or changes, or by answering in the server's place.
     */
    suspend fun headerManipulation(): CensorResult = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val steps = ArrayList<Step>()
        val token = "AnT-" + (100000..999999).random()
        val r = runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(ECHO_HOST, 80), TIMEOUT)
                s.soTimeout = TIMEOUT
                val req = "GET /headers HTTP/1.1\r\nHost: $ECHO_HOST\r\nX-Probe-Token: $token\r\nAccept: */*\r\nConnection: close\r\n\r\n"
                s.getOutputStream().write(req.toByteArray())
                s.getInputStream().readNBytes(65_536).toString(Charsets.ISO_8859_1)
            }
        }
        val resp = r.getOrElse {
            val o = classify(it)
            steps += Step("HTTP $ECHO_HOST", false, o.note)
            return@withContext CensorResult(Suite.Middlebox, "HTTP", ECHO_HOST, null, Verdict.Inconclusive, steps, ms = System.currentTimeMillis() - t0)
        }
        val status = resp.substringBefore("\r\n")
        val body = resp.substringAfter("\r\n\r\n", "")
        steps += Step("HTTP $ECHO_HOST", status.contains(" 200"), status)
        val names = Regex("\"([A-Za-z0-9-]+)\"\\s*:").findAll(body.substringAfter("\"headers\"", "")).map { it.groupValues[1].lowercase() }.toSet()
        val tokenBack = body.contains(token)
        val added = names - setOf("host", "x-probe-token", "accept", "connection", "x-amzn-trace-id")
        steps += Step("X-Probe-Token", tokenBack, if (tokenBack) "intact" else "changed or missing")
        if (added.isNotEmpty()) steps += Step("+ headers", false, added.joinToString(", "))
        val verdict = when {
            !status.contains(" 200") || !body.contains("\"headers\"") -> Verdict.ProxyDetected
            !tokenBack || added.isNotEmpty() -> Verdict.ProxyDetected
            else -> Verdict.Ok
        }
        CensorResult(Suite.Middlebox, "HTTP", ECHO_HOST, null, verdict, steps, ms = System.currentTimeMillis() - t0)
    }

    private const val ECHO_HOST = "httpbin.org"

    /** The public address and country, as Cloudflare sees this connection; null when it cannot be had. */
    suspend fun whereAmI(): Pair<String, String>? = withContext(Dispatchers.IO) {
        runCatching {
            val c = java.net.URL("https://www.cloudflare.com/cdn-cgi/trace").openConnection() as HttpsURLConnection
            c.connectTimeout = TIMEOUT; c.readTimeout = TIMEOUT
            val t = c.inputStream.use { it.readNBytes(4096).toString(Charsets.UTF_8) }
            c.disconnect()
            val kv = t.lines().mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            (kv["ip"] ?: return@runCatching null) to (kv["loc"] ?: "")
        }.getOrNull()
    }

    /** (url, category) of each entry of an OONI test-list answer: {"results":[{"category_code":"NEWS","url":"https://…"},…]}. */
    fun parseOoni(json: String): List<Pair<String, String>> {
        val entry = Regex("\\{[^{}]*\\}")
        val url = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"")
        val cat = Regex("\"category_code\"\\s*:\\s*\"([^\"]*)\"")
        return entry.findAll(json.substringAfter("\"results\"", "")).mapNotNull { m ->
            val u = url.find(m.value)?.groupValues?.get(1)?.replace("\\/", "/") ?: return@mapNotNull null
            if (!u.startsWith("http")) null else u to (cat.find(m.value)?.groupValues?.get(1) ?: "")
        }.toList()
    }

    /** The URLs OONI's test lists hold for [country] (the global list included), from its public API. */
    suspend fun ooniList(country: String, limit: Int = 200): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val c = java.net.URL("https://api.ooni.io/api/v1/test-list/urls?country_code=${country.uppercase()}&limit=$limit").openConnection() as HttpsURLConnection
        c.connectTimeout = TIMEOUT; c.readTimeout = TIMEOUT * 2
        try {
            parseOoni(c.inputStream.use { it.readNBytes(2_000_000).toString(Charsets.UTF_8) })
        } finally {
            c.disconnect()
        }
    }
}
