package com.allnetworktools.ui.pages

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.NavState
import com.allnetworktools.Page
import com.allnetworktools.data.PermGroup
import com.allnetworktools.Blocker
import com.allnetworktools.model.Network
import com.allnetworktools.model.Tool
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.IconCircleButton
import com.allnetworktools.ui.components.PanelAction
import com.allnetworktools.ui.components.StatePanel
import com.allnetworktools.ui.home.pageBlock
import com.allnetworktools.ui.pages.bt.BleScanner
import com.allnetworktools.ui.pages.bt.BtDashboard
import com.allnetworktools.ui.pages.bt.GattTool
import com.allnetworktools.ui.pages.bt.PairedTool
import com.allnetworktools.ui.pages.bt.TrackerTool
import com.allnetworktools.ui.pages.cell.CellDashboard
import com.allnetworktools.ui.pages.cell.CellDetailTool
import com.allnetworktools.ui.pages.gnss.SkyTool
import com.allnetworktools.ui.pages.cell.NeighborCells
import com.allnetworktools.ui.pages.gnss.GnssDashboard
import com.allnetworktools.ui.pages.wifi.WifiDashboard
import com.allnetworktools.ui.pages.wifi.WifiScanner
import com.allnetworktools.ui.pages.wifi.DnsTool
import com.allnetworktools.ui.pages.wifi.LanDeviceTool
import com.allnetworktools.ui.pages.wifi.LanTool
import com.allnetworktools.ui.pages.wifi.PingTool
import com.allnetworktools.ui.pages.wifi.PortsTool
import com.allnetworktools.ui.pages.wifi.SpeedTool
import com.allnetworktools.ui.pages.wifi.TraceTool
import com.allnetworktools.data.standardLabel
import androidx.compose.runtime.getValue
import com.allnetworktools.ui.settings.SettingsScreen
import com.allnetworktools.ui.theme.Motion
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.R
import com.allnetworktools.util.S

data class TopAction(val icon: String, val onClick: () -> Unit)

val LocalTopAction = staticCompositionLocalOf<MutableState<TopAction?>> { mutableStateOf(null) }

/** Registers the top-bar action of the current page while it is composed. */
@Composable
fun TopBarAction(icon: String, onClick: () -> Unit) {
    val slot = LocalTopAction.current
    val action = TopAction(icon, onClick)
    DisposableEffect(icon, onClick) {
        slot.value = action
        onDispose { if (slot.value === action) slot.value = null }
    }
}

/**
 * Back steps a tool takes itself before the page closes: a picked device, an open detail, a sub-screen. The latest
 * registered one runs first, for the system back gesture and the top bar's arrow alike.
 */
class ToolBackStack {
    val entries = mutableStateListOf<() -> Unit>()

    /** Runs the latest step; false when the tool has none and the page itself should go back. */
    fun pop(): Boolean {
        val last = entries.lastOrNull() ?: return false
        last()
        return true
    }
}

val LocalToolBack = staticCompositionLocalOf { ToolBackStack() }

/** While [enabled], back runs [onBack] instead of leaving the tool. */
@Composable
fun ToolBack(enabled: Boolean, onBack: () -> Unit) {
    val stack = LocalToolBack.current
    val current by rememberUpdatedState(onBack)
    if (enabled) {
        DisposableEffect(stack) {
            val entry: () -> Unit = { current() }
            stack.entries += entry
            onDispose { stack.entries.remove(entry) }
        }
    }
}

private fun titles(nav: NavState): Pair<String, String> {
    val net = nav.network ?: Network.Wifi
    return when (val p = nav.page) {
        Page.Changelog -> S(R.string.changelog) to S(R.string.settings)
        Page.Settings -> S(R.string.settings) to "All Radio Tools ${com.allnetworktools.BuildConfig.VERSION_NAME}"
        Page.Dashboard -> net.label to "Dashboard"
        Page.Tools -> net.label to S(R.string.tools)
        is Page.ToolPage -> p.tool.title to S(R.string.tools_2, net.label)
        Page.Home -> "" to ""
    }
}

