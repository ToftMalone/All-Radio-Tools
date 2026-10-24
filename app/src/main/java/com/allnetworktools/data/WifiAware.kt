package com.allnetworktools.data

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareSession
import android.net.wifi.rtt.RangingRequest
import android.net.wifi.rtt.RangingResult
import android.net.wifi.rtt.RangingResultCallback
import android.net.wifi.rtt.WifiRttManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What this phone's Wi-Fi Aware (NAN) can do. */
data class AwareCaps(
    val supported: Boolean,
    val available: Boolean,
    val rtt: Boolean,
    val maxServiceName: Int?,
    val maxServiceInfo: Int?,
    val publishSessions: Int?,
    val subscribeSessions: Int?,
    val dataPaths: Int?,
    val instantMode: Boolean?,
)

data class AwarePeer(
    val id: Int,
    val name: String,
    val foundMs: Long,
    val lastSeenMs: Long,
    val distanceM: Double? = null,
    val distanceStdM: Double? = null,
    val rssi: Int? = null,
)

data class AwareWave(val from: String, val timeMs: Long)

enum class AwareStatus { Idle, Starting, Running, Unavailable, Failed }

data class AwareState(
    val status: AwareStatus = AwareStatus.Idle,
    val peers: List<AwarePeer> = emptyList(),
    val waves: List<AwareWave> = emptyList(),
    val ranging: Boolean = false,
)

/**
 * Finds the other phones that have this tool open, over Wi-Fi Aware: every phone publishes and subscribes to the same
 * service, carrying its model name. Distances come from Wi-Fi RTT ranging when both phones support it.
 */
