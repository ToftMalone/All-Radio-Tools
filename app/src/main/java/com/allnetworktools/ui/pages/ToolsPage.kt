package com.allnetworktools.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.Page
import com.allnetworktools.model.Network
import com.allnetworktools.model.Tool
import com.allnetworktools.ui.components.LeadingIcon
import com.allnetworktools.ui.components.PillButton
import com.allnetworktools.ui.components.SectionTitle
import com.allnetworktools.ui.components.ShapeBadge
import com.allnetworktools.ui.components.TileGrid
import com.allnetworktools.ui.components.cookieShape
import com.allnetworktools.ui.components.fadeUp
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.util.plural

private data class ToolEntry(val tool: Tool, val title: String, val subtitle: String, val icon: String = tool.icon)
private data class ToolGroup(val title: String, val items: List<ToolEntry>)

private fun groups(net: Network, connectedDevice: String?, servingLabel: String?): List<ToolGroup> = when (net) {
    Network.Wifi -> listOf(
        ToolGroup("Analyse", listOf(ToolEntry(Tool.Speed, "Test de débit", "Ping, download, upload"), ToolEntry(Tool.Coverage, "Carte de couverture", "Le signal pièce par pièce"))),
        ToolGroup("Réseau local", listOf(ToolEntry(Tool.Lan, "Appareils du LAN", "Découverte des hôtes"), ToolEntry(Tool.Ports, "Scan de ports", "TCP, ports courants"))),
        ToolGroup("Découverte", listOf(ToolEntry(Tool.WifiDirect, "Wi-Fi Direct", "Appareils en connexion directe"))),
        ToolGroup(
            "Diagnostic",
            listOf(ToolEntry(Tool.Ping, "Ping", "Latence, gigue, pertes"), ToolEntry(Tool.Trace, "Traceroute", "Sauts jusqu'à l'hôte"), ToolEntry(Tool.Dns, "DNS Lookup", "A, AAAA, MX, TXT, NS"), ToolEntry(Tool.Whois, "Whois", "Domaine ou adresse IP")),
        ),
        ToolGroup(
            "Sécurité",
            listOf(
                ToolEntry(Tool.Audit, "Audit du réseau", "Chiffrement, WPS, PMF, box"),
                ToolEntry(Tool.EvilTwin, "Faux points d'accès", "SSID dupliqués, evil twin"),
                ToolEntry(Tool.Mitm, "Homme du milieu", "ARP spoofing, DHCP pirate"),
            ),
        ),
    )
    Network.Bluetooth -> listOf(
        ToolGroup(
            "Appareils",
            listOf(
                ToolEntry(Tool.Tracker, "Chaud/Froid", "Retrouver un appareil"),
                ToolEntry(Tool.Earbuds, "Batterie des écouteurs", "AirPods et Beats : gauche, droite, boîtier"),
                ToolEntry(Tool.Beacons, "Balises", "iBeacon, Eddystone, AltBeacon"),
            ),
        ),
        ToolGroup("Sécurité", listOf(ToolEntry(Tool.UnknownTrackers, "Traqueurs inconnus", "AirTag, SmartTag, Tile qui vous suivent"))),
    )
    Network.Cellular -> listOf(
        ToolGroup(
            "Cellules",
            listOf(
                ToolEntry(Tool.Neighbors, "Cellules voisines", "Cellules NR et LTE détectées par le modem"),
                ToolEntry(Tool.Handover, "Journal de handover", "Changements de cellule en trajet, export CSV"),
            ),
        ),
    )
    Network.Gnss -> listOf(
        ToolGroup(
            "Outils",
            listOf(
                ToolEntry(Tool.PositionCompare, "Comparer les positions", "GNSS, réseau et fusionnée"),
                ToolEntry(Tool.GnssClock, "Horloge atomique", "Heure UTC des satellites, écart du téléphone"),
                ToolEntry(Tool.CompassCheck, "Boussole et cap GNSS", "Vérifier la boussole en marchant"),
                ToolEntry(Tool.SkyMask, "Masque du ciel", "Où le ciel est caché par les obstacles"),
            ),
        ),
        ToolGroup(
            "Expert",
            listOf(
                ToolEntry(Tool.GnssIntegrity, "Brouillage et leurrage", "Détecter un brouilleur ou de faux signaux"),
                ToolEntry(Tool.GnssRaw, "Mesures brutes", "Bi-fréquence L1/L5, export RINEX"),
            ),
        ),
    )
    Network.Talkie -> emptyList()
    Network.Sdr -> listOf(
        ToolGroup("Outils", listOf(ToolEntry(Tool.Adsb, "Avions (ADS-B)", "Carte des avions sur 1090 MHz"), ToolEntry(Tool.Sonde, "Ballons-sondes", "Sondes RS41, M10 et M20 vers 403 MHz"), ToolEntry(Tool.Fm, "Radio FM", "Écoute et RDS, 87,5 à 108 MHz"), ToolEntry(Tool.Ais, "Navires (AIS)", "Carte des navires sur 162 MHz"), ToolEntry(Tool.Meteor, "Satellites météo (Meteor-M)", "Images LRPT sur 137 MHz"), ToolEntry(Tool.Aprs, "Radioamateurs (APRS)", "Balises sur 144,800 MHz"), ToolEntry(Tool.Acars, "Messages d'avions (ACARS)", "Messagerie des avions de ligne, 131 MHz"), ToolEntry(Tool.Meshtastic, "Meshtastic", "Messages, positions et nœuds LoRa, 868 MHz"), ToolEntry(Tool.GpsSdr, "Récepteur GPS", "Position calculée par le HackRF, 1575 MHz"), ToolEntry(Tool.Fpv, "Drones", "FPV analogique, DJI et radiocommandes"))),
    )
}