/** Page chrome (top bar + scrolling body) with a fade-through between pages. */
@Composable
fun PageHostContent(vm: MainViewModel, nav: NavState, onBack: () -> Unit) {
    val topAction = remember { mutableStateOf<TopAction?>(null) }
    val (title, subtitle) = titles(nav)
    CompositionLocalProvider(LocalTopAction provides topAction) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().background(cs.surface).windowInsetsPadding(WindowInsets.statusBars)
                    .height(64.dp).padding(start = 4.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconCircleButton(Sym.ArrowBack, onBack, tint = cs.onSurface)
                Column(Modifier.weight(1f)) {
                    Text(title, style = gs(22, 28, 500), maxLines = 1)
                    Text(subtitle, style = rf(12, 16), color = cs.onSurfaceVariant, maxLines = 1)
                }
                val a = topAction.value
                if (a != null) IconCircleButton(a.icon, a.onClick, tint = cs.onSurfaceVariant) else Spacer(Modifier.size(48.dp))
            }
            AnimatedContent(
                targetState = nav.network to nav.page,
                transitionSpec = {
                    (fadeIn(tween(400, 90, Motion.Emphasized)) + slideInVertically(tween(400, 90, Motion.Emphasized)) { it / 60 })
                        .togetherWith(fadeOut(tween(90)))
                },
                label = "fadeThrough",
                modifier = Modifier.weight(1f),
            ) { (net, page) ->
                PageBody(vm, net ?: Network.Wifi, page)
            }
        }
    }
}

