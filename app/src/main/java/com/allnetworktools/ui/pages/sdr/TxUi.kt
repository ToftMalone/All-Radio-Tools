package com.allnetworktools.ui.pages.sdr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.foundation.clickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.allnetworktools.R
import com.allnetworktools.data.sdr.TxSettings
import com.allnetworktools.ui.components.AntFilterChip
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.HostInputField
import com.allnetworktools.util.S

/**
 * Transmit settings, the same in every tool that transmits: the power and the reminder that the operator answers for
 * what goes out. On the amateur bands the call sign in use is shown with a way to change it; the call sign and the
 * licence box themselves are asked by [LicenceGate] when the user transmits.
 */
@Composable
internal fun TxSettingsCard(amateur: Boolean = true, gate: LicenceGate? = null) {
    val t = TxSettings
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Symbol(Sym.Antenna, size = 22.dp, tint = AntTheme.accent.accent)
                Text(S(R.string.tx_title), style = rf(15, 20, 600))
            }
            if (amateur && gate != null && t.amateurReady) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(S(R.string.tx_callsign_line, t.callsign), Modifier.weight(1f), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
                    androidx.compose.material3.TextButton({ gate.edit() }) { Text(S(R.string.tx_callsign_change)) }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(S(R.string.tx_gain_label), Modifier.weight(1f), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
                Text("${t.txGain} dB", style = rf(14, 18, 700, tnum = true))
            }
            androidx.compose.material3.Slider(
                value = t.txGain.toFloat(), onValueChange = { t.updateGain(Math.round(it)) },
                valueRange = 0f..47f, steps = 46, colors = sliderColors(false, ticks = false),
            )
            AntFilterChip(S(R.string.tx_amp), t.txAmp, { t.updateAmp(!t.txAmp) })
            Text(S(if (amateur) R.string.tx_rules_amateur else R.string.tx_rules_ism), style = rf(12, 16), color = cs.onSurfaceVariant)
        }
    }
}

/** The red line shown while transmitting, or why the last transmission could not go out. */
@Composable
internal fun TxStatus(tx: SdrTransmitter) {
    when {
        tx.transmitting -> Text(S(R.string.tx_on_air), style = rf(14, 20, 700), color = cs.error)
        tx.error != null -> Text(tx.error!!, style = rf(13, 18), color = cs.error)
    }
}

/**
 * Asks for the call sign and the licence box when the user transmits on the amateur bands, and only then: [run] goes
 * straight on when both are already set, else shows the dialog and carries on once they are.
 */
class LicenceGate {
    internal var pending by androidx.compose.runtime.mutableStateOf<(() -> Unit)?>(null)
    internal var open by androidx.compose.runtime.mutableStateOf(false)

    fun run(action: () -> Unit) {
        if (TxSettings.amateurReady) action() else { pending = action; open = true }
    }

    /** Changes the call sign: the dialog, with nothing to send afterwards. */
    fun edit() { pending = null; open = true }
}

/** A [LicenceGate] for this screen, with its dialog. */
@Composable
internal fun rememberLicenceGate(): LicenceGate {
    val gate = androidx.compose.runtime.remember { LicenceGate() }
    if (gate.open) LicenceDialog(gate)
    return gate
}

@Composable
private fun LicenceDialog(gate: LicenceGate) {
    val t = TxSettings
    var call by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(t.callsign) }
    var licensed by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(t.licensed) }
    val valid = com.allnetworktools.data.sdr.TxPolicy.isCallsign(call)
    val close = { gate.open = false; gate.pending = null }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = close,
        icon = { Symbol(Sym.Antenna, size = 26.dp, tint = AntTheme.accent.accent) },
        title = { Text(S(R.string.tx_licence_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HostInputField(call, { v -> call = v.uppercase().filter { it.isLetterOrDigit() || it == '/' || it == '-' }.take(12) }, S(R.string.tx_callsign), Sym.Antenna, keyboardType = KeyboardType.Ascii)
                if (call.isNotEmpty() && !valid) Text(S(R.string.tx_callsign_invalid), style = rf(12, 16), color = cs.error)
                Row(
                    Modifier.fillMaxWidth().clickable { licensed = !licensed },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Checkbox(licensed, { licensed = it })
                    Text(S(R.string.tx_licence_box), style = rf(14, 20))
                }
                Text(S(R.string.tx_rules_amateur), style = rf(12, 16), color = cs.onSurfaceVariant)
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = {
                    t.updateCallsign(call)
                    t.updateLicensed(licensed)
                    val next = gate.pending
                    gate.open = false
                    gate.pending = null
                    next?.invoke()
                },
                enabled = valid && licensed,
            ) { Text(S(R.string.tx_licence_confirm)) }
        },
        dismissButton = { androidx.compose.material3.TextButton(close) { Text(S(R.string.cancel)) } },
    )
}
