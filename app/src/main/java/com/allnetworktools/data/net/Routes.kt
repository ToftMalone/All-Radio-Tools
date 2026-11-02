package com.allnetworktools.data.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The networks the Internet tools may use. Wi-Fi tools go through the connected Wi-Fi network and nothing else; the
 * mobile data tools ask Android for the cellular network by name, which keeps it up (and usable) even while Wi-Fi is
 * what the phone browses with.
 */
class Routes(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    /** The connected Wi-Fi network (a VPN is not it), or null. */
    fun wifi(): NetRoute? {
        val n = cm.allNetworks.firstOrNull {
            val c = cm.getNetworkCapabilities(it)
            c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } ?: return null
        return describe(n, Transport.Wifi, cm.getLinkProperties(n), cm.getNetworkCapabilities(n))
    }

    /** Tools that must leave through Wi-Fi. */
    val wifiSource: RouteSource = object : RouteSource {
        override suspend fun <T> use(block: suspend (NetRoute) -> T): T? = wifi()?.let { block(it) }
    }

    /** Tools that must leave through mobile data, even with Wi-Fi connected. */
    val cellularSource: RouteSource = object : RouteSource {
        override suspend fun <T> use(block: suspend (NetRoute) -> T): T? {
            hold()
            try {
                val route = withTimeoutOrNull(CELL_WAIT_MS) { cell.filterNotNull().first() } ?: return null
                return block(route)
            } finally {
                release()
            }
        }
    }

    private val lock = Any()
    private var holders = 0
    private var callback: ConnectivityManager.NetworkCallback? = null
    private val cell = MutableStateFlow<NetRoute?>(null)

    private fun hold() = synchronized(lock) { if (holders++ == 0) register() }

    private fun release() = synchronized(lock) {
        if (--holders == 0) {
            callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
            callback = null
            cell.value = null
        }
    }

    private fun register() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cell.value = describe(network, Transport.Cellular, cm.getLinkProperties(network), cm.getNetworkCapabilities(network))
            }

            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                cell.value = describe(network, Transport.Cellular, lp, cm.getNetworkCapabilities(network))
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                cell.value = describe(network, Transport.Cellular, cm.getLinkProperties(network), caps)
            }

            override fun onLost(network: Network) {
                if (cell.value?.network == network) cell.value = null
            }
        }
        callback = cb
        runCatching { cm.requestNetwork(request, cb) }.onFailure { callback = null }
    }

    private fun describe(n: Network, transport: Transport, lp: LinkProperties?, caps: NetworkCapabilities?) = NetRoute(
        network = n,
        transport = transport,
        iface = lp?.interfaceName,
        dns = lp?.dnsServers.orEmpty(),
        isDefault = cm.activeNetwork == n,
        metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true,
    )

    private companion object {
        /** How long mobile data gets to come up before the tool says it is not there. */
        const val CELL_WAIT_MS = 10_000L
    }
}