/** Scrollable page body with the standard 16 dp gutters and room for the dock. */
@Composable
fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    val nav = WindowInsets.navigationBars.asPaddingValues()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 128.dp + nav.calculateBottomPadding())),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun PageBody(vm: MainViewModel, net: Network, page: Page) {
    if (page == Page.Settings) {
        SettingsScreen(vm)
        return
    }
    if (page == Page.Changelog) {
        com.allnetworktools.ui.settings.ChangelogScreen(vm)
        return
    }
    val blocker = vm.blockers.collectAsStateWithLifecycle().value[net]
    val internetTool = page is Page.ToolPage && page.tool in InternetTools
    // Without a HackRF or a cable the dashboard and the tool list stay open; only the tools themselves are blocked.
    if (blocker != null && !internetTool && (!blocker.soft || page is Page.ToolPage)) {
        BlockedPage(net, blocker)
        return
    }
    if (net == Network.Wifi && page is Page.ToolPage) {
        // Android 17: ask for the local network up front, otherwise the system interrupts the scans with a device picker.
        val actions = LocalActions.current
        val perms by vm.permissions.collectAsStateWithLifecycle()
        LaunchedEffect(Unit) {
            if (PermGroup.LocalNetwork !in perms && !actions.isPermanentlyDenied(PermGroup.LocalNetwork)) actions.request(PermGroup.LocalNetwork)
        }
    }
    when (page) {
        Page.Dashboard -> when (net) {
            Network.Wifi -> WifiDashboard(vm)
            Network.Bluetooth -> BtDashboard(vm)
            Network.Cellular -> CellDashboard(vm)
            Network.Gnss -> GnssDashboard(vm)
            Network.Sdr -> com.allnetworktools.ui.pages.sdr.SdrDashboard(vm)
            Network.Talkie -> com.allnetworktools.ui.pages.talkie.TalkieDashboard(vm)
        }
        Page.Tools -> ToolsPage(net, vm)
        is Page.ToolPage -> when (page.tool) {
            Tool.WifiScan -> WifiScanner(vm)
            Tool.BleScan -> BleScanner(vm)
            Tool.Neighbors -> NeighborCells(vm)
            Tool.Handover -> {
                val perms by vm.permissions.collectAsStateWithLifecycle()
                com.allnetworktools.ui.pages.cell.HandoverTool(PermGroup.Notifications in perms)
            }
            Tool.FakeCells -> {
                val perms by vm.permissions.collectAsStateWithLifecycle()
                val actions = LocalActions.current
                com.allnetworktools.ui.pages.cell.FakeCellsTool(
                    permitted = perms.location && perms.phone,
                    onGrant = { actions.request(if (!perms.location) PermGroup.Location else PermGroup.Phone) },
                    onNotifications = { if (PermGroup.Notifications !in perms) actions.request(PermGroup.Notifications) },
                )
            }
            Tool.Sky -> SkyTool(vm)
            Tool.PositionCompare -> com.allnetworktools.ui.pages.gnss.PositionCompareTool(vm)
            Tool.GnssIntegrity -> com.allnetworktools.ui.pages.gnss.IntegrityTool(vm)
            Tool.GnssRaw -> com.allnetworktools.ui.pages.gnss.RawTool(vm)
            Tool.CompassCheck -> com.allnetworktools.ui.pages.gnss.CompassCheckTool(vm)
            Tool.GnssClock -> com.allnetworktools.ui.pages.gnss.GnssClockTool(vm)
            Tool.SkyMask -> com.allnetworktools.ui.pages.gnss.SkyMaskTool(vm)
            Tool.Spectrum -> com.allnetworktools.ui.pages.sdr.SpectrumTool(vm)
            Tool.Adsb -> com.allnetworktools.ui.pages.sdr.AdsbTool(vm)
            Tool.Sonde -> com.allnetworktools.ui.pages.sdr.SondeTool(vm)
            Tool.Ais -> com.allnetworktools.ui.pages.sdr.AisTool(vm)
            Tool.Fpv -> com.allnetworktools.ui.pages.sdr.FpvTool(vm)
            Tool.Aprs -> com.allnetworktools.ui.pages.sdr.AprsTool(vm)
            Tool.Acars -> com.allnetworktools.ui.pages.sdr.AcarsTool(vm)
            Tool.Meteor -> com.allnetworktools.ui.pages.sdr.MeteorTool(vm)
            Tool.RadioChannels -> com.allnetworktools.ui.pages.talkie.ChannelsTool(vm)
            Tool.WifiDirect -> {
                val perms by vm.permissions.collectAsStateWithLifecycle()
                val actions = LocalActions.current
                // Android 13+ gates Wi-Fi Direct behind "Appareils à proximité"; earlier versions behind precise location.
                val modern = android.os.Build.VERSION.SDK_INT >= 33
                com.allnetworktools.ui.pages.wifi.WifiDirectTool(
                    vm.wifiDirect, if (modern) perms.nearby else perms.location,
                    if (modern) S(R.string.android_needs_the_nearby_devices_permission)
                    else S(R.string.android_needs_the_location_permission_to),
                ) { actions.request(if (modern) PermGroup.Nearby else PermGroup.Location) }
            }
            Tool.WifiAware -> {
                val perms by vm.permissions.collectAsStateWithLifecycle()
                val actions = LocalActions.current
                // Android 13+ gates Wi-Fi Aware behind "Appareils à proximité"; earlier versions behind precise location.
                val modern = android.os.Build.VERSION.SDK_INT >= 33
                com.allnetworktools.ui.pages.wifi.WifiAwareTool(
                    permitted = if (modern) perms.nearby else perms.location,
                    permissionText = if (modern) S(R.string.android_needs_the_nearby_devices_permission) else S(R.string.android_needs_the_location_permission_to),
                    onFix = { actions.request(if (modern) PermGroup.Nearby else PermGroup.Location) },
                    located = perms.location,
                    onLocate = { actions.request(PermGroup.Location) },
                )
            }
            Tool.Gatt, Tool.Paired, Tool.Tracker, Tool.UnknownTrackers, Tool.TrackerDetail, Tool.Beacons, Tool.Earbuds -> BtToolRoute(vm, page)
            Tool.CellDetail -> CellDetailTool(vm.cell.collectAsStateWithLifecycle().value.state, page.arg) {
                vm.navigate { it.copy(page = Page.ToolPage(Tool.Neighbors)) }
            }
            Tool.Lan, Tool.LanDevice, Tool.Ping, Tool.Trace, Tool.Ports, Tool.Dns, Tool.Speed,
            Tool.Whois, Tool.EvilTwin, Tool.Audit, Tool.Mitm, Tool.Coverage -> WifiToolRoute(vm, page)
            else -> ComingSoon(page.tool)
        }
        else -> Unit
    }
}

