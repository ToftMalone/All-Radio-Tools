package com.allnetworktools.data.net

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---- Whois ---------------------------------------------------------------------------------------

data class WhoisHop(val server: String, val text: String)

data class WhoisResult(val query: String, val isIp: Boolean, val hops: List<WhoisHop>) {
    /** The most specific answer: the last server that returned something useful. */
    val text get() = hops.lastOrNull { it.text.lines().count { l -> ':' in l } > 3 }?.text ?: hops.lastOrNull()?.text.orEmpty()

    private fun first(vararg keys: String): String? {
        for (h in hops.asReversed()) for (k in keys) {
            val v = h.text.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("$k:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("REDACTED", true) }
            if (v != null) return v
        }
        return null
    }

    private fun all(vararg keys: String, lower: Boolean = true): List<String> = hops.asReversed().firstNotNullOfOrNull { h ->
        h.text.lineSequence().map { it.trim() }
            .filter { l -> keys.any { l.startsWith("$it:", ignoreCase = true) } }
            .map { it.substringAfter(':').trim().substringBefore(' ').let { v -> if (lower) v.lowercase() else v } }
            .filter { it.isNotEmpty() }.distinct().toList().takeIf { it.isNotEmpty() }
    }.orEmpty()

    val registrar get() = first("Registrar", "registrar", "Sponsoring Registrar")
    val created get() = first("Creation Date", "created", "Registered on", "Registration Time", "RegDate")
    val expires get() = first("Registry Expiry Date", "Registrar Registration Expiration Date", "Expiry Date", "Expiration Date", "expire", "paid-till")
    val updated get() = first("Updated Date", "last-update", "changed", "Last Modified", "Updated")
    val nameServers get() = all("Name Server", "nserver")
    val status get() = all("Domain Status", "status", lower = false).map { it.substringBefore('(') }.take(4)
    val dnssec get() = first("DNSSEC", "dnssec")
    val registrant get() = first("Registrant Organization", "Registrant", "holder-c", "org-name", "OrgName", "Organization")
    val country get() = first("Registrant Country", "country", "Country")
    val network get() = first("inetnum", "NetRange", "inet6num", "CIDR")
    val netName get() = first("netname", "NetName")
    val asn get() = first("origin", "OriginAS")
    val description get() = first("descr", "org-name", "OrgName")
}

object Whois {
    private val IpRegex = Regex("""^[0-9.]+$|^[0-9a-fA-F:]+$""")

    fun isIp(q: String) = IpRegex.matches(q) && (q.count { it == '.' } == 3 || ':' in q)

    private suspend fun ask(server: String, query: String): String = withContext(Dispatchers.IO) {
        Socket().use { s ->
            s.connect(InetSocketAddress(server, 43), 5000)
            s.soTimeout = 8000
            s.getOutputStream().write("$query\r\n".toByteArray())
            s.getInputStream().readBytes().toString(Charsets.UTF_8).take(100_000)
        }
    }

    private fun refer(text: String): String? = text.lineSequence().map { it.trim() }.firstNotNullOfOrNull { l ->
        when {
            l.startsWith("refer:", true) || l.startsWith("whois:", true) -> l.substringAfter(':').trim()
            l.startsWith("Registrar WHOIS Server:", true) -> l.substringAfter(':').trim()
            l.startsWith("ReferralServer:", true) -> l.substringAfter("whois://").trim().trimEnd('/')
            else -> null
        }
    }?.removePrefix("whois://")?.substringBefore(':')?.takeIf { it.contains('.') }

    /** Follows the referral chain from IANA to the registry, then the registrar (at most 3 servers). */
    suspend fun lookup(input: String): WhoisResult {
        val q = input.trim().lowercase().removePrefix("http://").removePrefix("https://").substringBefore('/').removePrefix("www.")
        val ip = isIp(q)
        val hops = mutableListOf<WhoisHop>()
        var server = "whois.iana.org"
        var query = if (ip) q else q.substringAfterLast('.')
        repeat(3) {
            val text = try {
                ask(server, query)
            } catch (e: IOException) {
                if (hops.isEmpty()) throw e else return WhoisResult(q, ip, hops)
            }
            hops += WhoisHop(server, text)
            val next = refer(text)?.takeIf { it != server } ?: return WhoisResult(q, ip, hops)
            server = next
            query = when {
                ip && server == "whois.arin.net" -> "n + $q"
                else -> q
            }
        }
        return WhoisResult(q, ip, hops)
    }
}
