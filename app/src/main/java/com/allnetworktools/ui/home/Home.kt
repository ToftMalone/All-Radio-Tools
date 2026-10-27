package com.allnetworktools.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.allnetworktools.Blocker
import com.allnetworktools.CellUi
import com.allnetworktools.WifiUi
import com.allnetworktools.data.BleDevice
import com.allnetworktools.data.BluetoothSnapshot
import com.allnetworktools.data.Constellation
import com.allnetworktools.data.FixType
import com.allnetworktools.data.GnssState
import com.allnetworktools.model.Network
import com.allnetworktools.ui.components.BlinkDot
import com.allnetworktools.ui.components.IconCircleButton
import com.allnetworktools.ui.components.PulseRing
import com.allnetworktools.ui.components.ShapeBadge
import com.allnetworktools.ui.components.Sparkline
import com.allnetworktools.ui.components.StatusChip
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.components.TechChip
import com.allnetworktools.ui.components.cloverShape
import com.allnetworktools.ui.components.cookieShape
import com.allnetworktools.ui.components.rememberSpin
import com.allnetworktools.ui.components.spinning
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Motion
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.constellationColor
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.util.SignalValue
import com.allnetworktools.util.plural
import com.allnetworktools.util.signalValue
import kotlinx.coroutines.delay
import com.allnetworktools.R
import com.allnetworktools.util.S

/** What the home screen needs: which networks are available. No measurement runs from here. */
data class HomeData(
    val blockers: Map<Network, Blocker?>,
)

/** Cycles 0,1,2 at 1 Hz, for the animated bar icons. */
@Composable
private fun rememberBarsPhase(): Int {
    var t by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000); t = (t + 1) % 3
        }
    }
    return t
}