/** Tools that only need an Internet connection, usable over mobile data with Wi-Fi off. */
private val InternetTools = setOf(Tool.Ping, Tool.Trace, Tool.Dns, Tool.Speed, Tool.Ports, Tool.Whois)

@Composable
private fun WifiToolRoute(vm: MainViewModel, page: Page.ToolPage) {
    val tools = vm.tools
    val conn = vm.wifi.collectAsStateWithLifecycle().value.connection
    val perms by vm.permissions.collectAsStateWithLifecycle()
    val locationOn by vm.locationEnabled.collectAsStateWithLifecycle()
    val actions = LocalActions.current
    fun open(tool: Tool, arg: String? = null) = vm.navigate { it.copy(page = Page.ToolPage(tool, arg)) }
    when (page.tool) {
        Tool.Lan -> LanTool(tools.lan, conn) { ip -> open(Tool.LanDevice, ip) }
        Tool.Coverage -> com.allnetworktools.ui.pages.wifi.CoverageTool(tools.coverage, conn)
        Tool.LanDevice -> LanDeviceTool(
            tools.lanDevice, page.arg, page.arg?.let(tools.lan::device), conn?.prefix,
            onPing = { open(Tool.Ping, it) }, onPorts = { open(Tool.Ports, it) },
        )
        Tool.Ping -> PingTool(tools.ping, page.arg)
        Tool.Trace -> TraceTool(tools.trace)
        Tool.Ports -> PortsTool(tools.ports, page.arg, conn?.gateway)
        Tool.Dns -> DnsTool(tools.dns, conn?.dns?.firstOrNull { '.' in it })
        Tool.Whois -> com.allnetworktools.ui.pages.wifi.WhoisTool(tools.whois)
        Tool.Audit -> com.allnetworktools.ui.pages.wifi.AuditTool(tools.audit, conn, vm.wifiScan.collectAsStateWithLifecycle().value)
        Tool.Mitm -> com.allnetworktools.ui.pages.wifi.MitmTool(tools.mitm, conn)
        Tool.EvilTwin -> com.allnetworktools.ui.pages.wifi.EvilTwinTool(
            tools.evilTwin, conn, vm.wifiScan, vm::startWifiScan, perms.location && locationOn,
            onFixLocation = { if (!perms.location) actions.request(PermGroup.Location) else actions.openLocationSettings() },
        )
        Tool.Speed -> {
            val cell = if (conn == null) vm.cell.collectAsStateWithLifecycle().value.state else null
            val (label, icon) = when {
                conn != null -> listOfNotNull(conn.ssid, standardLabel(conn.standard)?.first, "${conn.band.label} GHz").joinToString(" · ") to Sym.Wifi
                cell != null -> listOfNotNull(cell.operator, cell.techLabel).joinToString(" · ") to Sym.CellBars3
                else -> S(R.string.active_network) to Sym.Public
            }
            SpeedTool(tools.speed, label, icon)
        }
        else -> Unit
    }
}

