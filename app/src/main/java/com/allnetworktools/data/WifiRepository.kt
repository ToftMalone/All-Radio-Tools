package com.allnetworktools.data

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import java.net.Inet4Address
import java.net.Inet6Address
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import com.allnetworktools.R
import com.allnetworktools.util.S

enum class WifiBand(val label: String) { B24("2.4"), B5("5"), B6("6") }

data class WifiConnection(
    val ssid: String?,
    val bssid: String?,
    val rssi: Int,
    val frequency: Int,
    val linkTx: Int?,
    val linkRx: Int?,
    val standard: Int,
    val security: String?,
    val ipv4: String?,
    val prefix: Int?,
    val gateway: String?,
    val dns: List<String>,
    val ipv6: String?,
    val connectedAtElapsed: Long?,
) {
    val band: WifiBand get() = bandOf(frequency)
    val channel: Int get() = channelOf(frequency)
}

/** Live values read straight from the Wi-Fi stack; the BSSID changes when the device roams to another AP. */
data class WifiPoll(val rssi: Int, val tx: Int, val rx: Int, val bssid: String?, val frequency: Int?)

data class WifiAp(
    val ssid: String,
    val bssid: String,
    val rssi: Int,
    val frequency: Int,
    val widthMhz: Int,
    val security: String,
    val standard: Int,
    val centerFrequency: Int,
    /** Raw capability string of the scan result ("[RSN-PSK-CCMP][MFPC][WPS][ESS]"). */
    val caps: String = "",
) {
    val band: WifiBand get() = bandOf(frequency)
    val channel: Int get() = channelOf(frequency)
}

fun bandOf(freq: Int): WifiBand = when {
    freq >= 5925 -> WifiBand.B6
    freq >= 4900 -> WifiBand.B5
    else -> WifiBand.B24
}

fun channelOf(freq: Int): Int = when {
    freq == 2484 -> 14
    freq in 2412..2472 -> (freq - 2407) / 5
    freq >= 5955 -> (freq - 5950) / 5
    freq in 4900..5899 -> (freq - 5000) / 5
    else -> 0
}

fun unii(freq: Int): String? = when (freq) {
    in 5150..5250 -> "UNII-1"
    in 5250..5350 -> "UNII-2A"
    in 5470..5730 -> "UNII-2C"
    in 5735..5895 -> "UNII-3"
    in 5925..6425 -> "UNII-5"
    in 6425..6525 -> "UNII-6"
    in 6525..6875 -> "UNII-7"
    in 6875..7125 -> "UNII-8"
    else -> null
}

fun standardLabel(standard: Int): Pair<String, String>? = when (standard) {
    ScanResult.WIFI_STANDARD_LEGACY -> "Wi-Fi" to "802.11a/b/g"
    ScanResult.WIFI_STANDARD_11N -> "Wi-Fi 4" to "802.11n"
    ScanResult.WIFI_STANDARD_11AC -> "Wi-Fi 5" to "802.11ac"
    ScanResult.WIFI_STANDARD_11AX -> "Wi-Fi 6" to "802.11ax"
    ScanResult.WIFI_STANDARD_11BE -> "Wi-Fi 7" to "802.11be"
    ScanResult.WIFI_STANDARD_11AD -> "WiGig" to "802.11ad"
    else -> null
}

fun widthOf(channelWidth: Int): Int = when (channelWidth) {
    ScanResult.CHANNEL_WIDTH_40MHZ -> 40
    ScanResult.CHANNEL_WIDTH_80MHZ -> 80
    ScanResult.CHANNEL_WIDTH_160MHZ, ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> 160
    ScanResult.CHANNEL_WIDTH_320MHZ -> 320
    else -> 20
}

private fun securityOfCapabilities(caps: String): String = when {
    "EAP" in caps && ("SAE" in caps || "SUITE_B" in caps) -> "WPA3-EAP"
    "EAP" in caps -> "WPA2-EAP"
    "SAE" in caps && "PSK" in caps -> "WPA2/3"
    "SAE" in caps -> "WPA3"
    "OWE" in caps -> "OWE"
    "WPA2" in caps || "RSN" in caps -> "WPA2"
    "WPA" in caps -> "WPA"
    "WEP" in caps -> "WEP"
    else -> S(R.string.open)
}

private fun securityOfType(type: Int): String? = when (type) {
    WifiInfo.SECURITY_TYPE_OPEN -> S(R.string.open)
    WifiInfo.SECURITY_TYPE_WEP -> "WEP"
    WifiInfo.SECURITY_TYPE_PSK -> S(R.string.wpa2_personal_psk)
    WifiInfo.SECURITY_TYPE_EAP, WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE -> S(R.string.wpa2_3_enterprise_eap)
    WifiInfo.SECURITY_TYPE_SAE -> S(R.string.wpa3_personal_sae)
    WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT -> S(R.string.wpa3_enterprise_192_bit)
    WifiInfo.SECURITY_TYPE_OWE -> "Enhanced Open (OWE)"
    WifiInfo.SECURITY_TYPE_WAPI_PSK, WifiInfo.SECURITY_TYPE_WAPI_CERT -> "WAPI"
    else -> null
}

