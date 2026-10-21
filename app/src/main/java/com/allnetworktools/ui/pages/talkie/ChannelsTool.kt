package com.allnetworktools.ui.pages.talkie

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.data.radio.RadioChannel
import com.allnetworktools.data.radio.RadioPower
import com.allnetworktools.data.radio.RadioPreset
import com.allnetworktools.data.radio.RadioPresets
import com.allnetworktools.data.radio.RadioLimits
import com.allnetworktools.data.radio.RadioTones
import com.allnetworktools.data.radio.Tone
import com.allnetworktools.ui.components.AntFilterChip
import com.allnetworktools.ui.components.Hairline
import com.allnetworktools.ui.components.PillButton
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.SegmentedRow
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.components.TechChip
import com.allnetworktools.ui.components.TextAction
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.HeroCard
import com.allnetworktools.ui.tools.HeroChip
import com.allnetworktools.ui.tools.ProgressBar
import com.allnetworktools.util.plural
import java.util.Locale
import kotlin.math.roundToLong
import com.allnetworktools.R
import com.allnetworktools.util.S
import com.allnetworktools.util.P

internal fun fmtMhz(hz: Long): String {
    val s = "%.6f".format(Locale.getDefault(), hz / 1e6).trimEnd('0')
    val dec = s.substringAfter(',', "")
    return if (dec.length < 3) s + "0".repeat(3 - dec.length) else s
}

