package com.allnetworktools.data.remoteid

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.allnetworktools.MainActivity
import com.allnetworktools.R
import com.allnetworktools.util.S
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RemoteIdState(
    val running: Boolean = false,
    val drones: List<Drone> = emptyList(),
    val longRange: Boolean = false,
    val bluetoothOff: Boolean = false,
    /** Wi-Fi beacons are being scanned, and Wi-Fi Aware is listened to. */
    val wifi: Boolean = false,
    val nan: Boolean = false,
)

/** Bridge between the background detector and the screen. */
object RemoteIdHub {
    private val _state = MutableStateFlow(RemoteIdState())
    val state: StateFlow<RemoteIdState> = _state.asStateFlow()

    internal val tracker = DroneTracker()

    /** The tool is on screen: scan harder and do not raise alerts for what the user is already watching. */
    @Volatile
    var watching = false
        set(v) { field = v; onWatching?.invoke() }
    internal var onWatching: (() -> Unit)? = null

    internal fun publish(s: RemoteIdState) { _state.value = s }

    fun start(context: Context) {
        ContextCompat.startForegroundService(context, Intent(context, RemoteIdService::class.java))
    }

    fun stop(context: Context) {
        context.startService(Intent(context, RemoteIdService::class.java).setAction(RemoteIdService.ACTION_STOP))
    }

    fun clear() {
        tracker.clear()
        _state.value = _state.value.copy(drones = emptyList())
    }
}

/** Foreground service listening for drone Remote ID broadcasts, also with the app closed, until stopped. */
@SuppressLint("MissingPermission")
class RemoteIdService : Service() {
    private var scope: CoroutineScope? = null
    private var scanning = false
    private var lowLatency = false
    private val alerted = HashSet<String>()
    private var lastSummary = ""

    @Volatile
    private var closing = false

    private val adapter get() = getSystemService(BluetoothManager::class.java)?.adapter

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val ok = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
        if (!ok) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, ongoing(0), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        if (scope == null) {
            val sc = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            scope = sc
            startScan()
            startWifi(sc)
            RemoteIdHub.onWatching = { if (RemoteIdHub.watching != lowLatency) { stopScan(); startScan() } }
            sc.launch {
                while (true) {
                    publish()
                    delay(1_000)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        closing = true
        scope?.cancel(); scope = null
        RemoteIdHub.onWatching = null
        stopScan()
        stopWifi()
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        RemoteIdHub.publish(RemoteIdHub.state.value.copy(running = false))
        super.onDestroy()
    }

    // ---- scanning ------------------------------------------------------------------------------------

    private fun startScan() {
        val a = adapter
        val scanner = a?.bluetoothLeScanner
        if (a == null || !a.isEnabled || scanner == null) {
            RemoteIdHub.publish(RemoteIdHub.state.value.copy(running = true, bluetoothOff = true))
            return
        }
        lowLatency = RemoteIdHub.watching
        // Filtered on the Remote ID service data, so Android keeps delivering results with the screen off.
        val filter = ScanFilter.Builder()
            .setServiceData(ParcelUuid.fromString(RemoteId.SERVICE_UUID), byteArrayOf(RemoteId.APP_CODE.toByte()), byteArrayOf(0xFF.toByte()))
            .build()
        // Bluetooth 5 long range (coded PHY) carries Remote ID several times further than legacy advertising.
        val longRange = a.isLeCodedPhySupported && a.isLeExtendedAdvertisingSupported
        val settings = ScanSettings.Builder()
            .setScanMode(if (lowLatency) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_BALANCED)
            .apply { if (a.isLeExtendedAdvertisingSupported) { setLegacy(false); setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED) } }
            .build()
        scanning = runCatching { scanner.startScan(listOf(filter), settings, callback) }.isSuccess
        RemoteIdHub.publish(RemoteIdHub.state.value.copy(running = true, bluetoothOff = false, longRange = longRange))
    }

    private fun stopScan() {
        if (scanning) runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
        scanning = false
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)
    }

    private fun handle(r: ScanResult) {
        val data = r.scanRecord?.getServiceData(ParcelUuid.fromString(RemoteId.SERVICE_UUID)) ?: return
        val msgs = RemoteId.parseServiceData(data)
        RemoteIdHub.tracker.add(r.device.address, msgs, r.rssi, System.currentTimeMillis())
    }

    // ---- Wi-Fi: beacons and Wi-Fi Aware ---------------------------------------------------------------

    private val wifi get() = applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
    private var wifiReceiver: android.content.BroadcastReceiver? = null
    private var aware: android.net.wifi.aware.WifiAwareSession? = null

