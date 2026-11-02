package com.allnetworktools.data.net

import android.net.Network
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.net.URLConnection

/** The two ways out to the Internet a tool can be tied to. */
enum class Transport { Wifi, Cellular }

/**
 * A way out to the Internet: the system's own choice ([System]), or one given network that every socket, lookup and
 * request of a tool is bound to, whatever the phone would use by default (Wi-Fi tools never leave through mobile
 * data, mobile data tools never through Wi-Fi).
 */
class NetRoute(
    /** The network sockets are bound to; null follows the system's default network. */
    val network: Network?,
    val transport: Transport? = null,
    /** Name of the network interface (wlan0, rmnet_data0…), for the system `ping`. */
    val iface: String? = null,
    /** The network's own DNS servers. */
    val dns: List<InetAddress> = emptyList(),
    /** True when the system already routes everything this way: the plain `ping` binary then goes the right way. */
    val isDefault: Boolean = true,
    /** False on an unmetered network: big transfers are fine. */
    val metered: Boolean = true,
) {
    /** Set once the system `ping` has refused to bind to [iface]: later probes go straight to an ICMP socket. */
    @Volatile var binaryCannotBind = false

    fun socket(): Socket = network?.socketFactory?.createSocket() ?: Socket()

    fun datagram(): DatagramSocket = DatagramSocket().also { network?.bindSocket(it) }

    fun open(url: URL): URLConnection = network?.openConnection(url) ?: url.openConnection()

    fun resolveAll(host: String): Array<InetAddress> = network?.getAllByName(host.trim()) ?: InetAddress.getAllByName(host.trim())

    fun resolve(host: String): InetAddress = resolveAll(host).first()

    companion object {
        /** No binding: whatever the system routes by default, as the tools always did. */
        val System = NetRoute(null)
    }
}

/** Hands a tool the route it must use, or nothing when that network is not there. */
interface RouteSource {
    /** Runs [block] on the route; null (and [block] not run) when the network is missing. The route stays up meanwhile. */
    suspend fun <T> use(block: suspend (NetRoute) -> T): T?

    /** The system's default network: how the tools always worked. */
    object System : RouteSource {
        override suspend fun <T> use(block: suspend (NetRoute) -> T): T? = block(NetRoute.System)
    }
}
