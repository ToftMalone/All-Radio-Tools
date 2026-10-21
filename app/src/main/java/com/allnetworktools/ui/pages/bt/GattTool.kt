package com.allnetworktools.ui.pages.bt

import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.allnetworktools.data.BleDevice
import com.allnetworktools.data.GattClient
import com.allnetworktools.data.GattEvent
import com.allnetworktools.data.GattException
import com.allnetworktools.data.GattNames
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.BlinkDot
import com.allnetworktools.ui.components.Hairline
import com.allnetworktools.ui.components.InfoList
import com.allnetworktools.ui.components.InfoRow
import com.allnetworktools.ui.components.LeadingIcon
import com.allnetworktools.ui.components.PillButton
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.SegmentedRow
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.components.rise
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.HeroCard
import com.allnetworktools.ui.tools.HeroChip
import com.allnetworktools.ui.tools.IndeterminateBar
import com.allnetworktools.ui.tools.Phase
import com.allnetworktools.ui.tools.Spinner
import com.allnetworktools.ui.tools.ToolEmpty
import com.allnetworktools.ui.tools.ToolError
import com.allnetworktools.ui.tools.mono
import com.allnetworktools.util.fmt
import com.allnetworktools.util.plural
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.allnetworktools.R
import com.allnetworktools.util.S
import com.allnetworktools.util.P

