package com.allnetworktools.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Bluetooth LE spam: the pop-up floods sent by a Flipper Zero, an ESP32 or a phone app (Bluetooth-LE-Spam by Simon
 * Dankelmann, GPL-3.0, whose signatures are the ones recognised here). The tool only listens.
 */
enum class SpamKind(val label: String, val target: String) {
    AppleAction("Apple Nearby Action", "iPhone"),
    AppleProximity("Apple Proximity Pairing", "iPhone"),
    FastPair("Google Fast Pair", "Android"),
    SwiftPair("Microsoft Swift Pair", "Windows"),
    SamsungEasySetup("Samsung Easy Setup", "Samsung"),
    Lovespouse("Lovespouse", "—"),
}

/** The Flipper Zero's three body colours, from its BLE service UUID. */
enum class FlipperColor { White, Black, Transparent, Unknown }

data class FlipperSeen(val address: String, val name: String?, val color: FlipperColor, val rssi: Int, val lastSeen: Long, val byName: Boolean)

/** One kind of spam pop-up, over the last [BleSpam.WINDOW_MS]. */
data class SpamKindState(
    val kind: SpamKind,
    /** Addresses carrying this kind heard in the window. */
    val addresses: Int,
    /** Of those, the ones that appeared in the window: spam rotates its address at every burst. */
    val fresh: Int,
    val strongest: Int,
    val attacking: Boolean,
)

/** A burst of spam, from the moment it crossed the threshold to the moment it stopped. */
data class SpamIncident(val kind: SpamKind, val startMs: Long, val endMs: Long?, val peakFresh: Int, val strongest: Int)

object BleSpam {
    const val WINDOW_MS = 10_000L

    /**
     * New addresses of one kind within [WINDOW_MS] before it counts as an attack. A real AirPods, a real Fast Pair
     * headset or a Windows mouse keeps its address for minutes (a resolvable address changes every ~15 minutes): six new
     * ones in ten seconds is a generator. The iPhone action pop-ups with the spam flags are rarer in real life.
     */
    fun threshold(kind: SpamKind) = if (kind == SpamKind.AppleAction) 3 else 6

    /** What kind of spam pop-up [ads] carries, or null. */
    fun classify(ads: List<AdStructure>): SpamKind? {
        val mfg = Ad.manufacturer(ads)
        mfg[0x004C]?.let { d ->
            if (d.size >= 3 && d.u8(0) == 0x0F && d.u8(1) == 0x05 && (d.u8(2) == 0xC0 || d.u8(2) == 0x40)) return SpamKind.AppleAction
            if (d.size >= 3 && d.u8(0) == 0x07 && d.u8(1) == 0x19 && d.u8(2) in setOf(0x01, 0x05, 0x07)) return SpamKind.AppleProximity
        }
        mfg[0x0006]?.let { d -> if (d.size >= 3 && d.u8(0) == 0x03 && d.u8(1) == 0x00 && d.u8(2) == 0x80) return SpamKind.SwiftPair }
        mfg[0x0075]?.let { d ->
            val h = d.joinToString("") { "%02X".format(it) }
            if (h.startsWith("42098102141503210109") || h.startsWith("010002000101FF000043")) return SpamKind.SamsungEasySetup
        }
        if (0xFE2C in Ad.serviceData(ads) || 0xFE2C in Ad.uuids16(ads)) return SpamKind.FastPair
        if (Ad.hex(ads).contains("6DB643CE97FE427C")) return SpamKind.Lovespouse
        return null
    }

    /** A Flipper Zero, from the service UUID of its body colour, or from the name its firmware gives ("Flipper …"). */
    fun flipper(ads: List<AdStructure>, name: String?): Pair<FlipperColor, Boolean>? {
        val u = Ad.uuids16(ads)
        val color = when {
            0x3082 in u -> FlipperColor.White
            0x3081 in u -> FlipperColor.Black
            0x3083 in u -> FlipperColor.Transparent
            else -> null
        }
        if (color != null) return color to false
        if (name != null && name.startsWith("Flipper ")) return FlipperColor.Unknown to true
        return null
    }
}

/** Follows the scan: which spam is going on, its history, and the Flipper Zeros around. Kept for the session only. */
class BleSpamWatch {
    var kinds by mutableStateOf<List<SpamKindState>>(emptyList())
        private set
    var incidents by mutableStateOf<List<SpamIncident>>(emptyList())
        private set
    var flippers by mutableStateOf<List<FlipperSeen>>(emptyList())
        private set
    /** Spam addresses heard since the tool started (each burst comes from a new one). */
    var packets by mutableStateOf(0)
        private set

    private val firstSeen = HashMap<String, Long>()
    private val counted = HashSet<String>()
    private val open = HashMap<SpamKind, SpamIncident>()
    private val knownFlippers = LinkedHashMap<String, FlipperSeen>()

    val attacking get() = kinds.any { it.attacking }

    /** [devices] as the scan lists them; [nowEl] and the devices' lastSeen are elapsedRealtime; [wallMs] for the log. */
    fun update(devices: List<BleDevice>, nowEl: Long, wallMs: Long) {
        val inWindow = devices.filter { nowEl - it.lastSeen <= BleSpam.WINDOW_MS }
        val byKind = HashMap<SpamKind, MutableList<BleDevice>>()
        for (d in inWindow) {
            firstSeen.getOrPut(d.address) { d.lastSeen }
            val k = BleSpam.classify(d.ads) ?: continue
            byKind.getOrPut(k) { ArrayList() } += d
            if (counted.add(d.address)) packets++
        }
        for (d in inWindow) BleSpam.flipper(d.ads, d.name)?.let { (c, byName) -> knownFlippers[d.address] = FlipperSeen(d.address, d.name, c, d.rssi, d.lastSeen, byName) }
        // Addresses not heard for a minute are forgotten; a Flipper stays listed for a minute after its last packet.
        firstSeen.entries.removeAll { nowEl - it.value > 60_000 && devices.none { d -> d.address == it.key } }
        knownFlippers.entries.removeAll { nowEl - it.value.lastSeen > 60_000 }
        if (counted.size > 5000) counted.clear()

        val states = SpamKind.entries.mapNotNull { k ->
            val list = byKind[k] ?: return@mapNotNull null
            val fresh = list.count { nowEl - (firstSeen[it.address] ?: 0L) <= BleSpam.WINDOW_MS }
            SpamKindState(k, list.size, fresh, list.maxOf { it.rssi }, fresh >= BleSpam.threshold(k))
        }.sortedWith(compareByDescending<SpamKindState> { it.attacking }.thenByDescending { it.fresh })
        kinds = states

        var log = incidents
        for (k in SpamKind.entries) {
            val s = states.firstOrNull { it.kind == k }
            val cur = open[k]
            if (s != null && s.attacking) {
                val next = if (cur == null) SpamIncident(k, wallMs, null, s.fresh, s.strongest)
                else cur.copy(peakFresh = maxOf(cur.peakFresh, s.fresh), strongest = maxOf(cur.strongest, s.strongest))
                open[k] = next
                log = listOf(next) + log.filterNot { it === cur }
            } else if (cur != null) {
                open.remove(k)
                val done = cur.copy(endMs = wallMs)
                log = log.map { if (it === cur) done else it }
            }
        }
        incidents = log.take(100)
        flippers = knownFlippers.values.sortedByDescending { it.rssi }
    }

    fun clear() {
        incidents = emptyList(); open.clear(); packets = 0; counted.clear()
    }
}
