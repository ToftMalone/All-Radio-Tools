package com.allnetworktools.data.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.allnetworktools.ui.theme.Sym
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.allnetworktools.R
import com.allnetworktools.util.S

enum class LanCategory(val label: String) { Network(S(R.string.network)), Computers(S(R.string.computers)), Media(S(R.string.multimedia)), Home(S(R.string.smart_home)), Other(S(R.string.others)) }

data class LanDevice(
    val ip: String,
    val hostname: String? = null,
    val mdnsName: String? = null,
    val services: Set<String> = emptySet(),
    val openPorts: Set<Int> = emptySet(),
    val ms: Float? = null,
    val isSelf: Boolean = false,
    val isGateway: Boolean = false,
) {
    val lastOctet: Int get() = ip.substringAfterLast('.').toIntOrNull() ?: 0

    val name: String get() = when {
        isSelf -> android.os.Build.MODEL
        mdnsName != null -> mdnsName
        hostname != null -> hostname.substringBefore('.')
        isGateway -> S(R.string.gateway)
        else -> S(R.string.unknown_device)
    }

    private fun has(vararg types: String) = types.any { t -> services.any { it.startsWith(t) } }
    private val host get() = (hostname.orEmpty() + " " + mdnsName.orEmpty()).lowercase()

    /** icon, type label, category */
    val kind: Triple<String, String, LanCategory> get() = when {
        isGateway -> Triple(Sym.Router, S(R.string.gateway), LanCategory.Network)
        isSelf -> Triple(Sym.Smartphone, S(R.string.this_device), LanCategory.Computers)
        has("_googlecast") -> Triple(Sym.Cast, S(R.string.media_player), LanCategory.Media)
        has("_airplay", "_raop") || "tv" in host -> Triple(Sym.Tv, S(R.string.tv_airplay), LanCategory.Media)
        has("_spotify-connect", "_sonos") -> Triple(Sym.Speaker, S(R.string.speaker), LanCategory.Media)
        has("_ipp", "_printer", "_pdl-datastream") || 9100 in openPorts || 631 in openPorts -> Triple(Sym.Print, S(R.string.printer), LanCategory.Computers)
        has("_hap", "_hue", "_matter", "_home-assistant", "_esphomelib") || 8123 in openPorts || 1883 in openPorts -> Triple(Sym.Lightbulb, S(R.string.home_automation), LanCategory.Home)
        listOf("ps4", "ps5", "xbox", "switch", "playstation").any { it in host } -> Triple(Sym.Gamepad, "Console", LanCategory.Media)
        has("_smb", "_afpovertcp", "_nfs") && (5000 in openPorts || 5001 in openPorts || "nas" in host || "synology" in host) -> Triple(Sym.Storage, S(R.string.network_storage), LanCategory.Computers)
        62078 in openPorts || listOf("iphone", "ipad", "android", "galaxy", "pixel", "redmi", "oneplus").any { it in host } -> Triple(Sym.Smartphone, S(R.string.phone_tablet), LanCategory.Computers)
        has("_companion-link", "_workstation", "_ssh", "_sftp") || 3389 in openPorts || 22 in openPorts ||
            listOf("macbook", "imac", "laptop", "desktop", "pc-").any { it in host } -> Triple(Sym.Laptop, S(R.string.computer), LanCategory.Computers)
        445 in openPorts || 139 in openPorts -> Triple(Sym.Laptop, S(R.string.computer_smb), LanCategory.Computers)
        listOf("esp", "tasmota", "shelly", "tuya", "sonoff").any { it in host } -> Triple(Sym.Memory, S(R.string.iot_module), LanCategory.Home)
        else -> Triple(Sym.DevicesOther, S(R.string.device_2), LanCategory.Other)
    }
}

data class LanProgress(val scanned: Int, val total: Int, val devices: List<LanDevice>, val done: Boolean)

