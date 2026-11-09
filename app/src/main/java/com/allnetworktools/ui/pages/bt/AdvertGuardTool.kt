package com.allnetworktools.ui.pages.bt

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.allnetworktools.R
import com.allnetworktools.data.AdvertFinding
import com.allnetworktools.data.AdvertLevel
import com.allnetworktools.data.AdvertVerdict
import com.allnetworktools.data.AdvertWatch
import com.allnetworktools.data.BleDevice
import com.allnetworktools.ui.components.Hairline
import com.allnetworktools.ui.components.LeadingIcon
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.pages.TopBarAction
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.HeroCard
import com.allnetworktools.ui.tools.HeroChip
import com.allnetworktools.ui.tools.ToolEmpty
import com.allnetworktools.util.S

private fun calm() = AdvertVerdict(AdvertLevel.Calm, 0, 0, emptyList(), emptyList(), emptyList())

/**
 * Watches for the pop-up flooding attack: pairing and setup cards pushed at the phone, one after
 * another. The page only recognises what the scanner already receives; the top-bar action starts over.
 */
@Composable
fun AdvertGuardTool(devices: List<BleDevice>) {
    val watch = remember { AdvertWatch() }
    var verdict by remember { mutableStateOf(calm()) }
    var peak by remember { mutableStateOf<AdvertVerdict?>(null) }
    var peakAgeMs by remember { mutableLongStateOf(0L) }
    val reset = remember {
        {
            watch.reset()
            verdict = calm()
            peak = null
        }
    }
    TopBarAction(Sym.RestartAlt, reset)
    LaunchedEffect(devices) {
        val now = SystemClock.elapsedRealtime()
        watch.observe(devices, now)
        verdict = watch.assess(now)
        peak = watch.peak
        peakAgeMs = now - watch.peakAtMs
    }

    PageColumn {
        HeroCard {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(verdict.sources.toString(), style = gs(56, 60, 500, -1.5f, tnum = true))
                Text(S(R.string.advert_sources), Modifier.padding(bottom = 8.dp), style = rf(18, 24, 500))
            }
            Text(S(R.string.advert_guard_blurb), style = rf(14, 20))
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeroChip(verdict.level.label, levelColor(verdict.level), blink = verdict.level != AdvertLevel.Calm)
                HeroChip(S(R.string.advert_advertisers_n, verdict.advertisers), AntTheme.net.good)
            }
        }

        // A burst is over in seconds and the window forgets it a minute later; what it was stays here.
        peak?.takeIf { it.level.ordinal > verdict.level.ordinal }?.let { p ->
            val minutes = (peakAgeMs / 60_000L).toInt()
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(S(R.string.advert_earlier), Modifier.weight(1f), style = rf(16, 22, 600))
                    Text(
                        if (minutes < 1) S(R.string.advert_ago_now) else S(R.string.advert_ago_min, minutes),
                        style = rf(12, 16), color = cs.onSurfaceVariant,
                    )
                }
                Text(p.level.label, Modifier.padding(top = 4.dp), style = rf(14, 20, 600), color = levelColor(p.level))
                p.findings.forEach { f ->
                    Text("${f.abuse.label} — ${f.detail}", Modifier.padding(top = 6.dp), style = rf(13, 18), color = cs.onSurfaceVariant)
                }
            }
        }

        if (verdict.findings.isEmpty() && peak == null) {
            ToolEmpty(Sym.Shield, S(R.string.advert_nothing), S(R.string.advert_nothing_body), null)
        }

        verdict.findings.forEach { FindingCard(it, verdict.level) }

        if (verdict.frames.isNotEmpty()) {
            SectionCard {
                Text(S(R.string.advert_frames), style = rf(16, 22, 600))
                verdict.frames.forEach { hit ->
                    Text(hit.title, Modifier.padding(top = 6.dp), style = rf(13, 18), color = cs.onSurfaceVariant)
                }
            }
        }

        if (verdict.suspects.isNotEmpty()) {
            SectionCard {
                Text(S(R.string.advert_addresses), style = rf(16, 22, 600))
                verdict.suspects.forEach { a ->
                    Text(a, Modifier.padding(top = 6.dp), style = rf(13, 18, tnum = true), color = cs.onSurfaceVariant)
                }
            }
        }

        SectionCard {
            Text(S(R.string.advert_explain), style = rf(13, 18), color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun FindingCard(f: AdvertFinding, level: AdvertLevel) {
    val tint = levelColor(level)
    SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LeadingIcon(Sym.Warning, cs.surfaceContainerHighest, tint, 44.dp, RoundedCornerShape(14.dp), 24.dp)
            Column(Modifier.weight(1f)) {
                Text(f.abuse.label, style = rf(16, 22, 600), maxLines = 2)
                Text(f.count.toString(), style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant)
            }
        }
        Hairline(Modifier.padding(top = 10.dp))
        Text(f.detail, Modifier.fillMaxWidth().padding(top = 10.dp), style = rf(13, 18))
    }
}

@Composable
private fun levelColor(level: AdvertLevel): Color = when (level) {
    AdvertLevel.Attack -> cs.error
    AdvertLevel.Suspect -> AntTheme.net.fair
    AdvertLevel.Calm -> AntTheme.net.good
}