@Composable
private fun BtToolRoute(vm: MainViewModel, page: Page.ToolPage) {
    val bt by vm.bluetooth.collectAsStateWithLifecycle()
    val ble by vm.ble.collectAsStateWithLifecycle()
    when (page.tool) {
        Tool.Gatt -> GattTool(
            vm.tools.gatt, page.arg, ble.firstOrNull { it.address == page.arg },
            bonded = bt.bonded.any { it.address == page.arg },
        )
        Tool.Paired -> {
            val d = bt.bonded.firstOrNull { it.address == page.arg }
            PairedTool(
                d, ble.firstOrNull { it.address == page.arg }?.rssi,
                onForget = { page.arg != null && vm.forgetBluetooth(page.arg) },
                onExplore = { vm.navigate { it.copy(page = Page.ToolPage(Tool.Gatt, page.arg)) } },
            )
        }
        Tool.Tracker -> {
            DisposableEffect(Unit) {
                vm.setBleLowLatency(true)
                onDispose { vm.setBleLowLatency(false) }
            }
            TrackerTool(vm.tools.tracker, ble)
        }
        Tool.UnknownTrackers -> {
            val positions by vm.positions.collectAsStateWithLifecycle()
            val guard by com.allnetworktools.data.TrackerGuard.running.collectAsStateWithLifecycle()
            val perms by vm.permissions.collectAsStateWithLifecycle()
            val actions = LocalActions.current
            val context = androidx.compose.ui.platform.LocalContext.current
            com.allnetworktools.ui.pages.bt.UnknownTrackersTool(
                vm.tools.unknownTrackers, ble, positions,
                onOpen = { key -> vm.navigate { it.copy(page = Page.ToolPage(Tool.TrackerDetail, key)) } },
                guardRunning = guard,
                onGuard = { on ->
                    if (!on) com.allnetworktools.data.TrackerGuard.stop(context) else {
                        if (PermGroup.Notifications !in perms) actions.request(PermGroup.Notifications)
                        else if (!perms.location) actions.request(PermGroup.Location)
                        com.allnetworktools.data.TrackerGuard.start(context)
                    }
                },
            )
        }
        Tool.Beacons -> com.allnetworktools.ui.pages.bt.BeaconsTool(ble)
        Tool.Earbuds -> com.allnetworktools.ui.pages.bt.EarbudsTool(ble)
        Tool.TrackerDetail -> {
            val positions by vm.positions.collectAsStateWithLifecycle()
            com.allnetworktools.ui.pages.bt.TrackerDetailTool(
                vm.tools.unknownTrackers, page.arg, ble, positions,
                onLocate = { d -> vm.tools.tracker.follow(d); vm.navigate { it.copy(page = Page.ToolPage(Tool.Tracker)) } },
            )
        }
        else -> Unit
    }
}

@Composable
private fun BlockedPage(net: Network, blocker: Blocker) {
    val actions = LocalActions.current
    val group = when (blocker) {
        Blocker.LocationPermission -> PermGroup.Location
        Blocker.NearbyPermission -> PermGroup.Nearby
        Blocker.PhonePermission -> PermGroup.Phone
        else -> null
    }
    val denied = group != null && actions.isPermanentlyDenied(group)
    val d = pageBlock(net, blocker, denied)
    val secondary = d.secondary?.let { label ->
        PanelAction(label) {
            when (blocker) {
                Blocker.WifiOff -> actions.openWifiSettings()
                Blocker.BluetoothOff -> actions.openBluetoothSettings()
                else -> actions.openAppSettings()
            }
        }
    }
    Box(Modifier.fillMaxSize().padding(bottom = 96.dp), contentAlignment = Alignment.Center) {
        StatePanel(
            icon = d.icon, title = d.title, message = d.message,
            primary = d.primary?.let { PanelAction(it, d.primaryIcon) { if (denied) actions.openAppSettings() else actions.resolve(blocker) } },
            secondary = secondary,
        )
    }
}

@Composable
private fun ComingSoon(tool: Tool) {
    Box(Modifier.fillMaxSize().padding(bottom = 96.dp), contentAlignment = Alignment.Center) {
        StatePanel(
            icon = tool.icon,
            title = tool.title,
            message = S(R.string.this_tool_arrives_in_the_next),
            primary = null,
        )
    }
}
