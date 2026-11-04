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
import com.allnetworktools.data.sdr.MeshCore
import com.allnetworktools.data.sdr.MeshCoreTracker
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
import kotlinx.coroutines.CoroutineScope

/** MeshCore nodes (adverts), the Public channel and the "#name" channels the user adds, for this session only. */
class MeshCoreController(repo: SdrRepository, scope: CoroutineScope, onAcquire: () -> Unit = {}) : MeshController(repo, scope, onAcquire, "sdr-meshcore") {
    override val band: MeshBand = MeshCore.EU868
    override val tracker = MeshCoreTracker()

    var hashtags by mutableStateOf<List<String>>(emptyList())
        private set

    /** Adds a "#name" channel; false when the name is empty or has spaces. */
    fun addHashtag(raw: String): Boolean {
        val n = raw.trim().removePrefix("#")
        if (n.isEmpty() || n.length > 30 || n.any { it.isWhitespace() }) return false
        hashtags = (hashtags - n) + n
        apply()
        return true
    }

    fun removeHashtag(n: String) { hashtags = hashtags - n; apply() }

    private fun apply() { tracker.channels = listOf(MeshCore.PUBLIC) + hashtags.map { MeshCore.hashtag(it) } }
}

@Composable
fun MeshCoreTool(vm: MainViewModel) {
    val c = vm.tools.meshcore
    val actions = LocalActions.current
    MeshScreen(
        vm, c, "meshcore", Sym.Hub, S(R.string.meshcore_none_yet), S(R.string.meshcore_none_yet_body), S(R.string.meshcore_how_it_works),
    ) {
        Text(S(R.string.mesh_channels), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AntFilterChip("Public", true, {})
            c.hashtags.forEach { h -> AntFilterChip("#$h ✕", true, { c.removeHashtag(h) }) }
        }
        var tag by remember { mutableStateOf("") }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HostInputField(tag, { tag = it }, S(R.string.meshcore_hashtag), Sym.Tag, keyboardType = KeyboardType.Text)
            ToolButtons(
                ToolButton(S(R.string.mesh_add_channel), Sym.Add, BtnKind.Outline) {
                    if (c.addHashtag(tag)) { tag = ""; actions.toast(S(R.string.mesh_channel_added)) } else actions.toast(S(R.string.meshcore_invalid_hashtag))
                },
            )
        }
        Text(S(R.string.mesh_channels_note), style = rf(12, 16), color = cs.onSurfaceVariant)
    }
}