fun shortSecurity(full: String?): String = when {
    full == null -> "—"
    "SAE" in full -> "WPA3"
    "PSK" in full -> "WPA2"
    else -> full.substringBefore(" ").substringBefore("-Pers")
}

private fun cleanSsid(raw: String?): String? =
    raw?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && it != WifiManager.UNKNOWN_SSID.removeSurrounding("\"") && it != "<unknown ssid>" }

open class WifiRepository(context: Context) {
    private val wifi = context.getSystemService(WifiManager::class.java)
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    /** Current Wi-Fi connection, or null when not associated. */
    open val connection: Flow<WifiConnection?> = callbackFlow {
        var info: WifiInfo? = null
        var link: LinkProperties? = null
        var since: Long? = null
        fun emit() {
            val i = info
            if (i == null) {
                trySend(null); return
            }
            val v4 = link?.linkAddresses?.firstOrNull { it.address is Inet4Address }
            val v6 = link?.linkAddresses?.firstOrNull { it.address is Inet6Address && !it.address.isLinkLocalAddress }
            trySend(
                WifiConnection(
                    ssid = cleanSsid(i.ssid),
                    bssid = i.bssid?.takeIf { it != "02:00:00:00:00:00" },
                    rssi = i.rssi,
                    frequency = i.frequency,
                    linkTx = i.txLinkSpeedMbps.takeIf { it > 0 },
                    linkRx = i.rxLinkSpeedMbps.takeIf { it > 0 },
                    standard = i.wifiStandard,
                    security = securityOfType(i.currentSecurityType),
                    ipv4 = v4?.address?.hostAddress,
                    prefix = v4?.prefixLength,
                    gateway = link?.routes?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress,
                    dns = link?.dnsServers?.mapNotNull { it.hostAddress }.orEmpty(),
                    ipv6 = v6?.address?.hostAddress?.substringBefore('%'),
                    connectedAtElapsed = since,
                ),
            )
        }
        val callback = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
            override fun onAvailable(network: Network) {
                if (since == null) since = SystemClock.elapsedRealtime()
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                info = caps.transportInfo as? WifiInfo
                emit()
            }

            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                link = lp
                emit()
            }

            override fun onLost(network: Network) {
                info = null; link = null; since = null
                emit()
            }
        }
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        cm.registerNetworkCallback(request, callback)
        trySend(null)
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }

    /** Latest RSSI and link speeds, read directly since capability callbacks are coarse. */
    @Suppress("DEPRECATION")
    open fun pollRssi(): WifiPoll? {
        val i = wifi?.connectionInfo ?: return null
        if (i.networkId == -1 && i.rssi <= -127) return null
        return WifiPoll(i.rssi, i.txLinkSpeedMbps, i.rxLinkSpeedMbps, i.bssid?.takeIf { it != "02:00:00:00:00:00" }, i.frequency.takeIf { it > 0 })
    }

    private val _scanTimes = ArrayDeque<Long>()
    private val _throttledUntil = MutableStateFlow(0L)

    /** Elapsed-realtime until which Android will refuse new scans (4 per 2 min in foreground). */
    open val throttledUntil: StateFlow<Long> = _throttledUntil.asStateFlow()

    /** Asks for a scan; returns false if Android throttled it. */
    @Suppress("DEPRECATION")
    open fun startScan(): Boolean {
        val now = SystemClock.elapsedRealtime()
        while (_scanTimes.isNotEmpty() && now - _scanTimes.first() > 120_000) _scanTimes.removeFirst()
        val ok = runCatching { wifi?.startScan() == true }.getOrDefault(false)
        if (ok) {
            _scanTimes.addLast(now)
            _throttledUntil.value = 0L
        } else {
            val oldest = _scanTimes.firstOrNull() ?: now
            _throttledUntil.value = oldest + 120_000
        }
        return ok
    }

    /** Scan results as they are delivered by the system, with their age. */
    @SuppressLint("MissingPermission")
    open val scanResults: Flow<List<WifiAp>> = callbackFlow {
        val mgr = wifi ?: run { trySend(emptyList()); awaitClose { }; return@callbackFlow }
        fun read(): List<WifiAp> = runCatching { mgr.scanResults }.getOrDefault(emptyList()).map { r ->
            val ssid = if (Build.VERSION.SDK_INT >= 33) r.wifiSsid?.toString()?.removeSurrounding("\"") else @Suppress("DEPRECATION") r.SSID
            WifiAp(
                ssid = ssid?.takeIf { it.isNotBlank() } ?: S(R.string.hidden_network),
                bssid = r.BSSID ?: "",
                rssi = r.level,
                frequency = r.frequency,
                widthMhz = widthOf(r.channelWidth),
                security = securityOfCapabilities(r.capabilities ?: ""),
                standard = r.wifiStandard,
                centerFrequency = r.centerFreq0.takeIf { it > 0 } ?: r.frequency,
                caps = r.capabilities.orEmpty(),
            )
        }.sortedByDescending { it.rssi }
        val cb = object : WifiManager.ScanResultsCallback() {
            override fun onScanResultsAvailable() {
                trySend(read())
            }
        }
        mgr.registerScanResultsCallback(Runnable::run, cb)
        trySend(read())
        awaitClose { mgr.unregisterScanResultsCallback(cb) }
    }
}
