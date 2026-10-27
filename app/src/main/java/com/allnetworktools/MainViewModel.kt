package com.allnetworktools

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.allnetworktools.data.AppSettings
import com.allnetworktools.data.BleDevice
import com.allnetworktools.data.BluetoothSnapshot
import com.allnetworktools.data.AdapterInfo
import com.allnetworktools.data.CellState
import com.allnetworktools.data.GnssState
import com.allnetworktools.data.PermGroup
import com.allnetworktools.data.PermissionSnapshot
import com.allnetworktools.data.SimInfo
import com.allnetworktools.data.WifiAp
import com.allnetworktools.data.WifiConnection
import com.allnetworktools.model.Network
import com.allnetworktools.model.Tool
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Why a network card or page is unavailable. */
enum class Blocker {
    NoHardware, WifiOff, BluetoothOff, NearbyPermission, Airplane, PhonePermission, NoSim, LocationPermission, LocationOff, SdrMissing;

    /** Missing USB hardware: the network can still be browsed, only its tools refuse to start. */
    val soft: Boolean get() = this == SdrMissing
}

sealed interface Page {
    data object Home : Page
    data object Dashboard : Page
    data object Tools : Page
    data class ToolPage(val tool: Tool, val arg: String? = null) : Page
    data object Settings : Page
    /** Release notes of every version, read from GitHub. */
    data object Changelog : Page
}

data class NavState(
    val network: Network? = null,
    val page: Page = Page.Home,
)

data class WifiUi(val connection: WifiConnection?, val history: List<Float>)

/** The device moved from one access point of the same network to another. */
data class RoamEvent(
    val atMs: Long,
    val ssid: String?,
    val fromBssid: String,
    val toBssid: String,
    val fromFreq: Int,
    val toFreq: Int,
    val rssiBefore: Int,
    val rssiAfter: Int,
    /** How long the device stayed on the previous AP, when known. */
    val stayedMs: Long?,
)
data class CellUi(val state: CellState?, val history: List<Float>)

private fun tickerFlow(periodMs: Long): Flow<Long> = flow {
    while (true) {
        emit(SystemClock.elapsedRealtime())
        delay(periodMs)
    }
}