private fun parseDecimal(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()

private fun summary(ch: RadioChannel): String = buildList {
    add(fmtMhz(ch.rxHz) + " MHz")
    ch.offsetHz?.takeIf { it != 0L }?.let { add("%+.3f".format(Locale.getDefault(), it / 1e6).let { s -> "$s MHz" }) }
    if (ch.txTone != Tone.None) add("TX ${ch.txTone.label}")
    if (ch.rxTone != Tone.None) add("RX ${ch.rxTone.label}")
    add(if (ch.wide) "25 kHz" else S(R.string.n_12_5_khz))
}.joinToString(" · ")

/** Read the memories of a handheld through its programming cable, edit them, write them back. */
@Composable
fun ChannelsTool(vm: MainViewModel) {
    val c = vm.tools.radioChannels
    val cable by vm.radioCable.collectAsStateWithLifecycle()
    val acc = AntTheme.accent
    var editing by remember { mutableStateOf<RadioChannel?>(null) }
    var presets by remember { mutableStateOf(false) }
    var confirmWrite by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val slotList = c.channels.mapIndexedNotNull { i, ch -> ch?.let { i to it } }

    PageColumn {
        HeroCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(20.dp)).background(acc.accent), contentAlignment = Alignment.Center) {
                    Symbol(Sym.Radio, size = 28.dp, filled = true, tint = acc.onAccent)
                }
                Column(Modifier.weight(1f)) {
                    Text(c.detected?.label ?: S(R.string.handheld_radio), style = gs(22, 28, 500), maxLines = 1)
                    Text(
                        "${c.count} ${P(R.plurals.p_channels, c.count)}" + (c.detected?.let { S(R.string.of_2, it.slots) } ?: "") +
                            if (c.changed > 0 && c.count > 0) S(R.string.to_write, c.changed) else "",
                        style = rf(14, 20),
                    )
                }
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeroChip(cable?.let { S(R.string.cable, it.name) } ?: S(R.string.no_usb_cable), if (cable != null) AntTheme.net.good else cs.outline)
                if (c.detected == null && cable != null) HeroChip(S(R.string.model_recognised_when_reading), cs.outline)
            }
            if (c.busy) {
                Text(c.phase.label, Modifier.padding(top = 14.dp), style = rf(14, 20, 600))
                ProgressBar(c.progress, Modifier.padding(top = 8.dp))
            } else {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(S(R.string.read), { cable?.let(c::read) }, Modifier.weight(1f), icon = Sym.Download, height = 48.dp, enabled = cable != null)
                    PillButton(
                        S(R.string.write_2), { confirmWrite = true }, Modifier.weight(1f), icon = Sym.Upload, height = 48.dp,
                        bg = cs.surface, fg = acc.accent, enabled = cable != null && (c.count > 0 || c.changed > 0),
                    )
                }
                if (c.canRestore) {
                    Box(Modifier.padding(top = 4.dp)) { TextAction(S(R.string.restore_the_state_before_writing), { cable?.let(c::restore) }, trailingIcon = Sym.Undo) }
                }
            }
        }

        c.message?.let { m ->
            val (icon, tint) = when (m.kind) {
                ProgMessage.Kind.Success -> Sym.CheckCircle to AntTheme.net.good
                ProgMessage.Kind.Error -> Sym.Warning to cs.error
                ProgMessage.Kind.Info -> Sym.Info to acc.accent
            }
            SectionCard {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Symbol(icon, size = 22.dp, filled = true, tint = tint)
                    Text(m.text, style = rf(14, 20), color = if (m.kind == ProgMessage.Kind.Error) cs.error else cs.onSurface)
                }
            }
        }

        if (cable == null && c.count == 0) {
            SectionCard {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Symbol(Sym.Cable, size = 22.dp, tint = acc.accent)
                    Text(
                        S(R.string.plug_the_programming_cable_into_the) +
                            S(R.string.headset_socket_of_the_radio_switched),
                        style = rf(13, 18), color = cs.onSurfaceVariant,
                    )
                }
            }
        }

        if (slotList.isNotEmpty()) {
            SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) {
                slotList.forEachIndexed { i, (slot, ch) ->
                    if (i > 0) Hairline()
                    ChannelRow(slot, ch) { editing = ch }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(
                S(R.string.new_channel), { c.firstFree()?.let { editing = RadioChannel(it, 0, 0, "") } }, Modifier.weight(1f),
                icon = Sym.Add, height = 44.dp, outlined = true, bg = acc.accent, enabled = c.firstFree() != null,
            )
            PillButton(S(R.string.presets), { presets = true }, Modifier.weight(1f), icon = Sym.PlaylistAdd, height = 44.dp, outlined = true, bg = acc.accent)
        }
        if (c.count > 0 && !c.busy) TextAction(S(R.string.clear_all), { confirmClear = true }, color = cs.error, trailingIcon = Sym.Delete)

        SectionCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Info, size = 22.dp, tint = acc.accent)
                Text(
                    S(R.string.the_app_never_makes_the_radio) +
                        S(R.string.settings_keys_dtmf_is_untouched_and) +
                        S(R.string.amateur_bands_need_an_amateur_radio) +
                        S(R.string.the_list_is_never_saved_it),
                    style = rf(13, 18), color = cs.onSurfaceVariant,
                )
            }
        }
    }

    editing?.let { ch ->
        ChannelEditor(
            spec = c.limits, initial = ch, exists = c.channels[ch.slot] != null,
            onSave = { c.edit(it); editing = null },
            onDelete = { c.clear(ch.slot); editing = null },
            onDismiss = { editing = null },
        )
    }
    if (presets) PresetDialog(c.limits, onAdd = { p, listen -> c.insertPreset(p, listen) }, onDismiss = { presets = false })
    if (confirmWrite) {
        AlertDialog(
            onDismissRequest = { confirmWrite = false },
            icon = { Symbol(Sym.Upload, size = 24.dp, tint = acc.accent) },
            title = { Text(S(R.string.write_to_the_radio)) },
            text = {
                Text(
                    S(R.string.the_radio_will_be_recognised_and, if (c.changed > 0) "${c.changed} ${P(R.plurals.p_channels_changed, c.changed)}" else S(R.string.the_list)) +
                        S(R.string.will_be_written_the_radio_s) +
                        S(R.string.and_you_will_be_able_to),
                    style = rf(14, 20),
                )
            },
            confirmButton = { TextButton({ confirmWrite = false; cable?.let(c::write) }) { Text(S(R.string.write_2)) } },
            dismissButton = { TextButton({ confirmWrite = false }) { Text(S(R.string.cancel)) } },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(S(R.string.clear_the_list)) },
            text = { Text(S(R.string.the_channels_of_the_displayed_list), style = rf(14, 20)) },
            confirmButton = { TextButton({ confirmClear = false; c.clearAll() }) { Text(S(R.string.clear), color = cs.error) } },
            dismissButton = { TextButton({ confirmClear = false }) { Text(S(R.string.cancel)) } },
        )
    }
}