@SuppressLint("MissingPermission")
class WifiAwareController(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val aware: WifiAwareManager? = context.getSystemService(WifiAwareManager::class.java)
    private val rtt: WifiRttManager? = context.getSystemService(WifiRttManager::class.java)
    private val pm = context.packageManager

    private val _state = MutableStateFlow(AwareState())
    val state: StateFlow<AwareState> = _state.asStateFlow()

    private var session: WifiAwareSession? = null
    private var publish: PublishDiscoverySession? = null
    private var subscribe: SubscribeDiscoverySession? = null
    private val handles = HashMap<Int, PeerHandle>()
    private var receiver: BroadcastReceiver? = null
    private var running = false
    private var rangeWithLocation = false

    val myName: String = Build.MODEL ?: "Android"

    fun caps(): AwareCaps {
        val supported = pm.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE) && aware != null
        val ch = if (supported) runCatching { aware!!.characteristics }.getOrNull() else null
        val res = if (supported) runCatching { aware!!.availableAwareResources }.getOrNull() else null
        return AwareCaps(
            supported = supported,
            available = supported && aware!!.isAvailable,
            rtt = pm.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT) && rtt != null,
            maxServiceName = ch?.maxServiceNameLength,
            maxServiceInfo = ch?.maxServiceSpecificInfoLength,
            publishSessions = res?.availablePublishSessionsCount,
            subscribeSessions = res?.availableSubscribeSessionsCount,
            dataPaths = res?.availableDataPathsCount,
            instantMode = if (Build.VERSION.SDK_INT >= 33) ch?.isInstantCommunicationModeSupported else null,
        )
    }

    /** Starts discovery; [ranging] when the location permission allows Wi-Fi RTT. */
    fun start(ranging: Boolean) {
        rangeWithLocation = ranging
        if (running) return
        running = true
        val a = aware
        if (a == null || !pm.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) { set(AwareStatus.Unavailable); return }
        // Wi-Fi Aware comes and goes with Wi-Fi, hotspot and location: follow it and reattach.
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (!running) return
                if (a.isAvailable) { if (session == null) attach() } else { detach(); set(AwareStatus.Unavailable) }
            }
        }
        receiver = r
        ContextCompat.registerReceiver(context, r, IntentFilter(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        if (a.isAvailable) attach() else set(AwareStatus.Unavailable)
        handler.post(rangeLoop)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(rangeLoop)
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        detach()
        _state.value = AwareState()
    }

    /** Says hello to a peer: it shows on the other phone, which proves the link works both ways. */
    fun wave(peerId: Int) {
        val h = handles[peerId] ?: return
        val msg = (WAVE + myName).toByteArray(Charsets.UTF_8)
        // A peer handle only works with the session that found it: the subscription.
        runCatching { subscribe?.sendMessage(h, (System.nanoTime() and 0x7FFFFFFF).toInt(), msg) }
    }

    private fun set(s: AwareStatus) { _state.value = _state.value.copy(status = s) }

    private fun attach() {
        set(AwareStatus.Starting)
        runCatching {
            aware?.attach(object : AttachCallback() {
                // Nullable: test shadows (Robolectric) attach without a session.
                override fun onAttached(s: WifiAwareSession?) {
                    if (s == null) { set(AwareStatus.Failed); return }
                    if (!running) { s.close(); return }
                    session = s
                    startSessions(s)
                }

                override fun onAttachFailed() { set(AwareStatus.Failed) }
            }, handler)
        }.onFailure { set(AwareStatus.Failed) }
    }

    private fun startSessions(s: WifiAwareSession) {
        val info = myName.toByteArray(Charsets.UTF_8).let { if (it.size > 200) it.copyOf(200) else it }
        val canRange = rtt != null && pm.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)
        val pub = PublishConfig.Builder().setServiceName(SERVICE).setServiceSpecificInfo(info)
            .apply { if (canRange) setRangingEnabled(true) }.build()
        s.publish(pub, object : DiscoverySessionCallback() {
            override fun onPublishStarted(session: PublishDiscoverySession) { publish = session; set(AwareStatus.Running) }
            override fun onMessageReceived(peer: PeerHandle, message: ByteArray) = onMessage(peer, message)
            override fun onSessionConfigFailed() { set(AwareStatus.Failed) }
        }, handler)
        val sub = SubscribeConfig.Builder().setServiceName(SERVICE).setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE).build()
        s.subscribe(sub, object : DiscoverySessionCallback() {
            override fun onSubscribeStarted(session: SubscribeDiscoverySession) { subscribe = session; set(AwareStatus.Running) }
            override fun onServiceDiscovered(peer: PeerHandle, info: ByteArray?, filter: MutableList<ByteArray>?) {
                val name = info?.let { String(it, Charsets.UTF_8) }?.takeIf { it.isNotBlank() } ?: "Android"
                found(peer, name)
            }
            override fun onServiceLost(peer: PeerHandle, reason: Int) { lost(peer) }
            override fun onMessageReceived(peer: PeerHandle, message: ByteArray) = onMessage(peer, message)
        }, handler)
    }

    private fun detach() {
        publish?.close(); subscribe?.close(); session?.close()
        publish = null; subscribe = null; session = null
        handles.clear()
    }

    private fun found(peer: PeerHandle, name: String) {
        val id = peer.hashCode()
        handles[id] = peer
        val now = System.currentTimeMillis()
        val peers = _state.value.peers
        val prev = peers.firstOrNull { it.id == id }
        val p = prev?.copy(name = name, lastSeenMs = now) ?: AwarePeer(id, name, now, now)
        _state.value = _state.value.copy(peers = (peers.filter { it.id != id } + p).sortedBy { it.foundMs })
    }

    private fun lost(peer: PeerHandle) {
        val id = peer.hashCode()
        handles.remove(id)
        _state.value = _state.value.copy(peers = _state.value.peers.filter { it.id != id })
    }

    private fun onMessage(peer: PeerHandle, message: ByteArray) {
        val text = String(message, Charsets.UTF_8)
        if (!text.startsWith(WAVE)) return
        val from = text.removePrefix(WAVE).ifBlank { "Android" }
        _state.value = _state.value.copy(waves = (listOf(AwareWave(from, System.currentTimeMillis())) + _state.value.waves).take(20))
    }

    /** Ranges every peer every two seconds; a peer that answers is alive, one silent for a minute is dropped. */
    private val rangeLoop = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.currentTimeMillis()
            val stale = _state.value.peers.filter { now - it.lastSeenMs > 60_000 && it.distanceM != null }
            stale.forEach { handles.remove(it.id) }
            if (stale.isNotEmpty()) _state.value = _state.value.copy(peers = _state.value.peers - stale.toSet())
            range()
            handler.postDelayed(this, 2_000)
        }
    }

    private fun range() {
        val r = rtt ?: return
        if (!rangeWithLocation || !r.isAvailable || handles.isEmpty()) {
            if (_state.value.ranging) _state.value = _state.value.copy(ranging = false)
            return
        }
        val req = RangingRequest.Builder().apply { handles.values.take(RangingRequest.getMaxPeers()).forEach { addWifiAwarePeer(it) } }.build()
        runCatching {
            r.startRanging(req, context.mainExecutor, object : RangingResultCallback() {
                override fun onRangingFailure(code: Int) {}
                override fun onRangingResults(results: MutableList<RangingResult>) {
                    val now = System.currentTimeMillis()
                    var peers = _state.value.peers
                    results.filter { it.status == RangingResult.STATUS_SUCCESS }.forEach { res ->
                        val id = res.peerHandle?.hashCode() ?: return@forEach
                        peers = peers.map {
                            if (it.id != id) it else it.copy(
                                lastSeenMs = now, distanceM = res.distanceMm / 1000.0, distanceStdM = res.distanceStdDevMm / 1000.0, rssi = res.rssi,
                            )
                        }
                    }
                    _state.value = _state.value.copy(peers = peers, ranging = true)
                }
            })
        }
    }

    companion object {
        /** Service every phone running the tool publishes and subscribes to. */
        const val SERVICE = "allradiotools"
        private const val WAVE = "wave:"
    }
}