data class GattCharUi(val key: String, val uuid: UUID, val props: Int) {
    val name get() = GattNames.charName(uuid)
    val canRead get() = props and BluetoothGattCharacteristic.PROPERTY_READ != 0
    val canWrite get() = props and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
    val canNotify get() = props and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
    val propLabels
        get() = buildList {
            if (canRead) add("READ")
            if (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("WRITE")
            if (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("WRITE NR")
            if (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("NOTIFY")
            if (props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("INDICATE")
        }
}

data class GattServiceUi(val uuid: UUID, val chars: List<GattCharUi>) {
    val name get() = GattNames.serviceName(uuid)
}

class NotifyLine(val atMs: Long, val uuid: UUID, val value: ByteArray)

enum class GattConn { Idle, Connecting, Connected, Lost }

private val IdentityChars = setOf(0x2A00, 0x2A01, 0x2A24, 0x2A26, 0x2A29)

private val GattSteps = listOf(S(R.string.gatt_connection), S(R.string.mtu_negotiation), S(R.string.service_discovery), S(R.string.reading_values))

class GattController(private val context: Context, private val scope: CoroutineScope, private val identities: com.allnetworktools.data.BleIdentityStore? = null) {
    var address by mutableStateOf<String?>(null)
    var phase by mutableStateOf(Phase.Idle)
    var conn by mutableStateOf(GattConn.Idle)
    var step by mutableIntStateOf(0)
    var mtu by mutableIntStateOf(23)
    var phy by mutableStateOf<Int?>(null)
    var error by mutableStateOf<String?>(null)
    var elapsedS by mutableFloatStateOf(0f)
    val services = mutableStateListOf<GattServiceUi>()
    val values = mutableStateMapOf<String, ByteArray>()
    val flashes = mutableStateMapOf<String, Int>()
    val notifying = mutableStateMapOf<String, Boolean>()
    val expanded = mutableStateMapOf<UUID, Boolean>()
    val log = mutableStateListOf<NotifyLine>()
    private var client: GattClient? = null
    private val handles = mutableMapOf<String, BluetoothGattCharacteristic>()
    private var job: Job? = null

    /** Switches to another device; the previous connection is closed. */
    fun open(addr: String?) {
        if (addr == address) return
        disconnect()
        address = addr
        services.clear(); values.clear(); flashes.clear(); notifying.clear(); expanded.clear(); log.clear()
        phase = Phase.Idle
    }

    fun connect() {
        val addr = address ?: return
        disconnect()
        services.clear(); values.clear(); notifying.clear(); log.clear()
        phase = Phase.Running; conn = GattConn.Connecting; step = 0; error = null
        val c = GattClient(context, addr)
        client = c
        job = scope.launch {
            val events = launch {
                c.events.collect { e ->
                    when (e) {
                        is GattEvent.Disconnected -> if (conn == GattConn.Connected) lost(GattNames.status(e.status))
                        is GattEvent.Changed -> {
                            val key = keyOf(e.characteristic)
                            values[key] = e.value
                            flashes[key] = (flashes[key] ?: 0) + 1
                            log.add(0, NotifyLine(System.currentTimeMillis(), e.characteristic.uuid, e.value))
                            while (log.size > 40) log.removeAt(log.lastIndex)
                        }
                    }
                }
            }
            val t0 = SystemClock.elapsedRealtime()
            try {
                c.connect(); step = 1
                mtu = c.requestMtu(); phy = c.readPhy(); step = 2
                val list = c.discover(); step = 3
                handles.clear()
                val ui = list.map { s ->
                    GattServiceUi(s.uuid, s.characteristics.map { ch -> keyOf(ch).also { handles[it] = ch }.let { GattCharUi(it, ch.uuid, ch.properties) } })
                }
                services.addAll(ui)
                ui.flatMap { it.chars }.filter { it.canRead }.sortedBy { if (GattNames.uuid16(it.uuid) in IdentityChars) 0 else 1 }.take(24).forEach { ch ->
                    runCatching { c.read(handles.getValue(ch.key)) }.onSuccess { values[ch.key] = it }
                }
                step = 4
                learnIdentity(addr, ui)
                elapsedS = (SystemClock.elapsedRealtime() - t0) / 1000f
                conn = GattConn.Connected
                ui.firstOrNull { GattNames.uuid16(it.uuid) !in setOf(0x1800, 0x1801) }?.let { expanded[it.uuid] = true }
                phase = if (ui.isEmpty()) Phase.Empty else Phase.Results
            } catch (e: GattException) {
                events.cancel()
                lost(e.message ?: GattNames.status(e.status))
            }
        }
    }

    /** What this connection revealed feeds the scanner's identification. */
    private fun learnIdentity(addr: String, ui: List<GattServiceUi>) {
        val store = identities ?: return
        fun text(uuid: Int) = ui.flatMap { it.chars }.firstOrNull { GattNames.uuid16(it.uuid) == uuid }?.let { values[it.key] }?.let(com.allnetworktools.data.BleIdentifier::text)
        val appearance = ui.flatMap { it.chars }.firstOrNull { GattNames.uuid16(it.uuid) == 0x2A01 }?.let { values[it.key] }?.takeIf { it.size >= 2 }?.let { (it[0].toInt() and 0xFF) or ((it[1].toInt() and 0xFF) shl 8) }
        store.put(
            addr,
            com.allnetworktools.data.GattIdentity(
                text(0x2A00), appearance, text(0x2A29), text(0x2A24), text(0x2A26), ui.mapNotNull { GattNames.uuid16(it.uuid) }.toSet(),
            ),
        )
    }

    private fun lost(message: String) {
        error = message
        conn = GattConn.Lost
        phase = Phase.Error
        client?.close(); client = null
    }

    fun disconnect() {
        job?.cancel(); job = null
        client?.close(); client = null
        notifying.clear()
        conn = GattConn.Idle
        if (phase == Phase.Running) phase = Phase.Idle
    }

    private fun keyOf(c: BluetoothGattCharacteristic) = "${c.service.uuid}/${c.uuid}/${c.instanceId}"

    fun read(key: String) {
        val c = client ?: return
        val h = handles[key] ?: return
        scope.launch {
            runCatching { c.read(h) }
                .onSuccess { values[key] = it; flashes[key] = (flashes[key] ?: 0) + 1 }
                .onFailure { error = it.message }
        }
    }

    fun write(key: String, bytes: ByteArray, onDone: (Boolean) -> Unit) {
        val c = client ?: return
        val h = handles[key] ?: return
        scope.launch {
            val ok = runCatching { c.write(h, bytes) }.isSuccess
            if (ok && h.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) runCatching { c.read(h) }.onSuccess { values[key] = it; flashes[key] = (flashes[key] ?: 0) + 1 }
            onDone(ok)
        }
    }

    fun toggleNotify(key: String) {
        val c = client ?: return
        val h = handles[key] ?: return
        val on = notifying[key] != true
        notifying[key] = on
        scope.launch {
            runCatching { c.setNotify(h, on) }.onFailure { notifying[key] = !on; error = it.message }
        }
    }

    val subscribed get() = notifying.values.count { it }

    fun report(): String = buildString {
        appendLine("GATT $address · MTU $mtu")
        services.forEach { s ->
            appendLine("${s.name} (${GattNames.short(s.uuid)})")
            s.chars.forEach { ch ->
                append("  ${ch.name} (${GattNames.short(ch.uuid)}) [${ch.propLabels.joinToString(",")}]")
                values[ch.key]?.let { append(" = ${GattNames.hex(it)}"); GattNames.decode(ch.uuid, it)?.let { d -> append(" → $d") } }
                appendLine()
            }
        }
    }
}

private fun phyLabel(p: Int?) = when (p) {
    1 -> "LE 1M"; 2 -> "LE 2M"; 3 -> "LE Coded"; else -> null
}

private fun flagsLabel(f: Int): String = buildList {
    if (f and 0x01 != 0) add(S(R.string.le_limited))
    if (f and 0x02 != 0) add(S(R.string.le_general))
    if (f and 0x04 != 0) add(S(R.string.br_edr_not_supported))
} .joinToString(" · ").ifEmpty { "0x%02X".format(f) }

/** Parses "0A 1B" / "0x0a1b" or plain text depending on [hex]. */
fun parsePayload(input: String, hex: Boolean): ByteArray? {
    if (!hex) return input.toByteArray(Charsets.UTF_8).takeIf { it.isNotEmpty() }
    val h = input.replace("0x", "", ignoreCase = true).filter { !it.isWhitespace() && it != ':' && it != '-' }
    if (h.isEmpty() || h.length % 2 != 0 || !h.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
    return ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GattTool(c: GattController, address: String?, device: BleDevice?, bonded: Boolean) {
    val actions = LocalActions.current
    val haptics = AntTheme.haptics
    val acc = AntTheme.accent
    LaunchedEffect(address) { c.open(address) }
    LaunchedEffect(c.phase) {
        when (c.phase) {
            Phase.Results -> haptics.confirm()
            Phase.Error -> haptics.longPress()
            else -> Unit
        }
    }
    if (c.phase == Phase.Results) com.allnetworktools.ui.pages.TopBarAction(Sym.IosShare) { actions.share("GATT ${device?.displayName ?: address}", c.report()) }
    val connect = { haptics.confirm(); c.connect() }
    PageColumn {
        HeroCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                LeadingIcon(device?.icon ?: Sym.Bluetooth, acc.accent, acc.onAccent, 56.dp, RoundedCornerShape(20.dp), 28.dp)
                Column(Modifier.weight(1f)) {
                    Text(device?.displayName ?: S(R.string.ble_device), style = gs(24, 30, 500), maxLines = 1)
                    Text(listOfNotNull(address, device?.model?.takeIf { device.name != null && device.confidence >= 50 }, device?.maker).joinToString(" · "), style = mono(12, 16), maxLines = 1)
                }
                if (device != null) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(fmt(device.rssi), style = gs(22, 26, 500, tnum = true))
                        Text("dBm", style = rf(11, 14))
                    }
                }
            }
            FlowRow(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when (c.conn) {
                    GattConn.Connected -> HeroChip(S(R.string.connected), acc.accent)
                    GattConn.Connecting -> HeroChip(S(R.string.connecting), acc.accent, blink = true)
                    GattConn.Lost -> HeroChip(S(R.string.connection_lost), cs.error)
                    GattConn.Idle -> HeroChip(S(R.string.not_connected), cs.outline)
                }
                if (c.conn == GattConn.Connected) {
                    HeroChip("MTU ${c.mtu}", cs.outline)
                    phyLabel(c.phy)?.let { HeroChip("PHY $it", cs.outline) }
                }
                HeroChip(if (bonded) S(R.string.bonded) else S(R.string.not_bonded), cs.outline)
            }
            val (label, icon) = when (c.conn) {
                GattConn.Connected -> S(R.string.disconnect) to Sym.LinkOff
                GattConn.Connecting -> S(R.string.cancel) to Sym.Close
                else -> S(R.string.connect) to Sym.Link
            }
            PillButton(
                label,
                { if (c.conn == GattConn.Connected || c.conn == GattConn.Connecting) { haptics.segment(); c.disconnect() } else connect() },
                Modifier.fillMaxWidth().padding(top = 16.dp), icon = icon, height = 48.dp,
                bg = if (c.conn == GattConn.Connected || c.conn == GattConn.Connecting) cs.surface else acc.accent,
                fg = if (c.conn == GattConn.Connected || c.conn == GattConn.Connecting) acc.accent else acc.onAccent,
            )
        }
        when (c.phase) {
            Phase.Idle -> {
                IdentityCard(device)
                AdvertCard(device)
                FramesCard(device)
            }
            Phase.Running -> StepsCard(c)
            Phase.Error -> ToolError(Sym.LinkOff, S(R.string.connection_lost), c.error ?: S(R.string.the_device_no_longer_answers), S(R.string.reconnect), connect)
            Phase.Empty -> ToolEmpty(Sym.AccountTree, S(R.string.no_service_exposed), S(R.string.the_connection_worked_but_the_device), S(R.string.run_discovery_again), connect)
            Phase.Results -> {
                IdentityCard(device)
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.Bottom) {
                    Text(S(R.string.gatt_services, c.services.size), Modifier.weight(1f), style = rf(14, 20, 600), color = acc.accent)
                    Text(S(R.string.discovered_in_s, fmt(c.elapsedS, 1)), style = rf(12, 16), color = cs.onSurfaceVariant)
                }
                c.services.forEachIndexed { i, s -> ServiceCard(c, s, Modifier.rise(i)) }
                if (c.subscribed > 0 || c.log.isNotEmpty()) NotifyLog(c)
            }
        }
    }
}

@Composable
internal fun AdvertCard(device: BleDevice?) {
    InfoList(S(R.string.advertising_data)) {
        if (device == null) {
            Text(
                S(R.string.device_out_of_scan_range_its),
                Modifier.padding(vertical = 12.dp), style = rf(14, 20), color = cs.onSurfaceVariant,
            )
            return@InfoList
        }
        InfoRow("Flags", device.flags?.let(::flagsLabel) ?: "—")
        InfoRow(S(R.string.advertised_services), device.services.joinToString(", ").ifEmpty { S(R.string.none) })
        InfoRow(S(R.string.manufacturer_data), device.manufacturerHex?.let { if (it.length > 26) it.take(26) + "…" else it } ?: S(R.string.none_2))
        InfoRow(S(R.string.tx_power), device.txPower?.let { "$it dBm" } ?: S(R.string.not_advertised))
        InfoRow(S(R.string.observed_interval), device.intervalMs?.let { "≈ $it ms" } ?: "—")
        InfoRow("Connectable", if (device.connectable) "Oui" else "Non")
        InfoRow(S(R.string.address), device.addressNote)
        InfoRow(S(R.string.advertising), if (device.extended) S(R.string.ble_5_extended) else S(R.string.classic_legacy))
    }
}

@Composable
internal fun IdentityCard(d: BleDevice?) {
    if (d == null) return
    val acc = AntTheme.accent
    SectionCard(shape = RoundedCornerShape(28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LeadingIcon(d.icon, if (d.isUnknown) cs.surfaceContainerHighest else acc.container, if (d.isUnknown) cs.onSurfaceVariant else acc.onContainer, 48.dp, RoundedCornerShape(16.dp), 26.dp)
            Column(Modifier.weight(1f)) {
                Text(if (d.isUnknown) S(R.string.unidentified_device) else d.model ?: d.kind.label, style = rf(16, 22, 600), maxLines = 2)
                Text(listOfNotNull(d.kind.label.takeIf { !d.isUnknown }, d.maker).joinToString(" · ").ifEmpty { S(R.string.no_clue_in_the_advertisement) }, style = rf(13, 18), color = cs.onSurfaceVariant)
            }
            if (!d.isUnknown) {
                Box(Modifier.height(28.dp).clip(RoundedCornerShape(14.dp)).background(if (d.guessed) cs.surfaceContainerHighest else acc.accent).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                    Text(if (d.guessed) S(R.string.likely, d.confidence) else "${d.confidence} %", style = rf(12, 16, 700), color = if (d.guessed) cs.onSurface else acc.onAccent)
                }
            }
        }
        if (d.isUnknown) {
            Text(
                if (d.connectable) S(R.string.connect_to_read_its_name_model)
                else S(R.string.this_device_is_not_connectable_and),
                Modifier.padding(top = 10.dp), style = rf(13, 18), color = cs.onSurfaceVariant,
            )
        }
        if (d.evidence.isNotEmpty()) {
            Text(S(R.string.why), Modifier.padding(top = 12.dp, bottom = 4.dp), style = rf(13, 18, 600), color = acc.accent)
            d.evidence.forEach { Text("• $it", style = rf(13, 18), color = cs.onSurfaceVariant) }
        }
        if (d.fromGatt) Text(S(R.string.identity_read_by_connecting_gatt), Modifier.padding(top = 8.dp), style = rf(12, 16, 600), color = acc.accent)
        if (d.details.isNotEmpty()) {
            Column(Modifier.padding(top = 6.dp)) { d.details.forEach { (k, v) -> InfoRow(k, v) } }
        }
    }
}

@Composable
internal fun FramesCard(d: BleDevice?) {
    if (d == null || d.ads.isEmpty()) return
    InfoList(S(R.string.advertising_frames, d.ads.size)) {
        d.ads.forEach { s -> InfoRow("0x%02X · %s".format(s.type, com.allnetworktools.data.Ad.typeName(s.type)), com.allnetworktools.data.Ad.describe(s)) }
    }
}

@Composable
private fun StepsCard(c: GattController) {
    val acc = AntTheme.accent
    SectionCard {
        GattSteps.forEachIndexed { i, label ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                    when {
                        i < c.step -> Symbol(Sym.CheckCircle, size = 24.dp, filled = true, tint = acc.accent)
                        i == c.step -> Spinner(22.dp)
                        else -> Symbol(Sym.RadioUnchecked, size = 24.dp, tint = cs.outline)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(label, style = rf(15, 20, if (i == c.step) 600 else 500), color = if (i > c.step) cs.onSurfaceVariant else cs.onSurface)
                    val detail = when {
                        i == 1 && i < c.step -> S(R.string.bytes, c.mtu) + (phyLabel(c.phy)?.let { " · PHY $it" } ?: "")
                        i == 2 && i < c.step -> "${c.services.size} ${P(R.plurals.p_services, c.services.size)}"
                        else -> null
                    }
                    if (detail != null) Text(detail, style = rf(12, 16), color = cs.onSurfaceVariant)
                }
            }
        }
        IndeterminateBar(Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun ServiceCard(c: GattController, s: GattServiceUi, modifier: Modifier) {
    val acc = AntTheme.accent
    val open = c.expanded[s.uuid] == true
    val rot by animateFloatAsState(if (open) 90f else 0f, tween(250), label = "chevron")
    SectionCard(modifier, padding = PaddingValues(0.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { c.expanded[s.uuid] = !open }.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LeadingIcon(GattNames.serviceIcon(s.uuid), acc.container, acc.onContainer, 40.dp, RoundedCornerShape(14.dp), 22.dp)
            Column(Modifier.weight(1f)) {
                Text(s.name, style = rf(15, 20, 600), maxLines = 1)
                Text("${GattNames.short(s.uuid)} · ${s.chars.size} ${P(R.plurals.p_characteristics, s.chars.size)}", style = rf(12, 16), color = cs.onSurfaceVariant)
            }
            Symbol(Sym.ChevronRight, Modifier.graphicsLayer { rotationZ = rot }, size = 24.dp, tint = cs.onSurfaceVariant)
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                s.chars.forEach { ch -> CharRow(c, ch) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CharRow(c: GattController, ch: GattCharUi) {
    val acc = AntTheme.accent
    val actions = LocalActions.current
    val haptics = AntTheme.haptics
    var writing by remember { mutableStateOf(false) }
    Hairline()
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column {
            Text(ch.name, style = rf(14, 20, 600))
            Text(GattNames.short(ch.uuid), style = mono(11, 16), color = cs.onSurfaceVariant)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ch.propLabels.forEach { p ->
                Box(Modifier.height(22.dp).clip(RoundedCornerShape(6.dp)).background(cs.surfaceContainerHighest).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                    Text(p, style = rf(10, 14, 700, tracking = 0.4f), color = cs.onSurfaceVariant)
                }
            }
        }
        val value = c.values[ch.key]
        if (value != null) {
            val base = cs.surfaceContainerHigh
            val flash = remember { Animatable(base) }
            val flashes = c.flashes[ch.key] ?: 0
            val hi = acc.container
            LaunchedEffect(flashes) {
                if (flashes > 0) { flash.snapTo(hi); flash.animateTo(base, tween(700)) } else flash.snapTo(base)
            }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(flash.value)
                    .clickable { actions.copy(S(R.string.value), GattNames.hex(value)) }.padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(GattNames.hex(value), style = mono(12, 18), maxLines = 3)
                GattNames.decode(ch.uuid, value)?.let { Text(it, style = rf(13, 18, 600), color = acc.accent, maxLines = 2) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ch.canRead) PillButton(S(R.string.read), { haptics.tick(); c.read(ch.key) }, icon = Sym.Download, height = 36.dp, outlined = true, bg = acc.accent)
            if (ch.canWrite) PillButton(S(R.string.write_2), { writing = !writing }, icon = Sym.Edit, height = 36.dp, outlined = true, bg = acc.accent)
            if (ch.canNotify) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    Text(S(R.string.notify), Modifier.padding(end = 8.dp), style = rf(13, 18, 500), color = cs.onSurfaceVariant)
                    val on = c.notifying[ch.key] == true
                    Switch(
                        on, { haptics.segment(); c.toggleNotify(ch.key) },
                        thumbContent = if (on) ({ Symbol(Sym.Check, size = 16.dp, tint = acc.accent) }) else null,
                        colors = SwitchDefaults.colors(checkedTrackColor = acc.accent, checkedThumbColor = acc.onAccent),
                    )
                }
            }
        }
        AnimatedVisibility(writing, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            WritePanel { bytes -> c.write(ch.key, bytes) { ok -> actions.toast(if (ok) S(R.string.value_written) else S(R.string.write_refused_by_the_device)) } }
        }
    }
}

@Composable
private fun WritePanel(onSend: (ByteArray) -> Unit) {
    val acc = AntTheme.accent
    var hex by remember { mutableStateOf(true) }
    var text by remember { mutableStateOf("") }
    val payload = parsePayload(text, hex)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainer).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SegmentedRow(listOf(true to "Hex", false to S(R.string.text)), hex, { hex = it }, Modifier.fillMaxWidth(), height = 32.dp)
        OutlinedTextField(
            text, { text = it }, Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text(if (hex) "01 A0 FF" else S(R.string.hello)) },
            textStyle = if (hex) mono(14, 20) else rf(14, 20),
            isError = text.isNotEmpty() && payload == null,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(payload?.let { S(R.string.bytes, it.size) } ?: if (text.isEmpty()) "" else S(R.string.invalid_hexadecimal), Modifier.weight(1f), style = rf(12, 16), color = if (text.isNotEmpty() && payload == null) cs.error else cs.onSurfaceVariant)
            PillButton(S(R.string.send), { payload?.let(onSend) }, icon = Sym.Upload, height = 40.dp, enabled = payload != null, bg = acc.accent, fg = acc.onAccent)
        }
    }
}

@Composable
private fun NotifyLog(c: GattController) {
    val acc = AntTheme.accent
    val fmtTime = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainerLowest)
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(24.dp)).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (c.subscribed > 0) BlinkDot(acc.accent, 8.dp, 1200) else Box(Modifier.size(8.dp).clip(CircleShape).background(cs.outline))
            Text("Notifications", Modifier.weight(1f), style = rf(14, 20, 600), color = acc.accent)
            Text("${c.subscribed} ${P(R.plurals.p_subscribed, c.subscribed)}", style = rf(12, 16), color = cs.onSurfaceVariant)
        }
        if (c.log.isEmpty()) Text(S(R.string.waiting_for_data), Modifier.padding(top = 8.dp), style = rf(13, 18), color = cs.onSurfaceVariant)
        c.log.take(12).forEachIndexed { i, l ->
            Row(Modifier.padding(top = if (i == 0) 10.dp else 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(fmtTime.format(Date(l.atMs)), style = mono(11, 18), color = cs.onSurfaceVariant)
                Text(GattNames.short(l.uuid), style = mono(11, 18), color = acc.accent)
                Text(GattNames.decode(l.uuid, l.value) ?: GattNames.hex(l.value), Modifier.weight(1f), style = mono(11, 18).copy(fontFamily = FontFamily.Monospace), maxLines = 1)
            }
        }
    }
}