@Composable
private fun ChannelRow(slot: Int, ch: RadioChannel, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(AntTheme.accent.container), contentAlignment = Alignment.Center) {
            Text("${slot + 1}", style = rf(14, 18, 700, tnum = true), color = AntTheme.accent.onContainer)
        }
        Column(Modifier.weight(1f)) {
            Text(ch.name.ifEmpty { fmtMhz(ch.rxHz) + " MHz" }, style = rf(16, 22, 600), maxLines = 1)
            Text(summary(ch), style = rf(12, 16), color = cs.onSurfaceVariant, maxLines = 2)
        }
        if (!ch.transmits) TechChip(S(R.string.listening_4), cs.surfaceContainerHighest, cs.onSurfaceVariant)
        Symbol(Sym.ChevronRight, size = 22.dp, tint = cs.onSurfaceVariant)
    }
}

private enum class TxMode(val label: String) { Simplex("Simplex"), Shift(S(R.string.offset)), ListenOnly(S(R.string.listen_only)) }

/** Form for one memory. */
@Composable
private fun ChannelEditor(spec: RadioLimits, initial: RadioChannel, exists: Boolean, onSave: (RadioChannel) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var rx by remember { mutableStateOf(if (exists) fmtMhz(initial.rxHz) else "") }
    var mode by remember {
        mutableStateOf(
            when {
                !exists -> TxMode.Simplex
                initial.txHz == null -> TxMode.ListenOnly
                initial.txHz == initial.rxHz -> TxMode.Simplex
                else -> TxMode.Shift
            },
        )
    }
    var shift by remember { mutableStateOf(initial.offsetHz?.takeIf { it != 0L }?.let { (it / 1000.0).toString().removeSuffix(".0") } ?: "-600") }
    var rxTone by remember { mutableStateOf(initial.rxTone) }
    var txTone by remember { mutableStateOf(initial.txTone) }
    var power by remember { mutableStateOf(if (initial.power in spec.powers) initial.power else spec.powers.last()) }
    var wide by remember { mutableStateOf(initial.wide) }
    var scan by remember { mutableStateOf(initial.scan) }
    var error by remember { mutableStateOf<String?>(null) }

    fun build(): RadioChannel? {
        val mhz = parseDecimal(rx) ?: run { error = S(R.string.invalid_frequency_in_mhz_for_example); return null }
        val rxHz = (mhz * 1e6).roundToLong()
        if (!spec.inBand(rxHz)) { error = S(R.string.this_frequency_is_outside_the_s, spec.label); return null }
        val txHz = when (mode) {
            TxMode.Simplex -> rxHz
            TxMode.ListenOnly -> null
            TxMode.Shift -> {
                val k = parseDecimal(shift) ?: run { error = S(R.string.invalid_offset_in_khz_for_example); return null }
                rxHz + (k * 1000).roundToLong()
            }
        }
        return RadioChannel(initial.slot, rxHz, txHz, name, rxTone, txTone, power, wide, scan)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(S(R.string.channel, initial.slot + 1)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    name, { name = it.uppercase().take(spec.nameLength) }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(S(R.string.name_characters, spec.nameLength)) },
                )
                OutlinedTextField(
                    rx, { rx = it; error = null }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(S(R.string.receive_mhz)) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Text(S(R.string.transmit), style = rf(13, 18, 600), color = AntTheme.accent.accent)
                SegmentedRow(TxMode.entries.map { it to it.label }, mode, { mode = it }, Modifier.fillMaxWidth(), selectedColor = AntTheme.accent.accent, onSelectedColor = AntTheme.accent.onAccent)
                if (mode == TxMode.Shift) {
                    OutlinedTextField(
                        shift, { shift = it; error = null }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text(S(R.string.transmit_offset_khz)) },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
                if (mode != TxMode.ListenOnly) ToneField(S(R.string.transmit_tone), txTone) { txTone = it }
                ToneField(S(R.string.receive_tone_squelch), rxTone) { rxTone = it }
                Text(S(R.string.power), style = rf(13, 18, 600), color = AntTheme.accent.accent)
                SegmentedRow(spec.powers.map { it to it.label }, power, { power = it }, Modifier.fillMaxWidth(), selectedColor = AntTheme.accent.accent, onSelectedColor = AntTheme.accent.onAccent)
                Text(S(R.string.bandwidth), style = rf(13, 18, 600), color = AntTheme.accent.accent)
                SegmentedRow(listOf(true to S(R.string.wide_25_khz), false to S(R.string.narrow_12_5_khz)), wide, { wide = it }, Modifier.fillMaxWidth(), selectedColor = AntTheme.accent.accent, onSelectedColor = AntTheme.accent.onAccent)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(S(R.string.included_in_scan), Modifier.weight(1f), style = rf(15, 20))
                    Switch(
                        scan, { scan = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = AntTheme.accent.accent, checkedThumbColor = AntTheme.accent.onAccent),
                    )
                }
                error?.let { Text(it, style = rf(13, 18), color = cs.error) }
            }
        },
        confirmButton = { TextButton({ build()?.let(onSave) }) { Text(S(R.string.record)) } },
        dismissButton = {
            Row {
                if (exists) TextButton(onDelete) { Text(S(R.string.delete), color = cs.error) }
                TextButton(onDismiss) { Text(S(R.string.cancel)) }
            }
        },
    )
}

