package com.allnetworktools.ui.pages.gnss

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.data.Constellation
import com.allnetworktools.data.Country
import com.allnetworktools.data.FixType
import com.allnetworktools.data.SatGeo
import com.allnetworktools.data.Satellite
import com.allnetworktools.data.WorldMap
import com.allnetworktools.ui.components.EmptyStateCard
import com.allnetworktools.ui.components.Hairline
import com.allnetworktools.ui.components.IconCircleButton
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.SegmentedRow
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.constellationColor
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.HeroCard
import com.allnetworktools.util.fmt
import com.allnetworktools.R
import com.allnetworktools.util.S

/** A satellite with the point of Earth it currently flies over. */
data class SatOverhead(val sat: Satellite, val lat: Double, val lon: Double, val place: String)

enum class SkyView(val label: String) { Sky(S(R.string.sky)), Map(S(R.string.world_map)) }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SkyTool(vm: MainViewModel) {
    val g by vm.gnss.collectAsStateWithLifecycle()
    var view by vm.tools.skyView
    val context = LocalContext.current
    val countries by produceState<List<Country>>(emptyList()) { value = WorldMap.load(context) }
    val observer = (g.location ?: remember { vm.lastKnownLocation() })?.let { it.latitude to it.longitude }
    val overhead = if (observer == null || countries.isEmpty()) emptyList() else g.visible.filter { it.elevation > 0f }.map { s ->
        val (lat, lon) = SatGeo.subPoint(observer.first, observer.second, s)
        SatOverhead(s, lat, lon, WorldMap.countryAt(countries, lat, lon) ?: WorldMap.oceanAt(lat, lon))
    }
    // The world map opens full screen; leaving it goes back to the classic sky.
    var fullMap by remember { mutableStateOf(view == SkyView.Map) }
    var fullSky by remember { mutableStateOf(false) }
    val backToSky = { fullMap = false; view = SkyView.Sky }
    if (fullMap && observer != null && g.visible.isNotEmpty()) {
        com.allnetworktools.ui.components.FullscreenDialog(backToSky) {
            OsmSatelliteMap(observer, overhead, Modifier.fillMaxSize())
            com.allnetworktools.ui.components.PillButton(
                S(R.string.classic_sky), backToSky,
                Modifier.align(Alignment.BottomCenter).windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing).padding(bottom = 32.dp),
                icon = Sym.Radar, height = 48.dp,
            )
        }
    }
    if (fullSky) {
        com.allnetworktools.ui.components.FullscreenDialog({ fullSky = false }) {
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                val side = minOf(maxWidth, maxHeight)
                Box(Modifier.size(side)) { SkyPlot(g.visible, height = side) }
            }
        }
    }
    PageColumn {
        HeroCard {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(g.used.size.toString(), style = gs(48, 52, 500, -1.5f, tnum = true))
                Text("/ ${g.visible.size}", Modifier.padding(bottom = 6.dp), style = rf(18, 24, 500))
                Box(Modifier.weight(1f))
                Text(if (g.fix == FixType.None) S(R.string.searching) else g.fix.label, Modifier.padding(bottom = 8.dp), style = rf(14, 20, 600))
            }
            Text(S(R.string.satellites_used_visible_constellations, g.constellationCount), style = rf(13, 18))
        }
        SegmentedRow(SkyView.entries.map { it to it.label }, view, { view = it; fullMap = it == SkyView.Map }, Modifier.fillMaxWidth(), icons = mapOf(SkyView.Sky to Sym.Radar, SkyView.Map to Sym.Public))
        if (g.visible.isEmpty()) {
            EmptyStateCard(Sym.SatelliteAlt, S(R.string.searching_for_satellites), S(R.string.go_outside_under_an_open_sky))
            return@PageColumn
        }
        when (view) {
            SkyView.Sky -> SectionCard(shape = RoundedCornerShape(32.dp)) {
                Box {
                    SkyPlot(g.visible, height = 360.dp)
                    IconCircleButton(Sym.Fullscreen, { fullSky = true }, Modifier.align(Alignment.TopEnd), size = 36.dp, bg = cs.surfaceContainerHigh, tint = cs.onSurface)
                }
                FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Constellation.entries.forEach { c ->
                        val vis = g.visible.count { it.constellation == c }
                        if (vis > 0) {
                            Row(
                                Modifier.height(32.dp).clip(RoundedCornerShape(10.dp)).background(cs.surfaceContainerHigh).padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Box(Modifier.size(10.dp).clip(CircleShape).background(constellationColor(c)))
                                Text(c.label, style = rf(12, 16, 500))
                                Text("${g.used.count { it.constellation == c }}/$vis", style = rf(12, 16, 700, tnum = true))
                            }
                        }
                    }
                }
                Text(
                    S(R.string.centre_zenith_edge_horizon_filled_satellites),
                    Modifier.padding(top = 10.dp), style = rf(12, 16), color = cs.onSurfaceVariant,
                )
            }
            SkyView.Map -> {
                if (observer == null) {
                    EmptyStateCard(Sym.LocationSearching, S(R.string.unknown_position), S(R.string.the_map_needs_your_position_to))
                    return@PageColumn
                }
                SectionCard(shape = RoundedCornerShape(28.dp), padding = androidx.compose.foundation.layout.PaddingValues(8.dp)) {
                    OsmSatelliteMap(observer, overhead, onFullscreen = { fullMap = true })
                    Text(
                        S(R.string.point_below_each_satellite_computed_from),
                        Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), style = rf(12, 16), color = cs.onSurfaceVariant,
                    )
                }
                SectionCard(padding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
                    Text(S(R.string.above), Modifier.padding(bottom = 4.dp), style = rf(14, 20, 600), color = AntTheme.accent.accent)
                    overhead.sortedWith(compareByDescending<SatOverhead> { it.sat.used }.thenByDescending { it.sat.elevation }).forEach { o ->
                        Hairline()
                        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            val col = constellationColor(o.sat.constellation)
                            Box(
                                Modifier.size(36.dp).clip(CircleShape).background(if (o.sat.used) col else cs.surfaceContainerHighest),
                                contentAlignment = Alignment.Center,
                            ) { Text(o.sat.svid.toString(), style = rf(12, 16, 700), color = if (o.sat.used) cs.surface else col) }
                            Column(Modifier.weight(1f)) {
                                Text(o.place, style = rf(15, 20, 600), maxLines = 1)
                                Text(
                                    "${o.sat.constellation.label} ${o.sat.id} · ${latLon(o.lat, o.lon)}",
                                    style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant, maxLines = 1,
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("${fmt(o.sat.elevation)}°", style = rf(14, 20, 600, tnum = true))
                                Text(S(R.string.elevation), style = rf(11, 14), color = cs.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun latLon(lat: Double, lon: Double) =
    "${fmt(kotlin.math.abs(lat), 0)}° ${if (lat >= 0) "N" else "S"}, ${fmt(kotlin.math.abs(lon), 0)}° ${if (lon >= 0) "E" else "O"}"