/** Samples [value] of the latest upstream item every [rate] ms into a sliding window. */
private fun <T> Flow<T>.sampledHistory(rate: Flow<Long>, capacity: Int, value: (T) -> Float?): Flow<List<Float>> = channelFlow {
    var latest: T? = null
    val buffer = ArrayDeque<Float>()
    launch { this@sampledHistory.collect { latest = it } }
    rate.collectLatest { period ->
        while (true) {
            latest?.let(value)?.let {
                buffer.addLast(it)
                while (buffer.size > capacity) buffer.removeFirst()
                send(buffer.toList())
            }
            delay(period)
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val g = app as AntApplication
    private val sharing = SharingStarted.WhileSubscribed(5_000)

    val settings: StateFlow<AppSettings?> = g.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val rate: Flow<Long> = g.settings.settings.map { it.refreshMillis }.distinctUntilChanged()

    val permissions: StateFlow<PermissionSnapshot> = g.permissions.state
    fun refreshPermissions() = g.permissions.refresh()

    val wifiEnabled = g.radios.wifiEnabled.stateIn(viewModelScope, sharing, true)
    val bluetoothEnabled = g.radios.bluetoothEnabled.stateIn(viewModelScope, sharing, true)
    val airplane = g.radios.airplaneMode.stateIn(viewModelScope, sharing, false)
    val locationEnabled = g.radios.locationEnabled.stateIn(viewModelScope, sharing, true)

    private val _nav = MutableStateFlow(NavState())
    val nav: StateFlow<NavState> = _nav.asStateFlow()

    /** Pages visited inside the open network, so "back" returns to the previous screen. */
    private val trail = ArrayDeque<Page>()

    fun navigate(block: (NavState) -> NavState) {
        val old = _nav.value
        val new = block(old)
        when {
            new.network != old.network || new.page == Page.Home || old.page == Page.Home || old.page == Page.Settings || new.page == Page.Settings || new.page == Page.Changelog -> trail.clear()
            new.page != old.page -> {
                val i = trail.indexOf(new.page)
                // Coming back to a page already in the trail: drop what was opened after it.
                if (i >= 0) while (trail.size > i) trail.removeLast() else trail.addLast(old.page)
                while (trail.size > 30) trail.removeFirst()
            }
        }
        _nav.value = new
    }

    /** Where "back" leads from the current page: the previous screen, else [fallback] (the page's parent). */
    fun backTarget(fallback: Page): Page = trail.lastOrNull() ?: fallback

    fun back(fallback: Page) {
        val prev = trail.removeLastOrNull()
        _nav.value = _nav.value.copy(page = prev ?: fallback)
        if (prev == null && fallback == Page.Home) trail.clear()
    }

    // ---- availability ---------------------------------------------------------------------

    private data class CoreAvail(val p: PermissionSnapshot, val wifi: Boolean, val bt: Boolean, val plane: Boolean, val loc: Boolean)

    /** The plugged-in HackRF; USB enumeration only, the radio stays off until a tool starts it. */
    val sdrDevice = g.sdr.device.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val blockers: StateFlow<Map<Network, Blocker?>> = combine(
        combine(permissions, wifiEnabled, bluetoothEnabled, airplane, locationEnabled, ::CoreAvail),
        sdrDevice,
    ) { (p, wifi, bt, plane, loc), sdr ->
        mapOf(
            Network.Wifi to when {
                !g.radios.hasWifi -> Blocker.NoHardware
                !wifi -> Blocker.WifiOff
                else -> null
            },
            Network.Bluetooth to when {
                !g.radios.hasBluetooth -> Blocker.NoHardware
                !bt -> Blocker.BluetoothOff
                !p.nearby -> Blocker.NearbyPermission
                else -> null
            },
            Network.Cellular to when {
                !g.cell.hasTelephony -> Blocker.NoHardware
                plane -> Blocker.Airplane
                !p.phone -> Blocker.PhonePermission
                !g.cell.simReady() -> Blocker.NoSim
                else -> null
            },
            Network.Gnss to when {
                !p.location -> Blocker.LocationPermission
                !loc -> Blocker.LocationOff
                else -> null
            },
            Network.Sdr to if (sdr == null) Blocker.SdrMissing else null,
            // Wardriving needs both radios and the position of every sighting.
            Network.WifiBt to when {
                !g.radios.hasWifi || !g.radios.hasBluetooth -> Blocker.NoHardware
                !wifi -> Blocker.WifiOff
                !bt -> Blocker.BluetoothOff
                !p.nearby -> Blocker.NearbyPermission
                !p.location -> Blocker.LocationPermission
                !loc -> Blocker.LocationOff
                else -> null
            },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private fun <T> whenAvailable(network: Network, off: T, source: () -> Flow<T>): Flow<T> =
        blockers.map { it[network] == null && it.isNotEmpty() }.distinctUntilChanged()
            .flatMapLatest { ok -> if (ok) source().catch { emit(off) } else flowOf(off) }

    // ---- Wi-Fi --------------------------------------------------------------------------------

    private val wifiLive: Flow<WifiConnection?> = whenAvailable(Network.Wifi, null) {
        combine(g.wifi.connection, rate) { c, r -> c to r }.flatMapLatest { (c, r) ->
            if (c == null) flowOf(null) else tickerFlow(r).map {
                g.wifi.pollRssi()?.let { p ->
                    c.copy(
                        rssi = p.rssi, linkTx = p.tx.takeIf { it > 0 } ?: c.linkTx, linkRx = p.rx.takeIf { it > 0 } ?: c.linkRx,
                        bssid = p.bssid ?: c.bssid, frequency = p.frequency ?: c.frequency,
                    )
                } ?: c
            }
        }
    }

    val wifi: StateFlow<WifiUi> = channelFlow {
        var conn: WifiConnection? = null
        var hist = emptyList<Float>()
        // A new watching session starts from scratch: a gap must not look like a roaming event.
        roamLast = null
        launch {
            wifiLive.sampledHistory(rate, 60) { it?.rssi?.toFloat() }.collect { hist = it; send(WifiUi(conn, hist)) }
        }
        wifiLive.collect { conn = it; trackRoam(it); send(WifiUi(conn, if (it == null) emptyList() else hist)) }
    }.stateIn(viewModelScope, sharing, WifiUi(null, emptyList()))

    private val _roams = MutableStateFlow<List<RoamEvent>>(emptyList())

    /** Access-point changes on the current network, newest first. Cleared when the network changes. */
    val roams: StateFlow<List<RoamEvent>> = _roams

    val updater get() = g.updater

    internal fun setRoamsForTest(list: List<RoamEvent>) {
        _roams.value = list
    }

    private var roamLast: WifiConnection? = null
    private var roamSince = SystemClock.elapsedRealtime()

    /** Called with each Wi-Fi sample while the Wi-Fi network is being watched; records access-point changes. */
    private fun trackRoam(c: WifiConnection?) {
        val prev = roamLast
        roamLast = c
        if (c == null) return
        if (prev == null || prev.ssid != c.ssid || prev.connectedAtElapsed != c.connectedAtElapsed) {
            if (prev != null) _roams.value = emptyList()
            roamSince = SystemClock.elapsedRealtime()
            return
        }
        val a = prev.bssid
        val b = c.bssid
        if (a != null && b != null && !a.equals(b, true)) {
            val now = SystemClock.elapsedRealtime()
            _roams.value = (listOf(RoamEvent(System.currentTimeMillis(), c.ssid, a, b, prev.frequency, c.frequency, prev.rssi, c.rssi, now - roamSince)) + _roams.value).take(50)
            roamSince = now
        }
    }

    val wifiScan: StateFlow<List<WifiAp>?> = whenAvailable<List<WifiAp>?>(Network.Wifi, emptyList()) { g.wifi.scanResults }
        .stateIn(viewModelScope, sharing, null)
    val wifiThrottledUntil: StateFlow<Long> = g.wifi.throttledUntil
    fun startWifiScan(): Boolean = g.wifi.startScan()

    // ---- Bluetooth ---------------------------------------------------------------------------

    val bluetooth: StateFlow<BluetoothSnapshot> = whenAvailable(Network.Bluetooth, BluetoothSnapshot(AdapterInfo(null, emptyList()), emptyList())) {
        g.bluetooth.snapshot
    }.stateIn(viewModelScope, sharing, BluetoothSnapshot(AdapterInfo(null, emptyList()), emptyList()))

    private val bleLowLatency = MutableStateFlow(false)
    private val bleRestarts = MutableStateFlow(0)
    fun setBleLowLatency(v: Boolean) {
        bleLowLatency.value = v
    }

    fun forgetBluetooth(address: String): Boolean = g.bluetooth.forget(address)

    fun restartBleScan() {
        bleRestarts.value++
    }

    val ble: StateFlow<List<BleDevice>> = whenAvailable(Network.Bluetooth, emptyList()) {
        combine(bleLowLatency, bleRestarts) { fast, _ -> fast }.flatMapLatest { fast -> g.bluetooth.bleScan(lowPower = !fast) }
    }.stateIn(viewModelScope, sharing, emptyList())

    // ---- Cellular ------------------------------------------------------------------------------

    private val cellLive: Flow<CellState?> = whenAvailable<CellState?>(Network.Cellular, null) {
        rate.flatMapLatest { g.cell.cells(it) }
    }

    val cell: StateFlow<CellUi> = channelFlow {
        var state: CellState? = null
        var hist = emptyList<Float>()
        launch {
            cellLive.sampledHistory(rate, 300) { it?.serving?.level?.toFloat() }.collect { hist = it; send(CellUi(state, hist)) }
        }
        cellLive.collect { state = it; send(CellUi(state, hist)) }
    }.stateIn(viewModelScope, sharing, CellUi(null, emptyList()))

    val sims: StateFlow<List<SimInfo>> = whenAvailable(Network.Cellular, emptyList()) {
        cellLive.filterNotNull().map { g.cell.sims() }.distinctUntilChanged()
    }.stateIn(viewModelScope, sharing, emptyList())

    // ---- GNSS -----------------------------------------------------------------------------------

    val gnss: StateFlow<GnssState> = whenAvailable(Network.Gnss, GnssState()) {
        rate.flatMapLatest { g.gnss.status(it) }
    }.stateIn(viewModelScope, sharing, GnssState())

    fun lastKnownLocation() = g.gnss.lastKnownLocation()

    /** Raw GNSS measurements, only while a tool that needs them is open. */
    val gnssRaw: kotlinx.coroutines.flow.SharedFlow<com.allnetworktools.data.RawEpoch> =
        whenAvailable<com.allnetworktools.data.RawEpoch?>(Network.Gnss, null) { g.gnss.raw() }
            .filterNotNull()
            .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    fun gnssCapabilities() = g.gnss.capabilities()

    val wifiDirect get() = g.wifiDirect

    fun operatorPlmns() = g.cell.plmns()

    val positions: StateFlow<com.allnetworktools.data.PositionSet> = whenAvailable(Network.Gnss, com.allnetworktools.data.PositionSet()) {
        rate.flatMapLatest { g.gnss.positions(it) }
    }.stateIn(viewModelScope, sharing, com.allnetworktools.data.PositionSet())

    // ---- settings -------------------------------------------------------------------------------

    fun updateSettings(block: suspend com.allnetworktools.data.SettingsRepository.() -> Unit) {
        viewModelScope.launch { g.settings.block() }
    }

    fun markAsked(group: PermGroup) = updateSettings { markAsked(group.name) }

    val tools = com.allnetworktools.ui.tools.ToolsHub(g, viewModelScope)

    private fun stopSdr() {
        tools.fpv.stop()
        tools.spectrum.stop()
        tools.adsb.stop()
        tools.sonde.stop()
        tools.ais.stop()
        tools.aprs.stop()
        tools.meteor.stop()
    }

    init {
        // The HackRF only receives while the SDR tab is open.
        viewModelScope.launch {
            nav.map { it.network }.distinctUntilChanged().collect { if (it != Network.Sdr) stopSdr() }
        }
        viewModelScope.launch {
            sdrDevice.collect { if (it == null) stopSdr() }
        }
    }
    val history get() = g.history

    init {
        viewModelScope.launch {
            // At every launch: a newer GitHub release is offered in a window with its notes. A launch while offline stays silent.
            runCatching { g.updater.check(autoInstall = false, force = true) }
            g.updater.forgetFailedCheck()
        }
        viewModelScope.launch { g.history.purgeLegacyFiles() }
    }
}