/** None / CTCSS / DCS, then the value among the standard ones. */
@Composable
private fun ToneField(label: String, tone: Tone, onChange: (Tone) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = rf(13, 18, 600), color = AntTheme.accent.accent)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AntFilterChip(S(R.string.none_2), tone == Tone.None, { onChange(Tone.None) })
            AntFilterChip("CTCSS", tone is Tone.Ctcss, { if (tone !is Tone.Ctcss) onChange(Tone.Ctcss(88.5)) })
            AntFilterChip("DCS", tone is Tone.Dcs, { if (tone !is Tone.Dcs) onChange(Tone.Dcs(23)) })
        }
        when (tone) {
            is Tone.Ctcss -> LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(RadioTones.ctcss) { hz -> AntFilterChip("%.1f".format(Locale.getDefault(), hz), tone.hz == hz, { onChange(Tone.Ctcss(hz)) }) }
            }
            is Tone.Dcs -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AntFilterChip("Normal", !tone.inverted, { onChange(tone.copy(inverted = false)) })
                    AntFilterChip(S(R.string.reversed), tone.inverted, { onChange(tone.copy(inverted = true)) })
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(RadioTones.dcs) { code -> AntFilterChip("%03d".format(code), tone.code == code, { onChange(tone.copy(code = code)) }) }
                }
            }
            Tone.None -> Unit
        }
    }
}

@Composable
private fun PresetDialog(spec: RadioLimits, onAdd: (RadioPreset, Boolean) -> PresetResult, onDismiss: () -> Unit) {
    var chosen by remember { mutableStateOf<RadioPreset?>(null) }
    var listenOnly by remember { mutableStateOf(true) }
    var result by remember { mutableStateOf<PresetResult?>(null) }
    val p = chosen
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(p?.title ?: S(R.string.presets)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    result != null -> {
                        val r = result!!
                        Text(
                            "${r.added} ${P(R.plurals.p_channels_added, r.added)}" +
                                (if (r.outOfBand > 0) S(R.string.outside_the_s_bands, r.outOfBand, spec.label) else "") +
                                (if (r.noRoom > 0) S(R.string.with_no_free_slot, r.noRoom) else "") + ".",
                            style = rf(14, 20),
                        )
                    }
                    p == null -> RadioPresets.all.forEach { preset ->
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { chosen = preset; listenOnly = true }.padding(vertical = 8.dp, horizontal = 4.dp),
                        ) {
                            Text(preset.title, style = rf(16, 22, 600))
                            Text(preset.subtitle, style = rf(13, 18), color = cs.onSurfaceVariant)
                        }
                    }
                    else -> {
                        Text(S(R.string.channels_2, p.channels.size, p.subtitle), style = rf(14, 20))
                        Text(p.note, style = rf(13, 18), color = cs.onSurfaceVariant)
                        if (!p.alwaysListenOnly) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(S(R.string.receive_only_4), style = rf(15, 20, 600))
                                    Text(S(R.string.the_radio_will_not_transmit_on), style = rf(12, 16), color = cs.onSurfaceVariant)
                                }
                                Switch(
                                    listenOnly, { listenOnly = it },
                                    colors = SwitchDefaults.colors(checkedTrackColor = AntTheme.accent.accent, checkedThumbColor = AntTheme.accent.onAccent),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                result != null -> TextButton(onDismiss) { Text(S(R.string.close)) }
                p != null -> TextButton({ result = onAdd(p, listenOnly) }) { Text(S(R.string.add_to_the_list)) }
                else -> TextButton(onDismiss) { Text(S(R.string.close)) }
            }
        },
        dismissButton = { if (p != null && result == null) TextButton({ chosen = null }) { Text(S(R.string.back)) } },
    )
}
