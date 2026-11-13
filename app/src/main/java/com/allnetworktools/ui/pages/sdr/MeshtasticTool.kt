package com.allnetworktools.ui.pages.sdr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.allnetworktools.MainViewModel
import com.allnetworktools.R
import com.allnetworktools.data.sdr.MeshBand
import com.allnetworktools.data.sdr.Meshtastic
import com.allnetworktools.data.sdr.MeshtasticTracker
import com.allnetworktools.data.sdr.SdrRepository
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.AntFilterChip
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.BtnKind
import com.allnetworktools.ui.tools.HostInputField
import com.allnetworktools.ui.tools.ToolButton
import com.allnetworktools.ui.tools.ToolButtons
import com.allnetworktools.util.S
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Meshtastic nodes and messages on the default channels (EU 868 or EU 433). Channels the user adds with their key are
 * read too, for this session only: nothing is stored.
 */
class MeshtasticController(repo: SdrRepository, scope: CoroutineScope, onAcquire: () -> Unit = {}) : MeshController(repo, scope, onAcquire, "sdr-meshtastic") {
    var bandKey by mutableStateOf("868")
    /** The free frequency (MHz, as typed) and the preset read there; used with the band "free". */
    var frequencyMhz by mutableStateOf("906.875")
    var presetName by mutableStateOf("LongFast")

    fun parsedHz(): Long? = frequencyMhz.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in 400.0..1000.0 }?.let { (it * 1e6).roundToLong() }

    override val band: MeshBand get() = when (bandKey) {
        "433" -> Meshtastic.EU433
        "free" -> Meshtastic.custom(parsedHz() ?: 906_875_000L, Meshtastic.PRESETS.first { it.name == presetName })
        else -> Meshtastic.EU868
    }
    override val tracker = MeshtasticTracker()

    /** Channels added by the user: name and key. */
    var extra by mutableStateOf<List<Meshtastic.Channel>>(emptyList())
        private set

    /** Adds a channel from its name and its key as the apps show it (base64); false when the key is not valid. */
    fun addChannel(name: String, keyBase64: String): Boolean {
        val raw = runCatching { java.util.Base64.getDecoder().decode(keyBase64.trim().replace('-', '+').replace('_', '/')) }.getOrNull() ?: return false
        val key = Meshtastic.expandKey(raw) ?: return false
        val n = name.trim()
        if (n.isEmpty() || n.length > 11) return false
        extra = extra.filterNot { it.name == n } + Meshtastic.Channel(n, key)
        tracker.channels = extra + Meshtastic.publicChannels
        return true
    }

    fun removeChannel(name: String) {
        extra = extra.filterNot { it.name == name }
        tracker.channels = extra + Meshtastic.publicChannels
    }
}

@Composable
fun MeshtasticTool(vm: MainViewModel) {
    val c = vm.tools.meshtastic
    val actions = LocalActions.current
    MeshScreen(
        vm, c, "meshtastic", Sym.Hub, S(R.string.meshtastic_none_yet), S(R.string.meshtastic_none_yet_body), S(R.string.meshtastic_how_it_works),
        canStart = { c.bandKey != "free" || c.parsedHz() != null },
    ) {
        Text(S(R.string.mesh_band), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
        BandChips(listOf("868" to S(R.string.meshtastic_band_868), "433" to S(R.string.meshtastic_band_433), "free" to S(R.string.meshtastic_band_free)), c.bandKey, !c.starting) {
            if (c.running) c.stop()
            c.bandKey = it
        }
        if (c.bandKey == "free") {
            if (!c.running) HostInputField(c.frequencyMhz, { c.frequencyMhz = it }, S(R.string.frequency_mhz), Sym.Antenna, keyboardType = KeyboardType.Decimal)
            BandChips(Meshtastic.PRESETS.map { it.name to it.name }, c.presetName, !c.starting) {
                if (c.running) c.stop()
                c.presetName = it
            }
            Text(
                S(if (c.parsedHz() != null) R.string.meshtastic_free_note else R.string.meshtastic_free_invalid, c.frequencyMhz.replace('.', ',')),
                style = rf(12, 16), color = if (c.parsedHz() != null) cs.onSurfaceVariant else cs.error,
            )
        }
        Text(S(R.string.mesh_channels), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AntFilterChip(S(R.string.meshtastic_default_channels), true, {})
            c.extra.forEach { ch -> AntFilterChip("🔑 ${ch.name} ✕", true, { c.removeChannel(ch.name) }) }
        }
        var name by remember { mutableStateOf("") }
        var key by remember { mutableStateOf("") }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HostInputField(name, { name = it }, S(R.string.meshtastic_channel_name), Sym.Tag, keyboardType = KeyboardType.Text)
            HostInputField(key, { key = it }, S(R.string.meshtastic_channel_key), Sym.Key, keyboardType = KeyboardType.Ascii)
            ToolButtons(
                ToolButton(S(R.string.mesh_add_channel), Sym.Add, BtnKind.Outline) {
                    if (c.addChannel(name, key)) { name = ""; key = ""; actions.toast(S(R.string.mesh_channel_added)) } else actions.toast(S(R.string.meshtastic_invalid_channel))
                },
            )
        }
        Text(S(R.string.mesh_channels_note), style = rf(12, 16), color = cs.onSurfaceVariant)
    }
}
