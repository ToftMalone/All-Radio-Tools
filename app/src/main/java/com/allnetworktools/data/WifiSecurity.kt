package com.allnetworktools.data

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import com.allnetworktools.data.net.DnsClient
import com.allnetworktools.data.net.DnsServer
import com.allnetworktools.data.net.DnsType
import com.allnetworktools.data.net.Net
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.allnetworktools.R
import com.allnetworktools.util.S

enum class CheckLevel(val label: String) { Bad(S(R.string.risk)), Warn(S(R.string.to_improve)), Info("Info"), Good("OK") }

data class SecCheck(val level: CheckLevel, val title: String, val detail: String)

/** Addresses and state of the active network, read from Android's link properties. */
data class LinkSnapshot(
    val wifi: Boolean,
    val gateway: String?,
    val ipv6Routers: List<String>,
    val dns: List<String>,
    val dhcpServer: String?,
    val privateDns: Boolean,
    val privateDnsServer: String?,
    val validated: Boolean,
    val captivePortal: Boolean,
)

// ---- 1. Audit of the connected network ------------------------------------------------------------

object NetworkAudit {
    /**
     * [security] is Android's description of the link (WifiInfo), [caps] the capabilities of the
     * connected AP in the last scan ("[RSN-SAE+PSK-CCMP][MFPC][WPS][ESS]"), [openPorts] the admin
     * ports found open on the gateway.
     */
    fun checks(conn: WifiConnection, caps: String?, link: LinkSnapshot?, openPorts: Set<Int>?): List<SecCheck> = buildList {
        val sec = conn.security.orEmpty()
        val c = caps.orEmpty()
        add(
            when {
                sec.startsWith("Ouvert") -> SecCheck(CheckLevel.Bad, S(R.string.open_network), S(R.string.no_encryption_anyone_in_range_can))
                "WEP" in sec -> SecCheck(CheckLevel.Bad, S(R.string.wep_encryption), S(R.string.wep_breaks_in_minutes_switch_the))
                "OWE" in sec -> SecCheck(CheckLevel.Warn, "Enhanced Open (OWE)", S(R.string.traffic_is_encrypted_but_anyone_can))
                "192" in sec -> SecCheck(CheckLevel.Good, S(R.string.wpa3_enterprise_192_bit), S(R.string.the_strongest_level_of_wi_fi))
                "EAP" in sec -> SecCheck(CheckLevel.Good, S(R.string.wpa_enterprise), S(R.string.every_user_has_their_own_credentials))
                "SAE" in sec -> SecCheck(CheckLevel.Good, S(R.string.wpa3_personal), S(R.string.current_protocol_protects_the_password_against))
                "PSK" in sec || "WPA2" in sec -> SecCheck(CheckLevel.Info, S(R.string.wpa2_personal), S(R.string.fine_if_the_password_is_long))
                else -> SecCheck(CheckLevel.Info, S(R.string.security, sec.ifEmpty { "inconnue" }), S(R.string.android_does_not_say_which_protocol))
            },
        )
        if ("SAE" in c && "PSK" in c) {
            add(SecCheck(CheckLevel.Warn, S(R.string.wpa2_wpa3_transition_mode), S(R.string.the_access_point_still_accepts_wpa2)))
        }
        if ("TKIP" in c) {
            add(SecCheck(CheckLevel.Bad, S(R.string.tkip_encryption), S(R.string.tkip_wpa1_is_obsolete_and_vulnerable)))
        }
        if ("WPS" in c) {
            add(SecCheck(CheckLevel.Warn, S(R.string.wps_enabled), S(R.string.the_wps_pin_can_be_brute)))
        }
        when {
            "MFPR" in c || ("SAE" in c && "PSK" !in c) -> add(SecCheck(CheckLevel.Good, S(R.string.protected_management_frames_pmf), S(R.string.required_prevents_forced_disconnections_and_some)))
            "MFPC" in c -> add(SecCheck(CheckLevel.Info, S(R.string.protected_management_frames_pmf), S(R.string.offered_but_not_required_older_devices)))
            caps != null -> add(SecCheck(CheckLevel.Warn, S(R.string.unprotected_management_frames), S(R.string.the_access_point_does_not_advertise)))
        }
        if (conn.standard in 1..4) {
            add(SecCheck(CheckLevel.Info, S(R.string.old_hardware_wi_fi_4_or), S(R.string.old_access_points_often_no_longer)))
        }
        if (link != null) {
            add(
                if (link.privateDns) SecCheck(CheckLevel.Good, S(R.string.encrypted_dns), S(R.string.your_dns_queries_are_encrypted) + (link.privateDnsServer?.let { S(R.string.to, it) } ?: "") + S(R.string.the_network_cannot_see_the_names))
                else SecCheck(CheckLevel.Warn, S(R.string.plain_text_dns), S(R.string.the_network_can_see_and_change)),
            )
        }
        if (openPorts != null) {
            if (23 in openPorts) add(SecCheck(CheckLevel.Bad, S(R.string.telnet_open_on_the_router), S(R.string.the_gateway_s_port_23_accepts)))
            if (80 in openPorts && 443 !in openPorts) add(SecCheck(CheckLevel.Warn, S(R.string.router_administration_over_http), S(R.string.the_router_s_interface_is_only)))
            else if (443 in openPorts) add(SecCheck(CheckLevel.Good, S(R.string.router_administration_over_https), S(R.string.the_router_s_interface_is_reachable)))
            if (openPorts.isEmpty()) add(SecCheck(CheckLevel.Good, S(R.string.no_administration_port_open), S(R.string.neither_telnet_http_nor_https_answer)))
        }
    }