private fun featuredSubtitle(net: Network, servingLabel: String?) = when (net) {
    Network.Wifi -> "Réseaux alentour et occupation des canaux"
    Network.Bluetooth -> "Appareils à proximité, en direct, avec filtres"
    Network.Cellular -> servingLabel ?: "Identifiants et mesures de la cellule connectée"
    Network.Gnss -> "Sky plot et carte du monde des satellites"
    Network.Sdr -> "Spectre et chute d'eau, de 1 MHz à 6 GHz"
    Network.Talkie -> "Lire, modifier et écrire les mémoires par câble USB"
}

@Composable
private fun featuredBadge(net: Network, vm: MainViewModel): String? = when (net) {
    Network.Wifi -> vm.wifiScan.collectAsStateWithLifecycle().value?.let { "${it.size} ${plural(it.size, "réseau", "réseaux")}" }
    Network.Bluetooth -> vm.ble.collectAsStateWithLifecycle().value.size.let { "$it ${plural(it, "appareil")}" }
    Network.Cellular -> vm.cell.collectAsStateWithLifecycle().value.state?.serving?.tech?.short
    Network.Gnss -> vm.gnss.collectAsStateWithLifecycle().value.let { "${it.used.size}/${it.visible.size} satellites" }
    Network.Talkie -> vm.tools.radioChannels.let { c -> c.count.takeIf { it > 0 }?.let { "$it ${plural(it, "canal", "canaux")}" } }
    Network.Sdr -> vm.tools.spectrum.let { if (it.running && it.tunedHz > 0) "%.1f MHz".format(java.util.Locale.FRANCE, it.tunedHz / 1e6) else null }
}

@Composable
fun ToolsPage(net: Network, vm: MainViewModel) {
    val acc = AntTheme.accent
    val open = { tool: Tool -> vm.navigate { it.copy(page = Page.ToolPage(tool)) } }
    // Only the network being shown is read: opening Wi-Fi tools must not start Bluetooth or the modem.
    val connected = if (net == Network.Bluetooth) vm.bluetooth.collectAsStateWithLifecycle().value.connected.firstOrNull()?.name else null
    val serving = if (net == Network.Cellular) vm.cell.collectAsStateWithLifecycle().value.state?.serving else null
    val servingLabel = serving?.let { s -> listOfNotNull(s.pci?.let { "PCI $it" }, s.band).joinToString(" · ").ifEmpty { null } }
    val badge = featuredBadge(net, vm)
    PageColumn {
        Column(
            Modifier.fadeUp().fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(acc.container)
                .clickable { open(net.featured) }.padding(20.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                ShapeBadge(net.featured.icon, cookieShape(), 72.dp, acc.accent, acc.onAccent, 34.dp, spinMs = 30_000)
                if (badge != null) {
                    Box(Modifier.clip(RoundedCornerShape(14.dp)).background(cs.surface).padding(horizontal = 10.dp, vertical = 5.dp)) {
                        Text(badge, style = rf(12, 18, 600), color = cs.onSurface)
                    }
                }
            }
            Text(net.featured.title, Modifier.padding(top = 16.dp), style = gs(26, 32, 500), color = acc.onContainer)
            Text(featuredSubtitle(net, servingLabel), Modifier.padding(top = 2.dp).graphicsLayer { alpha = 0.85f }, style = rf(14, 20), color = acc.onContainer)
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End) {
                PillButton("Lancer", { open(net.featured) }, icon = Sym.PlayArrow, height = 48.dp)
            }
        }
        groups(net, connected, servingLabel).forEach { g ->
            SectionTitle(g.title)
            TileGrid(g.items) { e, mod ->
                Column(
                    mod.fadeUp(60).clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainerLow)
                        .clickable { open(e.tool) }.heightIn(min = 132.dp).padding(16.dp),
                ) {
                    LeadingIcon(e.icon, cs.surfaceContainerHighest, acc.accent, shape = RoundedCornerShape(14.dp))
                    Spacer(Modifier.weight(1f, fill = false).heightIn(min = 12.dp))
                    Text(e.title, Modifier.padding(top = 12.dp), style = rf(15, 20, 600), maxLines = 2)
                    Text(e.subtitle, Modifier.padding(top = 2.dp), style = rf(12, 16), color = cs.onSurfaceVariant, maxLines = 2)
                }
            }
        }
    }
}