@Composable
fun HomeScreen(
    data: HomeData,
    selected: Network?,
    cardBounds: SnapshotStateMap<Network, Rect>,
    onSettingsBounds: (Rect) -> Unit,
    onCard: (Network) -> Unit,
    onPair: () -> Unit,
    onBlockerAction: (Blocker) -> Unit,
    onDeselect: () -> Unit,
    onSettings: () -> Unit,
) {
    // The card held down to assemble the pair: the other one shows a "+" until it is tapped.
    var pairing by remember { mutableStateOf<Network?>(null) }
    if (selected == Network.WifiBt) pairing = null
    Box(
        Modifier
            .fillMaxSize()
            .background(cs.surface)
            .clickable(interactionSource = null, indication = null) { pairing = null; onDeselect() },
    ) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text("All Radio Tools", style = gs(32, 40, 500, -0.3f), color = cs.onSurface, maxLines = 1)
                        Text(Greetings.ofThisLaunch, Modifier.padding(top = 4.dp), style = rf(14, 20), color = cs.onSurfaceVariant)
                    }
                    IconCircleButton(
                        Sym.Settings, onSettings,
                        Modifier.onGloballyPositioned { onSettingsBounds(it.boundsInRoot()) },
                        bg = cs.surfaceContainerHigh, tint = cs.onSurfaceVariant,
                    )
                }
                val rows = Network.entries.filter { it.inGrid }.chunked(2)
                // Cards only hold an icon and a short description, so they stay compact.
                val cardH = 184.dp
                val haptics = AntTheme.haptics
                Column(
                    // Padding sits inside the scroll area so the selection outline and scale are not clipped.
                    Modifier.fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val fused = selected == Network.WifiBt
                    // 0: two cards; 1: one tile. The spring overshoots a little, so the tile bumps as the cards meet.
                    val merge by animateFloatAsState(if (fused) 1f else 0f, spring(0.55f, 320f), label = "merge")
                    rows.forEach { pair ->
                        val pairRow = pair.toSet() == PairCards
                        val m = if (pairRow) merge.coerceIn(0f, 1f) else 0f
                        val cards: @Composable () -> Unit = {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp * (1f - m))) {
                                pair.forEachIndexed { i, n ->
                                    val picked = selected == n || pairing == n
                                    // Where the two cards meet, their corners square off as they close the gap.
                                    val inner = 28.dp * (1f - m)
                                    val shape = if (i == 0) RoundedCornerShape(28.dp, inner, inner, 28.dp) else RoundedCornerShape(inner, 28.dp, 28.dp, inner)
                                    NetworkCard(
                                        network = n,
                                        data = data,
                                        selected = picked,
                                        dimmed = (selected != null || pairing != null) && !picked && !(fused && pairRow) && !(pairing != null && n in PairCards),
                                        onClick = {
                                            val armed = pairing
                                            pairing = null
                                            when {
                                                fused && n in PairCards -> onPair()
                                                armed != null && armed != n && n in PairCards -> onPair()
                                                armed == n -> Unit
                                                else -> onCard(n)
                                            }
                                        },
                                        onLongClick = if (n in PairCards && !fused) ({ haptics.segment(); pairing = n }) else null,
                                        plus = pairing != null && pairing != n && n in PairCards,
                                        onAction = onBlockerAction,
                                        shape = if (pairRow) shape else RoundedCornerShape(28.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(cardH)
                                            .onGloballyPositioned {
                                                cardBounds[n] = it.boundsInRoot()
                                                // The pair's page grows out of both cards at once.
                                                val a = cardBounds[Network.Wifi]
                                                val b = cardBounds[Network.Bluetooth]
                                                if (a != null && b != null) cardBounds[Network.WifiBt] = Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))
                                            },
                                    )
                                }
                            }
                        }
                        if (!pairRow) cards() else PairTile(merge, onPair, onDeselect, cards)
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Symbol(Sym.DirectionsCar, size = 16.dp, tint = cs.onSurfaceVariant)
                        Text(S(R.string.wardriving_hint), Modifier.padding(start = 6.dp), style = rf(12, 16), color = cs.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun NetworkCard(
    network: Network,
    data: HomeData,
    selected: Boolean,
    dimmed: Boolean,
    onClick: () -> Unit,
    onAction: (Blocker) -> Unit,
    modifier: Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    onLongClick: (() -> Unit)? = null,
    /** The other card of the pair is held: a "+" to assemble them. */
    plus: Boolean = false,
) {
    val roles = AntTheme.net[network]
    val blocker = data.blockers[network]?.takeUnless { it.soft }
    val scale by animateFloatAsState(if (selected) 1.03f else if (dimmed) 0.96f else 1f, Motion.standard(), label = "cardScale")
    val alpha by animateFloatAsState(if (dimmed) 0.5f else 1f, tween(250, easing = Motion.Emphasized), label = "cardAlpha")
    val outline by animateColorAsState(if (selected) roles.accent else Color.Transparent, tween(200, easing = Motion.Emphasized), label = "outline")
    val elevation by animateDpAsState(if (selected) 10.dp else 0.dp, tween(300, easing = Motion.Emphasized), label = "elev")
    val shadowColor = AntTheme.net.shadow
    val bg by animateColorAsState(if (blocker != null) cs.surfaceContainerHigh else roles.container, tween(250, easing = Motion.Emphasized), label = "cardBg")
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            // The outline and the shadow sit outside the card, so they must stay out of the faded layer below:
            // a layer with alpha is drawn offscreen at the card's bounds, which would cut them down to their corners.
            .drawBehind {
                val inset = 3.dp.toPx() + 1.5.dp.toPx()
                drawRoundRect(
                    outline.copy(alpha = outline.alpha * alpha), Offset(-inset, -inset), Size(size.width + inset * 2, size.height + inset * 2),
                    CornerRadius(28.dp.toPx() + inset), style = Stroke(3.dp.toPx()),
                )
            }
            // Fade the shadow with the card, or it shows through the card while it is half transparent.
            .shadow(elevation * ((alpha - 0.5f) * 2f).coerceIn(0f, 1f), shape, ambientColor = shadowColor, spotColor = shadowColor)
            .graphicsLayer { this.alpha = alpha }
            .clip(shape)
            .background(bg)
            .combinedClickable(onLongClick = onLongClick, onClick = onClick),
    ) {
        if (blocker != null) {
            BlockedCardContent(network, blocker, onAction)
        } else {
            CompositionLocalProvider(LocalContentColor provides roles.onContainer) {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    NetworkCardContent(network)
                }
            }
        }
        val plusScale by animateFloatAsState(if (plus) 1f else 0f, spring(0.5f, 500f), label = "plus")
        if (plusScale > 0.01f) {
            val pair = AntTheme.net.wifiBt
            Box(
                Modifier.align(Alignment.TopEnd).padding(12.dp).size(48.dp)
                    .graphicsLayer { scaleX = plusScale; scaleY = plusScale }
                    .clip(CircleShape).background(pair.accent).clickable(onClick = onClick),
                contentAlignment = Alignment.Center,
            ) { Symbol(Sym.Add, size = 28.dp, tint = pair.onAccent) }
        }
    }
}

