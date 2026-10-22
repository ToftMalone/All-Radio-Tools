package com.allnetworktools.ui.pages.bt

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.R
import com.allnetworktools.data.bitchat.BitchatHub
import com.allnetworktools.data.bitchat.BitchatStore
import com.allnetworktools.data.bitchat.BleStatus
import com.allnetworktools.data.bitchat.ChatMessage
import com.allnetworktools.data.bitchat.Delivery
import com.allnetworktools.data.bitchat.MeshPeer
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.Hairline
import com.allnetworktools.ui.components.IconCircleButton
import com.allnetworktools.ui.components.LeadingIcon
import com.allnetworktools.ui.components.PillButton
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.SegmentedRow
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.HeroCard
import com.allnetworktools.ui.tools.HeroChip
import com.allnetworktools.ui.tools.HeroTile
import com.allnetworktools.ui.tools.ToolEmpty
import com.allnetworktools.ui.tools.ToolError
import com.allnetworktools.ui.tools.mono
import com.allnetworktools.util.S
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAB_CHAT = 0
private const val TAB_PEERS = 1
private const val TAB_ME = 2

/**
 * BitChat, mesh only: the same Bluetooth LE mesh the official app uses, so this phone relays packets for the
 * others and chats with them (public channel and end-to-end encrypted private messages), without Internet.
 */