    fun score(list: List<SecCheck>): Int = (100 - 30 * list.count { it.level == CheckLevel.Bad } - 10 * list.count { it.level == CheckLevel.Warn }).coerceIn(0, 100)
}

// ---- 2. Network probes --------------------------------------------------------------------------

/** Network probes, replaced by fakes in tests. */
interface NetProbes {
    /** HTTP status and body length of the connectivity check URL, redirects not followed; null when unreachable. */
    suspend fun connectivityCheck(): Triple<Int, Int, String?>?

    /** IPv4 addresses from the system resolver; empty for "no such domain", null on network error. */
    suspend fun systemResolve(host: String): List<String>?

    /** A records from a DNS query sent over UDP straight to [server]; null when nobody answered. */
    suspend fun udpQuery(server: String, host: String): List<String>?

    /** Source of the "time exceeded" reply to a TTL 1 probe: the first router on the path. */
    suspend fun firstHop(): String?

    suspend fun tcpOpen(host: String, port: Int): Boolean
}

class RealProbes : NetProbes {
    override suspend fun connectivityCheck(): Triple<Int, Int, String?>? = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL(ConnectivityUrl).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 5000
            c.readTimeout = 5000
            c.useCaches = false
            try {
                val code = c.responseCode
                val body = runCatching { (if (code >= 400) c.errorStream else c.inputStream)?.use { it.readBytes().size } ?: 0 }.getOrDefault(0)
                Triple(code, body, c.getHeaderField("Location"))
            } finally {
                c.disconnect()
            }
        }.getOrNull()
    }

    override suspend fun systemResolve(host: String): List<String>? = withContext(Dispatchers.IO) {
        try {
            InetAddress.getAllByName(host).filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
        } catch (_: java.net.UnknownHostException) {
            emptyList()
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun udpQuery(server: String, host: String): List<String>? = runCatching {
        DnsClient.resolve(host, DnsType.A, DnsServer(server, server, null), doh = false, timeoutMs = 2500).records.filter { it.type == "A" }.map { it.value }
    }.getOrNull()

    override suspend fun firstHop(): String? = runCatching { Net.ping(InetAddress.getByName("1.1.1.1"), ttl = 1).from }.getOrNull()

    override suspend fun tcpOpen(host: String, port: Int): Boolean =
        runCatching { Net.tcp(InetAddress.getByName(host), port, 1200).state == Net.Tcp.Open }.getOrDefault(false)

    companion object {
        const val ConnectivityUrl = "http://connectivitycheck.gstatic.com/generate_204"
    }
}

// ---- 3. Man-in-the-middle watch ------------------------------------------------------------------

data class GatewaySnapshot(
    val timeMs: Long,
    val gateway: String?,
    val dhcpServer: String?,
    val dns: List<String>,
    val ipv6Routers: List<String>,
    val firstHop: String?,
    val bssid: String?,
)

data class MitmEvent(val timeMs: Long, val level: CheckLevel, val text: String)

object MitmWatch {
    fun checks(s: GatewaySnapshot): List<SecCheck> = buildList {
        val gw = s.gateway
        when {
            gw == null -> add(SecCheck(CheckLevel.Info, S(R.string.no_ipv4_gateway), S(R.string.the_network_does_not_advertise_an)))
            s.firstHop == null -> add(SecCheck(CheckLevel.Info, S(R.string.first_hop_unknown), S(R.string.the_first_router_did_not_answer)))
            s.firstHop == gw -> add(SecCheck(CheckLevel.Good, S(R.string.traffic_goes_through_the_router), S(R.string.the_first_router_on_the_way, gw)))
            else -> add(
                SecCheck(
                    CheckLevel.Bad, S(R.string.a_device_is_in_the_middle),
                    S(R.string.your_packets_go_first_through_instead, s.firstHop, gw),
                ),
            )
        }
        if (s.dhcpServer != null && gw != null && s.dhcpServer != gw) {
            add(SecCheck(CheckLevel.Warn, S(R.string.dhcp_server_other_than_the_router), S(R.string.the_ip_address_was_assigned_by, s.dhcpServer, gw)))
        } else if (s.dhcpServer != null) {
            add(SecCheck(CheckLevel.Good, S(R.string.dhcp_server_the_gateway), S(R.string.the_ip_address_was_assigned_by_2)))
        }
        if (s.ipv6Routers.size > 1) {
            add(SecCheck(CheckLevel.Warn, S(R.string.several_ipv6_routers), S(R.string.routers_advertise_themselves_over_ipv6_a, s.ipv6Routers.size, s.ipv6Routers.joinToString())))
        }
        if (s.dns.any { d -> !isPrivate(d) && d != gw } && s.dns.none { it == gw }) {
            add(SecCheck(CheckLevel.Info, S(R.string.public_dns), S(R.string.the_network_hands_out_the_dns, s.dns.joinToString())))
        }
    }

    /** What changed between two snapshots on the same access point. */
    fun changes(a: GatewaySnapshot, b: GatewaySnapshot): List<MitmEvent> = buildList {
        if (a.bssid != b.bssid) return@buildList
        val t = b.timeMs
        if (a.gateway != b.gateway) add(MitmEvent(t, CheckLevel.Bad, S(R.string.the_gateway_changed, a.gateway, b.gateway)))
        if (a.dhcpServer != b.dhcpServer && a.dhcpServer != null && b.dhcpServer != null) add(MitmEvent(t, CheckLevel.Bad, S(R.string.the_dhcp_server_changed, a.dhcpServer, b.dhcpServer)))
        if (a.dns.toSet() != b.dns.toSet()) add(MitmEvent(t, CheckLevel.Warn, S(R.string.the_dns_servers_changed, a.dns.joinToString(), b.dns.joinToString())))
        (b.ipv6Routers - a.ipv6Routers.toSet()).forEach { add(MitmEvent(t, CheckLevel.Warn, S(R.string.new_ipv6_router, it))) }
        if (a.firstHop != null && b.firstHop != null && a.firstHop != b.firstHop) add(MitmEvent(t, CheckLevel.Bad, S(R.string.the_first_router_changed, a.firstHop, b.firstHop)))
    }

    fun isPrivate(ip: String): Boolean {
        val p = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return ip.startsWith("fe80") || ip.startsWith("fd") || ip.startsWith("fc")
        return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168) || p[0] == 127 || (p[0] == 100 && p[1] in 64..127)
    }
}