/** Static description of each network: nothing here reads the radio. */
private fun description(n: Network) = when (n) {
    Network.Wifi -> S(R.string.nearby_networks_channels_speed_wi_fi)
    Network.Bluetooth -> S(R.string.bluetooth_le_devices_unknown_trackers_and)
    Network.Cellular -> S(R.string.cells_your_carrier_s_towers_and)
    Network.Gnss -> S(R.string.satellites_sky_position_comparison_and_passes)
    Network.Sdr -> S(R.string.hackrf_spectrum_aircraft_ships_radiosondes_fpv)
    Network.WifiBt -> S(R.string.pair_subtitle)
}

@Composable
private fun CardIcon(network: Network) {
    val roles = AntTheme.net[network]
    when (network) {
        Network.Wifi -> {
            val phase = rememberBarsPhase()
            ShapeBadge(listOf(Sym.Wifi1Bar, Sym.Wifi2Bar, Sym.Wifi)[phase], cookieShape(), 56.dp, roles.accent, roles.onAccent, 28.dp, spinMs = 30_000)
        }
        Network.Bluetooth -> Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(48.dp).spinning(rememberSpin(30_000)).clip(RoundedCornerShape(18.dp)).background(roles.accent))
            PulseRing(roles.accent, 40.dp, periodMs = 2400)
            Symbol(Sym.Bluetooth, size = 28.dp, filled = true, tint = roles.onAccent)
        }
        Network.Cellular -> {
            val phase = rememberBarsPhase()
            ShapeBadge(listOf(Sym.CellBars1, Sym.CellBars2, Sym.CellBars3)[phase], cloverShape(), 56.dp, roles.accent, roles.onAccent, 28.dp, spinMs = 40_000, reverse = true)
        }
        Network.Gnss -> {
            val spin = rememberSpin(8_000)
            val dash = remember { PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) }
            Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(roles.accent))
                Canvas(Modifier.size(56.dp).spinning(spin)) {
                    drawCircle(roles.accent, size.minDimension / 2 - 1.dp.toPx(), style = Stroke(2.dp.toPx(), pathEffect = dash))
                }
                Symbol(Sym.SatelliteAlt, size = 26.dp, filled = true, tint = roles.onAccent)
            }
        }
        Network.Sdr -> {
            // Waves leaving the antenna, under a slowly turning squircle.
            Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                PulseRing(roles.accent, 40.dp, periodMs = 1800)
                Box(Modifier.size(48.dp).spinning(rememberSpin(24_000, reverse = true)).clip(RoundedCornerShape(16.dp)).background(roles.accent))
                Symbol(Sym.Antenna, size = 26.dp, filled = true, tint = roles.onAccent)
            }
        }
        Network.WifiBt -> ShapeBadge(Sym.DirectionsCar, cookieShape(), 56.dp, roles.accent, roles.onAccent, 28.dp)
    }
}

/**
 * The Wi-Fi and Bluetooth cards fused into one tile: the gap closes, then the tile takes the pair's look (both
 * colours, both badges, one outline) over the two cards. [merge] runs from 0 (apart) to 1, overshooting a little.
 */
@Composable
private fun PairTile(merge: Float, onOpen: () -> Unit, onSeparate: () -> Unit, cards: @Composable () -> Unit) {
    val net = AntTheme.net
    val m = merge.coerceIn(0f, 1f)
    // The pair's colours only once the cards touch: before that the two cards are still seen moving.
    val look = ((m - 0.45f) / 0.55f).coerceIn(0f, 1f)
    val shadowColor = net.shadow
    Box(
        Modifier.fillMaxWidth()
            .graphicsLayer { val s = 1f + 0.03f * merge; scaleX = s; scaleY = s }
            .drawBehind {
                if (look > 0f) {
                    val inset = 3.dp.toPx() + 1.5.dp.toPx()
                    drawRoundRect(
                        Brush.horizontalGradient(listOf(net.wifi.accent, net.bt.accent)), Offset(-inset, -inset), Size(size.width + inset * 2, size.height + inset * 2),
                        CornerRadius(28.dp.toPx() + inset), alpha = look, style = Stroke(3.dp.toPx()),
                    )
                }
            },
    ) {
        cards()
        if (look > 0f) {
            val shape = RoundedCornerShape(28.dp)
            Box(
                Modifier.matchParentSize()
                    .shadow(10.dp * look, shape, ambientColor = shadowColor, spotColor = shadowColor)
                    .graphicsLayer { alpha = look }
                    .clip(shape)
                    .background(Brush.horizontalGradient(listOf(net.wifi.container, net.bt.container)))
                    .clickable(enabled = look > 0.5f, onClick = onOpen),
            ) {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CardIcon(Network.Wifi)
                        Box(Modifier.padding(start = 8.dp)) { CardIcon(Network.Bluetooth) }
                    }
                    Spacer(Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Wi-Fi", style = rf(18, 24, 600), color = net.wifi.onContainer)
                        Text(" + ", style = rf(18, 24, 600), color = net.wifi.onContainer)
                        Text("Bluetooth", style = rf(18, 24, 600), color = net.bt.onContainer)
                    }
                    Text(
                        S(R.string.pair_subtitle), Modifier.padding(top = 4.dp).graphicsLayer { alpha = 0.85f },
                        style = rf(13, 18), color = net.bt.onContainer, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                // Stays while the cards are assembled: takes them apart again.
                Box(
                    Modifier.align(Alignment.TopEnd).padding(12.dp).size(48.dp).clip(CircleShape).background(cs.surface)
                        .clickable(enabled = look > 0.5f, onClick = onSeparate),
                    contentAlignment = Alignment.Center,
                ) { Symbol(Sym.LinkOff, size = 24.dp, tint = cs.onSurface) }
            }
        }
    }
}