@Composable
fun BitchatTool(
    permitted: Boolean,
    bluetoothOn: Boolean,
    onGrant: () -> Unit,
    onEnableBluetooth: () -> Unit,
) {
    val context = LocalContext.current
    val actions = LocalActions.current
    val state by BitchatHub.state.collectAsStateWithLifecycle()
    val ble by BitchatHub.ble.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(TAB_CHAT) }
    var chatWith by rememberSaveable { mutableStateOf<String?>(null) }
    var nickname by remember { mutableStateOf(BitchatHub.nickname(context)) }
    val running = state.running

    // The chat on screen counts as read, and the sender is told.
    DisposableEffect(chatWith) {
        BitchatHub.openChat(chatWith)
        onDispose { BitchatHub.openChat(null) }
    }

    fun toggle(on: Boolean) {
        when {
            !on -> BitchatHub.stop(context)
            !permitted -> onGrant()
            !bluetoothOn -> onEnableBluetooth()
            else -> BitchatHub.start(context)
        }
    }

    PageColumn {
        HeroCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(S(R.string.mesh_engine), style = rf(22, 28, 500))
                    Text(
                        if (running) S(R.string.mesh_engine_on) else S(R.string.mesh_engine_off),
                        style = rf(14, 20),
                    )
                }
                Switch(
                    checked = running, onCheckedChange = ::toggle,
                    thumbContent = if (running) ({ Symbol(Sym.Check, size = 16.dp, tint = cs.primary) }) else null,
                    colors = SwitchDefaults.colors(checkedTrackColor = cs.primary, checkedThumbColor = cs.onPrimary),
                )
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (running) {
                    HeroTile(state.peers.size.toString(), S(R.string.nearby), Modifier.weight(1f))
                    HeroTile(state.links.toString(), S(R.string.bitchat_links), Modifier.weight(1f))
                    HeroTile(state.relayed.toString(), S(R.string.relayed), Modifier.weight(1f))
                } else {
                    HeroChip(S(R.string.mesh_stopped), AntTheme.net.poor)
                }
            }
            if (running) {
                Row(Modifier.padding(top = 12.dp)) {
                    HeroChip(
                        if (state.links > 0) S(R.string.connected_to_the_mesh) else S(R.string.looking_for_nearby_phones),
                        if (state.links > 0) AntTheme.net.good else AntTheme.net.fair, blink = true,
                    )
                }
            }
        }

        if (running && ble == BleStatus.NoAdvertiser) {
            ToolError(Sym.Warning, S(R.string.bluetooth_advertising_unavailable), S(R.string.cannot_advertise_message), S(R.string.ok)) {}
        }

        if (!running) {
            if (!permitted) {
                ToolEmpty(Sym.Hub, S(R.string.nearby_devices_permission_needed), S(R.string.bitchat_permission_body), S(R.string.allow), onGrant)
            } else if (!bluetoothOn) {
                ToolEmpty(Sym.Bluetooth, S(R.string.bluetooth_is_off), S(R.string.turn_bluetooth_on_to_join), S(R.string.turn_on), onEnableBluetooth)
            }
        }

        if (running) {
            SegmentedRow(
                listOf(TAB_CHAT to S(R.string.public_chat), TAB_PEERS to S(R.string.nearby), TAB_ME to S(R.string.me)),
                tab, { tab = it; chatWith = null }, Modifier.fillMaxWidth(),
            )
            when {
                tab == TAB_CHAT -> PublicChat(state.publicChat, state.peerId)
                tab == TAB_PEERS && chatWith != null -> PrivateChat(
                    peer = state.peers.firstOrNull { it.id == chatWith },
                    peerId = chatWith!!,
                    messages = state.privateChats[chatWith].orEmpty(),
                    onBack = { chatWith = null },
                )
                tab == TAB_PEERS -> Peers(state.peers, state.unread) { chatWith = it }
            }
        }
        if (!running || tab == TAB_ME) {
            Identity(
                nickname, state.peerId.ifEmpty { null },
                onNickname = { nickname = it },
                onSave = { BitchatHub.setNickname(context, nickname); actions.toast(S(R.string.saved)) },
                onReset = {
                    BitchatHub.stop(context)
                    BitchatStore(context).reset()
                    actions.toast(S(R.string.new_identity_created))
                },
                onCopy = { id -> actions.copy("ID", id) },
            )
            SectionCard {
                Text(S(R.string.how_it_works), style = rf(16, 22, 600))
                Text(
                    S(R.string.bitchat_how_it_works_body),
                    Modifier.padding(top = 6.dp), style = rf(14, 20), color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

// ---- public channel -------------------------------------------------------------------------------------------

@Composable
private fun PublicChat(messages: List<ChatMessage>, me: String) {
    MessageInput(S(R.string.message_everyone_in_the_mesh)) { BitchatHub.sendPublic(it) }
    if (messages.isEmpty()) {
        ToolEmpty(Sym.Forum, S(R.string.no_message_yet), S(R.string.messages_appear_here), null)
    } else {
        messages.takeLast(100).asReversed().forEach { Bubble(it, showName = true, ticks = false) }
    }
}

// ---- peers ----------------------------------------------------------------------------------------------------

@Composable
private fun Peers(peers: List<MeshPeer>, unread: Set<String>, onOpen: (String) -> Unit) {
    if (peers.isEmpty()) {
        ToolEmpty(Sym.Group, S(R.string.nobody_nearby), S(R.string.peers_appear_here), null)
        return
    }
    SectionCard(padding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp)) {
        peers.forEachIndexed { i, p ->
            if (i > 0) Hairline(Modifier.padding(horizontal = 16.dp))
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(p.id) }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                LeadingIcon(Sym.Person, cs.surfaceContainerHighest, nameColor(p.id))
                Column(Modifier.weight(1f)) {
                    Text(p.nickname, style = rf(16, 22, 600), color = nameColor(p.id), maxLines = 1)
                    Text(
                        (if (p.direct) S(R.string.direct_link) else S(R.string.through_the_mesh)) + " · " + p.id.take(8),
                        style = rf(12, 16), color = cs.onSurfaceVariant, maxLines = 1,
                    )
                }
                if (p.encrypted) Symbol(Sym.Lock, size = 18.dp, tint = AntTheme.net.good)
                if (p.id in unread) Box(Modifier.size(10.dp).clip(CircleShape).background(AntTheme.accent.accent))
                Symbol(Sym.ChevronRight, size = 22.dp, tint = cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PrivateChat(peer: MeshPeer?, peerId: String, messages: List<ChatMessage>, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        IconCircleButton(Sym.ArrowBack, onBack, size = 44.dp)
        Column(Modifier.weight(1f)) {
            Text(peer?.nickname ?: peerId.take(8), style = rf(18, 24, 600), color = nameColor(peerId), maxLines = 1)
            Text(
                if (peer == null) S(R.string.out_of_range) else if (peer.encrypted) S(R.string.end_to_end_encrypted) else S(R.string.encrypted_on_first_message),
                style = rf(12, 16), color = cs.onSurfaceVariant,
            )
        }
        Symbol(Sym.Lock, size = 20.dp, tint = if (peer?.encrypted == true) AntTheme.net.good else cs.onSurfaceVariant)
    }
    MessageInput(S(R.string.private_message), enabled = peer != null) { BitchatHub.sendPrivate(peerId, it) }
    if (messages.isEmpty()) {
        ToolEmpty(Sym.Lock, S(R.string.private_chat), S(R.string.only_you_can_read), null)
    } else {
        messages.takeLast(100).asReversed().forEach { Bubble(it, showName = false, ticks = true) }
    }
}

// ---- identity -------------------------------------------------------------------------------------------------

@Composable
private fun Identity(
    nickname: String, peerId: String?,
    onNickname: (String) -> Unit, onSave: () -> Unit, onReset: () -> Unit, onCopy: (String) -> Unit,
) {
    val context = LocalContext.current
    val id = peerId ?: remember { com.allnetworktools.data.bitchat.Bitchat.peerId(BitchatStore(context).identity().noisePublic) }
    SectionCard {
        Text(S(R.string.nickname), style = rf(14, 20, 600), color = AntTheme.accent.accent)
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainerHigh).padding(horizontal = 16.dp, vertical = 14.dp)) {
                BasicTextField(
                    nickname, onNickname, Modifier.fillMaxWidth(), singleLine = true,
                    textStyle = rf(16, 22).copy(color = cs.onSurface), cursorBrush = SolidColor(AntTheme.accent.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onDone = { onSave() }),
                )
            }
            PillButton(S(R.string.save), onSave, height = 48.dp, enabled = nickname.isNotBlank())
        }
        Hairline(Modifier.padding(vertical = 12.dp))
        Row(Modifier.fillMaxWidth().clickable { onCopy(id) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(S(R.string.peer_id), style = rf(12, 16), color = cs.onSurfaceVariant)
                Text(id, style = mono(15, 22))
            }
            Symbol(Sym.ContentCopy, size = 20.dp, tint = cs.onSurfaceVariant)
        }
        Hairline(Modifier.padding(vertical = 12.dp))
        Row(Modifier.fillMaxWidth().clickable(onClick = onReset), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Symbol(Sym.Delete, size = 22.dp, tint = cs.error)
            Column(Modifier.weight(1f)) {
                Text(S(R.string.new_identity), style = rf(15, 20, 600), color = cs.error)
                Text(S(R.string.new_identity_body), style = rf(12, 16), color = cs.onSurfaceVariant)
            }
        }
    }
}

// ---- pieces ---------------------------------------------------------------------------------------------------

@Composable
private fun MessageInput(hint: String, enabled: Boolean = true, onSend: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    fun send() {
        if (text.isNotBlank() && enabled) { onSend(text); text = "" }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.weight(1f).heightIn(min = 52.dp).clip(RoundedCornerShape(26.dp)).background(cs.surfaceContainerHigh).padding(horizontal = 18.dp, vertical = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isEmpty()) Text(hint, style = rf(15, 22), color = cs.onSurfaceVariant)
            BasicTextField(
                text, { text = it }, Modifier.fillMaxWidth(), enabled = enabled,
                textStyle = rf(15, 22).copy(color = cs.onSurface), cursorBrush = SolidColor(AntTheme.accent.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
            )
        }
        IconCircleButton(Sym.Send, { send() }, size = 52.dp, bg = AntTheme.accent.accent, tint = AntTheme.accent.onAccent, filled = true)
    }
}

@Composable
private fun Bubble(m: ChatMessage, showName: Boolean, ticks: Boolean) {
    val time = remember(m.time) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(m.time)) }
    val acc = AntTheme.accent
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(20.dp, 20.dp, if (m.mine) 6.dp else 20.dp, if (m.mine) 20.dp else 6.dp))
                .background(if (m.mine) acc.container else cs.surfaceContainerLow).padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (showName && !m.mine) Text(m.nickname, style = rf(12, 16, 700), color = nameColor(m.from))
            Text(m.text, style = rf(15, 22), color = if (m.mine) acc.onContainer else cs.onSurface)
            Row(Modifier.align(Alignment.End).padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(time, style = rf(11, 14), color = (if (m.mine) acc.onContainer else cs.onSurfaceVariant).copy(alpha = 0.7f))
                if (ticks && m.mine) {
                    val read = m.delivery == Delivery.Read
                    Symbol(
                        when (m.delivery) { Delivery.Sending -> Sym.Schedule; Delivery.Sent -> Sym.Check; else -> Sym.DoneAll },
                        size = 14.dp, tint = if (read) AntTheme.net.good else acc.onContainer.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

/** Each nickname gets a stable colour from its peer ID, as in the official apps. */
@Composable
private fun nameColor(id: String): Color {
    val hue = ((id.hashCode().toLong() and 0x7fffffff) % 360).toFloat()
    return Color.hsv(hue, 0.55f, if (isSystemInDarkTheme()) 0.92f else 0.58f)
}
