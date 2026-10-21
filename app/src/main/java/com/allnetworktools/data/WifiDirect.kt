package com.allnetworktools.data

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import com.allnetworktools.R
import com.allnetworktools.util.S

enum class P2pStatus(val label: String) { Available(S(R.string.available)), Invited(S(R.string.invited)), Connected(S(R.string.connected)), Failed(S(R.string.failed)), Unavailable(S(R.string.unavailable)) }

data class P2pPeer(
    val name: String,
    val address: String,
    /** "10-0050F204-5" style WSC primary device type. */
    val deviceType: String?,
    val status: P2pStatus,
    val groupOwner: Boolean,
    val serviceDiscovery: Boolean,
)

data class P2pCategory(val label: String, val icon: String)

object WifiDirect {
    /**
     * Category of a WSC primary device type "category-OUI-subcategory" (Wi-Fi Simple Configuration Technical
     * Specification, Primary Device Type table). Only the Wi-Fi Alliance OUI 0050F204 uses these meanings.
     */
    fun category(deviceType: String?): P2pCategory? {
        val parts = deviceType?.split('-') ?: return null
        if (parts.size != 3 || !parts[1].equals("0050F204", ignoreCase = true)) return null
        val cat = parts[0].toIntOrNull() ?: return null
        val sub = parts[2].toIntOrNull() ?: 0
        val s = com.allnetworktools.ui.theme.Sym
        return when (cat) {
            1 -> P2pCategory(if (sub == 5) S(R.string.laptop) else S(R.string.computer), s.Devices)
            2 -> P2pCategory(S(R.string.input_device), s.Keyboard)
            3 -> P2pCategory(if (sub == 2) "Scanner" else S(R.string.printer), s.Print)
            4 -> P2pCategory(S(R.string.camera), s.PhotoCamera)
            5 -> P2pCategory(S(R.string.storage_nas), s.Devices)
            6 -> P2pCategory(S(R.string.network_equipment), s.Router)
            7 -> P2pCategory(if (sub == 1) S(R.string.television) else S(R.string.display), s.ConnectedTv)
            8 -> P2pCategory(S(R.string.multimedia_device), s.Cast)
            9 -> P2pCategory(S(R.string.game_console), s.Devices)
            10 -> P2pCategory(if (sub == 5) "Smartphone" else S(R.string.phone), s.Smartphone)
            11 -> P2pCategory(S(R.string.audio_device), s.VolumeUp)
            else -> null
        }
    }

    fun status(code: Int) = when (code) {
        WifiP2pDevice.AVAILABLE -> P2pStatus.Available
        WifiP2pDevice.INVITED -> P2pStatus.Invited
        WifiP2pDevice.CONNECTED -> P2pStatus.Connected
        WifiP2pDevice.FAILED -> P2pStatus.Failed
        else -> P2pStatus.Unavailable
    }
}

/** Wi-Fi Direct peer discovery; runs only while collected (the Wi-Fi Direct tool is open). */
open class WifiDirectRepository(private val context: Context) {
    private val manager: WifiP2pManager? = context.getSystemService(WifiP2pManager::class.java)

    open val supported: Boolean get() = manager != null

    /** Emits the current peer list, or throws [SecurityException] when the permission is missing. */
    @SuppressLint("MissingPermission")
    open fun peers(): Flow<List<P2pPeer>> = callbackFlow {
        val m = manager ?: run { close(); return@callbackFlow }
        val channel = m.initialize(context, Looper.getMainLooper(), null)
        fun refresh() = m.requestPeers(channel) { list ->
            trySend(
                list.deviceList.map { d ->
                    P2pPeer(
                        name = d.deviceName?.takeIf { it.isNotBlank() } ?: S(R.string.unnamed),
                        address = d.deviceAddress ?: "—",
                        deviceType = d.primaryDeviceType,
                        status = WifiDirect.status(d.status),
                        groupOwner = d.isGroupOwner,
                        serviceDiscovery = d.isServiceDiscoveryCapable,
                    )
                }.sortedBy { it.name.lowercase() },
            )
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = refresh()
        }
        val filter = IntentFilter(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        // Discovery stops on its own after about two minutes, so it is restarted periodically.
        val loop = launch {
            while (true) {
                m.discoverPeers(channel, null)
                delay(30_000)
            }
        }
        refresh()
        awaitClose {
            loop.cancel()
            runCatching { m.stopPeerDiscovery(channel, null) }
            runCatching { context.unregisterReceiver(receiver) }
            channel.close()
        }
    }
}