/** The two cards held together to pick the pair of radios. */
private val PairCards = setOf(Network.Wifi, Network.Bluetooth)

@Composable
private fun ColumnScope.NetworkCardContent(network: Network) {
    CardIcon(network)
    Spacer(Modifier.weight(1f))
    Text(network.label, style = rf(18, 24, 600), maxLines = 1)
    Text(description(network), Modifier.padding(top = 4.dp).graphicsLayer { alpha = 0.85f }, style = rf(13, 18), maxLines = 3, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun BlockedCardContent(network: Network, blocker: Blocker, onAction: (Blocker) -> Unit) {
    val d = cardBlock(blocker)
    val roles = AntTheme.net[network]
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(20.dp)).background(cs.surfaceContainerHighest), contentAlignment = Alignment.Center) {
            Symbol(d.icon, size = 28.dp, tint = cs.onSurfaceVariant)
        }
        Spacer(Modifier.weight(1f))
        Text(network.label, style = rf(16, 22, 600), color = cs.onSurface)
        Text(d.title, Modifier.padding(top = 2.dp), style = rf(14, 20, 500), color = cs.onSurface)
        Text(d.message, Modifier.padding(top = 4.dp), style = rf(13, 18), color = cs.onSurfaceVariant)
        if (d.button != null) {
            Row(
                Modifier.padding(top = 14.dp).fillMaxWidth().height(40.dp).clip(RoundedCornerShape(20.dp))
                    .background(roles.accent).clickable { onAction(blocker) },
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Symbol(d.buttonIcon, size = 18.dp, filled = true, tint = roles.onAccent)
                Text(d.button, style = rf(14, 20, 600), color = roles.onAccent)
            }
        }
    }
}

/** A welcome line or a network joke, drawn once per launch of the app. */
object Greetings {
    private val all = listOf(
        S(R.string.welcome_the_airwaves_are_waiting),
        S(R.string.good_to_see_you_again_the),
        S(R.string.hello_which_frequency_shall_we_explore),
        S(R.string.ready_to_listen_to_the_invisible),
        S(R.string.the_satellites_wave_at_you_from),
        S(R.string.wi_fi_is_like_love_it),
        S(R.string.why_is_the_router_so_zen),
        S(R.string.my_wi_fi_is_called_virus),
        S(R.string.a_udp_packet_walks_into_a),
        S(R.string.i_know_a_joke_about_tcp),
        S(R.string.there_are_10_kinds_of_people),
        S(R.string.bluetooth_is_the_art_of_pairing),
        S(R.string.ping_pong_all_good),
        S(R.string.no_5g_in_the_basement_normal),
        S(R.string.radiosondes_climb_to_30_km_your),
        S(R.string.an_antenna_married_a_dish_the),
        S(R.string.n_127_0_0_1_there_s),
        S(R.string.it_s_always_dns_even_when),
        S(R.string.your_router_is_blinking_it_s),
        S(R.string.a_lost_airtag_it_s_never),
        S(R.string.gps_never_gets_lost_it_recalculates),
        S(R.string.aircraft_shout_their_position_on_1090),
        S(R.string.short_waves_long_ideas),
        S(R.string.the_sky_has_never_been_so),
        S(R.string.better_a_good_dbm_than_a),
        S(R.string.wi_fi_doesn_t_go_through),
        S(R.string.today_a_constellation_of_good_news),
        S(R.string.radio_silence_not_here),
    )

    val ofThisLaunch: String = all.random()
}