/** Hosts of the /24 (or smaller) subnet around [ip]. */
fun subnetHosts(ip: String, prefix: Int): List<String> {
    val parts = ip.split('.').map { it.toInt() }
    val p = prefix.coerceIn(24, 30)
    val self = (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
    val mask = (-1 shl (32 - p))
    val net = self and mask
    val count = (1 shl (32 - p)) - 2
    return (1..count).map { i ->
        val v = net + i
        "${(v ushr 24) and 255}.${(v ushr 16) and 255}.${(v ushr 8) and 255}.${v and 255}"
    }
}

class LanScanner(private val context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)

    private val serviceTypes = listOf(
        "_googlecast._tcp", "_airplay._tcp", "_raop._tcp", "_spotify-connect._tcp", "_ipp._tcp", "_printer._tcp",
        "_pdl-datastream._tcp", "_hap._tcp", "_hue._tcp", "_matter._tcp", "_home-assistant._tcp", "_smb._tcp",
        "_afpovertcp._tcp", "_companion-link._tcp", "_workstation._tcp", "_ssh._tcp", "_http._tcp", "_sonos._tcp", "_esphomelib._tcp",
    )

    /** mDNS announcements seen during the scan: ip → (service name, types). */
    private fun mdns(): Flow<Pair<String, Pair<String, String>>> = callbackFlow {
        val mgr = nsd ?: run { awaitClose { }; return@callbackFlow }
        val resolveLock = Mutex()
        val scope = this
        val listeners = serviceTypes.map { type ->
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                override fun onServiceLost(info: NsdServiceInfo) = Unit
                override fun onServiceFound(info: NsdServiceInfo) {
                    scope.launch {
                        resolveLock.withLock { resolve(mgr, info) }?.let { (ip, name) -> trySend(ip to (name to type)) }
                    }
                }
            }
        }
        listeners.forEachIndexed { i, l -> runCatching { mgr.discoverServices(serviceTypes[i], NsdManager.PROTOCOL_DNS_SD, l) } }
        awaitClose { listeners.forEach { runCatching { mgr.stopServiceDiscovery(it) } } }
    }

    /** NsdManager resolves one service at a time, hence the caller's lock. */
    @Suppress("DEPRECATION")
    private suspend fun resolve(mgr: NsdManager, info: NsdServiceInfo): Pair<String, String>? = withTimeoutOrNull(3000) {
        suspendCancellableCoroutine { cont ->
            mgr.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(s: NsdServiceInfo, errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }

                override fun onServiceResolved(s: NsdServiceInfo) {
                    val host = s.host
                    if (cont.isActive) cont.resume(if (host is Inet4Address) host.hostAddress!! to s.serviceName else null)
                }
            })
        }
    }

    /** Sweeps [hosts] with ICMP/TCP probes while listening to mDNS, then resolves hostnames. */
    fun scan(hosts: List<String>, selfIp: String, gateway: String?): Flow<LanProgress> = callbackFlow {
        val found = ConcurrentHashMap<String, LanDevice>()
        val mdnsInfo = ConcurrentHashMap<String, Pair<String, MutableSet<String>>>()
        var scanned = 0
        fun snapshot(done: Boolean) = LanProgress(scanned, hosts.size, found.values.sortedBy { it.lastOctet }, done)

        val mdnsJob = launch {
            mdns().collect { (ip, info) ->
                val (name, type) = info
                val entry = mdnsInfo.getOrPut(ip) { name to mutableSetOf() }
                entry.second += type
                val prev = found[ip] ?: LanDevice(ip, isSelf = ip == selfIp, isGateway = ip == gateway)
                found[ip] = prev.copy(mdnsName = prev.mdnsName ?: name, services = entry.second.toSet())
                trySend(snapshot(false))
            }
        }
        found[selfIp] = LanDevice(selfIp, isSelf = true)
        val sem = Semaphore(48)
        coroutineScope {
            hosts.filter { it != selfIp }.map { ip ->
                async {
                    sem.withPermit {
                        val probe = probeHost(ip)
                        if (probe != null) {
                            val (ms, ports) = probe
                            val prev = found[ip] ?: LanDevice(ip, isGateway = ip == gateway)
                            found[ip] = prev.copy(ms = ms, openPorts = prev.openPorts + ports)
                        }
                        synchronized(this@callbackFlow) { scanned++ }
                        trySend(snapshot(false))
                    }
                }
            }.awaitAll()
        }
        // Reverse DNS for everything that answered.
        coroutineScope {
            found.values.map { d ->
                async {
                    val name = Net.reverse(InetAddress.getByName(d.ip), 1500)
                    if (name != null && name != d.ip) found[d.ip] = found.getValue(d.ip).copy(hostname = name)
                }
            }.awaitAll()
        }
        delay(1500)
        mdnsJob.cancel()
        trySend(snapshot(true))
        close()
        awaitClose { mdnsJob.cancel() }
    }

    private val aliveProbePorts = listOf(80, 443, 22, 445, 139, 62078, 8080, 8008, 7000, 9100)

    /** Returns (latency, open ports) if the host answered anything, else null. */
    private suspend fun probeHost(ip: String): Pair<Float, Set<Int>>? = withContext(Dispatchers.IO) {
        val addr = InetAddress.getByName(ip)
        val t0 = System.nanoTime()
        val reachable = withTimeoutOrNull(900) { runInterruptible { addr.isReachable(700) } } == true
        val icmpMs = (System.nanoTime() - t0) / 1e6f
        val results = coroutineScope { aliveProbePorts.map { p -> async { p to Net.tcp(addr, p, 450) } }.awaitAll() }
        val open = results.filter { it.second.state == Net.Tcp.Open }.map { it.first }.toSet()
        val answered = results.filter { it.second.state == Net.Tcp.Open || it.second.state == Net.Tcp.Closed }
        when {
            reachable -> icmpMs to open
            answered.isNotEmpty() -> answered.minOf { it.second.ms } to open
            else -> null
        }
    }

    suspend fun isAlive(ip: String): Float? = probeHost(ip)?.first

    /** Probes [ports] on one device, reading banners of the open ones. */
    suspend fun services(ip: String, ports: List<Int>): List<Pair<Int, String?>> = coroutineScope {
        val addr = withContext(Dispatchers.IO) { InetAddress.getByName(ip) }
        ports.map { p ->
            async {
                var banner: String? = null
                val r = Net.tcp(addr, p, 800) { s -> banner = Net.grabBanner(s, p, ip) }
                if (r.state == Net.Tcp.Open) p to banner else null
            }
        }.awaitAll().filterNotNull().sortedBy { it.first }
    }
}
