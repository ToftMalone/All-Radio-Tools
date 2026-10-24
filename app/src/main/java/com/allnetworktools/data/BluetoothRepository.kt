package com.allnetworktools.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.allnetworktools.ui.theme.Sym
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import com.allnetworktools.R
import com.allnetworktools.util.S

data class AdapterInfo(
    val name: String?,
    val features: List<String>,
)

data class BondedDevice(
    val address: String,
    val name: String,
    val icon: String,
    val kind: String,
    val connected: Boolean,
    val profiles: List<String>,
    /** Classique, LE or Double mode. */
    val transport: String = S(R.string.classic),
    /** Battery reported over HFP/BAS, when the system exposes it. */
    val battery: Int? = null,
)

data class BluetoothSnapshot(
    val adapter: AdapterInfo,
    val bonded: List<BondedDevice>,
) {
    val connected get() = bonded.filter { it.connected }
}

@SuppressLint("MissingPermission")
open class BluetoothRepository(private val context: Context) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = manager?.adapter

    private val profileIds = buildList {
        add(BluetoothProfile.A2DP to "A2DP")
        add(BluetoothProfile.HEADSET to "HFP")
        add(BluetoothProfile.HEARING_AID to "ASHA")
        if (Build.VERSION.SDK_INT >= 33) add(BluetoothProfile.LE_AUDIO to "LE Audio")
        add(BluetoothProfile.HID_DEVICE to "HID")
    }

    private fun adapterInfo(): AdapterInfo {
        val a = adapter ?: return AdapterInfo(null, emptyList())
        val features = buildList {
            add(if (a.isLe2MPhySupported || a.isLeExtendedAdvertisingSupported) "Bluetooth 5" else "Bluetooth 4")
            if (Build.VERSION.SDK_INT >= 33) {
                if (a.isLeAudioSupported == BluetoothStatusCodes.FEATURE_SUPPORTED) add("LE Audio")
                if (a.isLeAudioBroadcastSourceSupported == BluetoothStatusCodes.FEATURE_SUPPORTED) add("Auracast")
            }
            if (a.isLeCodedPhySupported) add(S(R.string.long_range))
        }
        return AdapterInfo(runCatching { a.name }.getOrNull(), features)
    }

    /** Adapter details, bonded devices and which of them are connected, refreshed on ACL and profile changes. */
    open val snapshot: Flow<BluetoothSnapshot> = callbackFlow {
        val a = adapter
        if (a == null) {
            trySend(BluetoothSnapshot(AdapterInfo(null, emptyList()), emptyList())); awaitClose { }; return@callbackFlow
        }
        val proxies = mutableMapOf<Int, BluetoothProfile>()
        fun emit() {
            val bonded = runCatching { a.bondedDevices }.getOrNull().orEmpty()
            val gatt = runCatching { manager.getConnectedDevices(BluetoothProfile.GATT) }.getOrDefault(emptyList()).map { it.address }.toSet()
            val profilesByDevice = mutableMapOf<String, MutableList<String>>()
            profileIds.forEach { (id, label) ->
                runCatching { proxies[id]?.connectedDevices }.getOrNull()?.forEach { d ->
                    profilesByDevice.getOrPut(d.address) { mutableListOf() }.add(label)
                }
            }
            val list = bonded.map { d ->
                val profiles = profilesByDevice[d.address].orEmpty()
                val isConnected = profiles.isNotEmpty() || d.address in gatt || isAclConnected(d)
                val (icon, kind) = describeClass(d)
                BondedDevice(
                    address = d.address,
                    name = runCatching { d.alias ?: d.name }.getOrNull() ?: d.address,
                    icon = icon,
                    kind = kind,
                    connected = isConnected,
                    profiles = profiles + if (d.address in gatt) listOf("GATT") else emptyList(),
                    transport = when (runCatching { d.type }.getOrDefault(0)) {
                        BluetoothDevice.DEVICE_TYPE_LE -> "Bluetooth LE"
                        BluetoothDevice.DEVICE_TYPE_DUAL -> S(R.string.dual_mode)
                        else -> S(R.string.classic_br_edr)
                    },
                    battery = if (isConnected) batteryLevel(d) else null,
                )
            }.sortedWith(compareByDescending<BondedDevice> { it.connected }.thenBy { it.name.lowercase() })
            trySend(BluetoothSnapshot(adapterInfo(), list))
        }
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                proxies[profile] = proxy; emit()
            }

            override fun onServiceDisconnected(profile: Int) {
                proxies.remove(profile); emit()
            }
        }
        profileIds.forEach { (id, _) -> runCatching { a.getProfileProxy(context, listener, id) } }
        val refresh = launch {
            broadcastFlow(
                context,
                BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED,
                BluetoothDevice.ACTION_BOND_STATE_CHANGED, BluetoothAdapter.ACTION_STATE_CHANGED,
                BluetoothAdapter.ACTION_LOCAL_NAME_CHANGED, BluetoothDevice.ACTION_NAME_CHANGED,
                "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED",
                "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED",
                "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED",
            ) { SystemClock.elapsedRealtimeNanos() }.collect { emit() }
        }
        emit()
        awaitClose {
            refresh.cancel()
            proxies.forEach { (id, p) -> runCatching { a.closeProfileProxy(id, p) } }
        }
    }

    /** Hidden but greylisted getter fed by HFP and the LE battery service; null when unknown or blocked. */
    private fun batteryLevel(d: BluetoothDevice): Int? =
        runCatching { d.javaClass.getMethod("getBatteryLevel").invoke(d) as Int }.getOrNull()?.takeIf { it in 0..100 }

    /** Asks the stack to drop the bond; hidden API, so callers fall back to system settings when it returns false. */
    open fun forget(address: String): Boolean = runCatching {
        val d = adapter?.getRemoteDevice(address) ?: return false
        d.javaClass.getMethod("removeBond").invoke(d) as Boolean
    }.getOrDefault(false)

    private fun isAclConnected(d: BluetoothDevice): Boolean =
        runCatching { d.javaClass.getMethod("isConnected").invoke(d) as Boolean }.getOrDefault(false)

    private fun describeClass(d: BluetoothDevice): Pair<String, String> {
        val cls = runCatching { d.bluetoothClass }.getOrNull() ?: return Sym.Bluetooth to S(R.string.device_2)
        return when (cls.majorDeviceClass) {
            BluetoothClass.Device.Major.AUDIO_VIDEO -> when (cls.deviceClass) {
                BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER, BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO, BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO -> Sym.Speaker to S(R.string.speaker)
                BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO, BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE -> Sym.DirectionsCar to S(R.string.car)
                BluetoothClass.Device.AUDIO_VIDEO_VIDEO_DISPLAY_AND_LOUDSPEAKER, BluetoothClass.Device.AUDIO_VIDEO_VIDEO_MONITOR -> Sym.Tv to S(R.string.display)
                else -> Sym.Headphones to "Audio"
            }
            BluetoothClass.Device.Major.WEARABLE, BluetoothClass.Device.Major.HEALTH -> Sym.Watch to S(R.string.watch)
            BluetoothClass.Device.Major.PERIPHERAL -> when {
                cls.deviceClass and 0x40 != 0 -> Sym.Keyboard to S(R.string.keyboard)
                cls.deviceClass and 0x80 != 0 -> Sym.Mouse to S(R.string.mouse)
                else -> Sym.Gamepad to S(R.string.peripheral)
            }
            BluetoothClass.Device.Major.COMPUTER -> Sym.Laptop to S(R.string.computer)
            BluetoothClass.Device.Major.PHONE -> Sym.Smartphone to S(R.string.phone)
            BluetoothClass.Device.Major.IMAGING -> Sym.Print to S(R.string.printer)
            else -> Sym.Bluetooth to S(R.string.device_2)
        }
    }

    /** Identities learned by connecting (Generic Access + Device Information), shared with the scanner. */
    open val identities = BleIdentityStore(context)

    /** Connects briefly, read-only, to learn what an unidentified device is. Null when it refuses or times out. */
    open suspend fun identify(address: String): GattIdentity? = BleIdentifier(context, identities).identify(address)

    private class Entry {
        val ads = LinkedHashMap<String, AdStructure>()
        var adsHash = 0
        var name: String? = null
        var rssi = 0
        var txPower: Int? = null
        var connectable = false
        var extended = false
        var intervalMs: Int? = null
        var lastSeen = 0L
        var identity: BleIdentity? = null
        var idVersion = -1
        var dirty = true
    }

    /**
     * Live BLE advertisements, merged per address and dropped after [staleMs] without a packet.
     * Extended (BLE 5) advertisements are requested too, and the AD structures of successive
     * packets are merged so identification sees advertising data and scan responses together.
     * Emits a snapshot at least every second so RSSI and staleness stay current.
     */
    open fun bleScan(lowPower: Boolean, staleMs: Long = 15_000, filters: List<android.bluetooth.le.ScanFilter>? = null): Flow<List<BleDevice>> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null || !adapter.isEnabled) {
            trySend(emptyList()); awaitClose { }; return@callbackFlow
        }
        val entries = mutableMapOf<String, Entry>()
        fun build(addr: String, e: Entry): BleDevice {
            val gatt = identities.get(addr)
            val version = identities.version.value
            val ads = e.ads.values.toList()
            if (e.identity == null || e.dirty || e.idVersion != version) {
                e.identity = BleIdentify.identify(e.name, ads, addr, gatt)
                e.idVersion = version
                e.dirty = false
            }
            return BleDevice.from(addr, e.name, e.rssi, e.txPower, e.connectable, e.lastSeen, ads, gatt, e.extended, e.intervalMs, e.identity)
        }
        fun snapshot(): List<BleDevice> {
            val now = SystemClock.elapsedRealtime()
            entries.values.removeAll { now - it.lastSeen > staleMs }
            return entries.map { (a, e) -> build(a, e) }.sortedByDescending { it.rssi }
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val addr = result.device.address
                val now = SystemClock.elapsedRealtime()
                val first = entries[addr] == null
                val e = entries.getOrPut(addr) { Entry() }
                val gap = if (first) null else (now - e.lastSeen).toInt().takeIf { it in 5..10_000 }
                val record = result.scanRecord
                record?.bytes?.let { Ad.merge(e.ads, Ad.parse(it)) }
                val hash = e.ads.values.sumOf { it.hashCode() }
                if (hash != e.adsHash) { e.adsHash = hash; e.dirty = true }
                val nm = record?.deviceName ?: runCatching { result.device.name }.getOrNull()
                if (nm != null && nm != e.name) { e.name = nm; e.dirty = true }
                e.rssi = if (first) result.rssi else ((e.rssi * 0.6f) + result.rssi * 0.4f).toInt()
                e.txPower = result.txPower.takeIf { it != ScanResult.TX_POWER_NOT_PRESENT } ?: record?.txPowerLevel?.takeIf { it != Int.MIN_VALUE } ?: e.txPower
                e.connectable = e.connectable || result.isConnectable
                e.extended = e.extended || !result.isLegacy
                e.intervalMs = when {
                    gap == null -> e.intervalMs
                    e.intervalMs == null -> gap
                    else -> (e.intervalMs!! * 0.8f + gap * 0.2f).toInt()
                }
                e.lastSeen = now
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { onScanResult(0, it) }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("BLE scan failed: $errorCode"))
            }
        }
        val settings = ScanSettings.Builder()
            .setScanMode(if (lowPower) ScanSettings.SCAN_MODE_LOW_POWER else ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setLegacy(false)
            .setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
            .build()
        runCatching { scanner.startScan(filters, settings, callback) }.onFailure { close(it) }
        val ticker = launch {
            while (true) {
                trySend(snapshot())
                delay(1000)
            }
        }
        awaitClose {
            ticker.cancel()
            runCatching { scanner.stopScan(callback) }
        }
    }
}