/** Reads the active network's link properties and DHCP lease. */
open class LinkReader(private val context: Context) {
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    open fun read(): LinkSnapshot? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val net = cm.activeNetwork ?: return null
        val lp = cm.getLinkProperties(net) ?: return null
        val caps = cm.getNetworkCapabilities(net)
        val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val dhcp = if (wifi) runCatching { context.getSystemService(WifiManager::class.java)?.dhcpInfo?.serverAddress }.getOrNull() else null
        return LinkSnapshot(
            wifi = wifi,
            gateway = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress,
            ipv6Routers = lp.routes.filter { it.isDefaultRoute && it.gateway is Inet6Address && !it.gateway!!.isAnyLocalAddress }
                .mapNotNull { it.gateway?.hostAddress?.substringBefore('%') }.distinct(),
            dns = lp.dnsServers.mapNotNull { it.hostAddress?.substringBefore('%') },
            dhcpServer = dhcp?.takeIf { it != 0 }?.let { "%d.%d.%d.%d".format(it and 0xFF, it shr 8 and 0xFF, it shr 16 and 0xFF, it ushr 24) },
            privateDns = lp.isPrivateDnsActive,
            privateDnsServer = lp.privateDnsServerName,
            validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            captivePortal = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true,
        )
    }
}
