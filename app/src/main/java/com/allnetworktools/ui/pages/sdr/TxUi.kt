package com.allnetworktools.ui.pages.sdr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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
 * Transmit settings, the same in every tool that transmits: the call sign and the licence box (needed on the amateur
 * bands), the power, and the reminder that the operator answers for what goes out. [amateur] hides the licence part
 * for the licence-free mesh radios.
 */
@Composable
internal fun TxSettingsCard(amateur: Boolean = true) {
    val t = TxSettings
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Symbol(Sym.Antenna, size = 22.dp, tint = AntTheme.accent.accent)
                Text(S(R.string.tx_title), style = rf(15, 20, 600))
            }
            if (amateur) {
                HostInputField(t.callsign, { t.updateCallsign(it) }, S(R.string.tx_callsign), Sym.Antenna, keyboardType = KeyboardType.Ascii)
                if (t.callsign.isNotEmpty() && !t.validCallsign) Text(S(R.string.tx_callsign_invalid), style = rf(12, 16), color = cs.error)
                AntFilterChip(S(R.string.tx_licence_box), t.licensed, { t.updateLicensed(!t.licensed) })
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