    private fun startWifi(sc: CoroutineScope) {
        val wm = wifi ?: return
        // Every scan result, ours or the system's, is searched for the Remote ID vendor element.
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = readWifi()
        }
        wifiReceiver = r
        ContextCompat.registerReceiver(this, r, android.content.IntentFilter(android.net.wifi.WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        RemoteIdHub.publish(RemoteIdHub.state.value.copy(wifi = true))
        // Android allows a foreground service four scans every two minutes.
        sc.launch {
            while (true) {
                @Suppress("DEPRECATION")
                runCatching { wm.startScan() }
                delay(30_000)
            }
        }
        startAware()
    }

    private fun readWifi() {
        val list = runCatching { wifi?.scanResults }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        list.forEach { s ->
            s.informationElements.forEach { ie ->
                if (ie.id == 221) {
                    val body = ByteArray(ie.bytes.remaining()).also { ie.bytes.duplicate().get(it) }
                    val msgs = RemoteId.parseWifiVendorIe(body)
                    if (msgs.isNotEmpty()) RemoteIdHub.tracker.add("wifi:" + s.BSSID, msgs, s.level, now, Via.WifiBeacon)
                }
            }
        }
    }

    /** Wi-Fi Aware (NAN): some Remote ID modules broadcast there instead of in beacons. */
    private fun startAware() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) return
        val am = getSystemService(android.net.wifi.aware.WifiAwareManager::class.java) ?: return
        if (!am.isAvailable) return
        runCatching {
            am.attach(object : android.net.wifi.aware.AttachCallback() {
                override fun onAttached(session: android.net.wifi.aware.WifiAwareSession) {
                    if (closing) { session.close(); return }
                    aware = session
                    val cfg = android.net.wifi.aware.SubscribeConfig.Builder()
                        .setServiceName(RemoteId.NAN_SERVICE)
                        .setSubscribeType(android.net.wifi.aware.SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE)
                        .build()
                    session.subscribe(cfg, object : android.net.wifi.aware.DiscoverySessionCallback() {
                        override fun onServiceDiscovered(peer: android.net.wifi.aware.PeerHandle, info: ByteArray?, filter: MutableList<ByteArray>?) {
                            val msgs = info?.let(RemoteId::parseNan).orEmpty()
                            if (msgs.isNotEmpty()) RemoteIdHub.tracker.add("nan:" + peer.hashCode(), msgs, null, System.currentTimeMillis(), Via.WifiNan)
                        }
                    }, null)
                    RemoteIdHub.publish(RemoteIdHub.state.value.copy(nan = true))
                }
            }, null)
        }
    }

    private fun stopWifi() {
        wifiReceiver?.let { runCatching { unregisterReceiver(it) } }
        wifiReceiver = null
        aware?.close(); aware = null
        RemoteIdHub.publish(RemoteIdHub.state.value.copy(wifi = false, nan = false))
    }

    // ---- state and notifications ----------------------------------------------------------------------

    private fun publish() {
        val now = System.currentTimeMillis()
        val t = RemoteIdHub.tracker
        t.prune(now)
        val list = t.list(now)
        RemoteIdHub.publish(RemoteIdHub.state.value.copy(running = true, drones = list))
        if (closing) return
        val active = list.count { it.active(now) }
        val summary = "$active/${list.size}"
        if (summary != lastSummary) {
            lastSummary = summary
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, ongoing(active))
        }
        // One alert per drone, once it has told who it is (or after a few seconds without it).
        list.filter { it.key !in alerted && it.active(now) && (it.id != null || now - it.firstSeen > 5_000) }.forEach { d ->
            alerted += d.key
            if (!RemoteIdHub.watching) alert(d)
        }
    }

    private fun openIntent() = PendingIntent.getActivity(
        this, 4, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_REMOTE_ID),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    @SuppressLint("LaunchActivityFromNotification")
    private fun ongoing(active: Int): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(NotificationChannel(CHANNEL, S(R.string.drone_detector), NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 5, Intent(this, RemoteIdService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(S(R.string.drone_detection_running))
            .setContentText(S(R.string.drones_in_range_n, active))
            .setOngoing(true)
            .setContentIntent(openIntent())
            .addAction(Notification.Action.Builder(null, S(R.string.stop), stop).build())
            .build()
    }

    private fun alert(d: Drone) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, S(R.string.drone_alerts), NotificationManager.IMPORTANCE_HIGH))
        val n = Notification.Builder(this, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(S(R.string.drone_detected))
            .setContentText(d.title)
            .setAutoCancel(true)
            .setContentIntent(openIntent())
            .build()
        nm.notify(ALERT_BASE + (d.key.hashCode() and 0xFFFF), n)
    }

    companion object {
        private const val CHANNEL = "remoteid"
        private const val ALERT_CHANNEL = "remoteid_alerts"
        private const val NOTIFICATION_ID = 44
        private const val ALERT_BASE = 10_000
        const val ACTION_STOP = "com.allnetworktools.REMOTE_ID_STOP"
    }
}
