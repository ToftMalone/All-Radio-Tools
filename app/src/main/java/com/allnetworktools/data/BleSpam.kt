package com.allnetworktools.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Bluetooth LE spam: the pop-up floods sent by a Flipper Zero, an ESP32 or a phone app. The tool only listens.
 *
 * The byte signatures are those of simondankelmann/Bluetooth-LE-Spam (GPL-3.0); the Flipper Zero service UUIDs and
 * address blocks come from K3YOMI/Wall-of-Flippers (MIT); the Apple action names follow the furiousMAC Continuity
 * dissector. The ways of telling a flood from real accessories (a list being walked by one address, one radio behind
 * several addresses, the tail of the iOS 17 crash frame, Fast Pair counted in its discoverable form only) were
 * contributed by Melvin Olivet (pull request #4) and merged here with the rate of new addresses.
 */
enum class SpamKind(val label: String, val target: String) {
    /** The Nearby Action with the tail that locked iOS 17 up: no product sends it. */
    AppleCrash("Apple · crash iOS 17", "iPhone"),
    AppleAction("Apple Nearby Action", "iPhone"),
    AppleProximity("Apple Proximity Pairing", "iPhone"),
    FastPair("Google Fast Pair", "Android"),
    SwiftPair("Microsoft Swift Pair", "Windows"),
    SamsungEasySetup("Samsung Easy Setup", "Samsung"),
    Lovespouse("Lovespouse", "—"),
}

/** A recognised pop-up frame, with what varies inside its kind: the Apple action number, the model asked for. */
data class SpamHit(val kind: SpamKind, val variant: Int? = null)

/** Why a kind of pop-up is called an attack. */
enum class SpamReason {
    /** A frame no product sends. */
    Crafted,
    /** New addresses appearing faster than accessories do. */
    Flood,
    /** One address walking through kinds, models or action numbers. */
    Cycling,
    /** Several new addresses at the same signal level: one radio wearing them. */
    OneRadio,
}

/** The Flipper Zero's three body colours, from its BLE service UUID. */
enum class FlipperColor { White, Black, Transparent, Unknown }

/** What gave the Flipper away. */
enum class FlipperClue { Service, Name, Address }

data class FlipperSeen(val address: String, val name: String?, val color: FlipperColor, val rssi: Int, val lastSeen: Long, val clue: FlipperClue)

/** One kind of spam pop-up, over the last [BleSpam.WINDOW_MS]. */
data class SpamKindState(
    val kind: SpamKind,
    /** Addresses carrying this kind heard in the window. */
    val addresses: Int,
    /** Of those, the ones that appeared in the window: spam rotates its address at every burst. */
    val fresh: Int,
    val strongest: Int,
    val reasons: Set<SpamReason>,
    /** The Apple actions asked for, by name, when the kind carries them. */
    val detail: String? = null,
) {
    val attacking get() = reasons.isNotEmpty()
}

/** A burst of spam, from the moment it was recognised to the moment it stopped. */
data class SpamIncident(val kind: SpamKind, val startMs: Long, val endMs: Long?, val peakFresh: Int, val strongest: Int, val reasons: Set<SpamReason>)

object BleSpam {
    const val WINDOW_MS = 10_000L

    /** How long an address's frames are remembered, to see it walk a list. */
    const val MEMORY_MS = 60_000L

    /** Distinct frames from one address before it is cycling: a product sends one, always the same. */
    const val CYCLING_FRAMES = 3

    /** New addresses at the same level before they read as one radio, and the spread allowed between them. */
    const val ONE_RADIO_ADDRESSES = 4
    const val ONE_RADIO_SPREAD_DB = 6

    /**
     * New addresses of one kind within [WINDOW_MS] before it counts as a flood. A real AirPods, a real Fast Pair
     * headset or a Windows mouse keeps its address for minutes (a resolvable address changes every ~15 minutes): six new
     * ones in ten seconds is a generator. The iPhone action pop-ups with the spam flags are rarer in real life.
     */
    fun threshold(kind: SpamKind) = if (kind == SpamKind.AppleAction) 3 else 6

    /** Apple Nearby Action numbers, from the furiousMAC Continuity dissector. */
    private val NEARBY_ACTIONS = mapOf(
        0x01 to "Apple TV Tap-To-Setup", 0x04 to "Mobile Backup", 0x05 to "Watch Setup", 0x06 to "Apple TV Pair",
        0x07 to "Internet Relay", 0x08 to "Wi-Fi Password", 0x09 to "iOS Setup", 0x0A to "Repair", 0x0B to "Speaker Setup",
        0x0C to "Apple Pay", 0x0D to "Whole Home Audio Setup", 0x0E to "Developer Tools Pairing Request",
        0x0F to "Answered Call", 0x10 to "Ended Call", 0x13 to "Remote Auto Fill", 0x14 to "Companion Link Prox",
        0x15 to "Remote Management", 0x17 to "Remote Display",
    )

    fun nearbyAction(n: Int): String = NEARBY_ACTIONS[n] ?: "0x%02X".format(n)

    /** What pop-up frame [ads] carries, or null. */
    fun classify(ads: List<AdStructure>): SpamHit? {
        val mfg = Ad.manufacturer(ads)
        mfg[0x004C]?.let { d ->
            if (d.size >= 3 && d.u8(0) == 0x0F && d.u8(1) == 0x05 && (d.u8(2) == 0xC0 || d.u8(2) == 0x40)) {
                val action = if (d.size >= 4) d.u8(3) else null
                // A Nearby Action of length 5 ends after 7 bytes. The frame that locked iOS 17 up hangs 00 00 10 past
                // that end, and 0x00 begins no Continuity message: only what trails is read, not the whole payload.
                val crash = d.size >= 10 && d.u8(7) == 0x00 && d.u8(8) == 0x00 && d.u8(9) == 0x10
                return SpamHit(if (crash) SpamKind.AppleCrash else SpamKind.AppleAction, action)
            }
            if (d.size >= 3 && d.u8(0) == 0x07 && d.u8(1) == 0x19 && d.u8(2) in setOf(0x01, 0x05, 0x07)) {
                // The model asked for: 0E 20 is an AirPods Pro. Real ones always send their own.
                return SpamHit(SpamKind.AppleProximity, if (d.size >= 5) (d.u8(3) shl 8) or d.u8(4) else null)
            }
        }
        mfg[0x0006]?.let { d -> if (d.size >= 3 && d.u8(0) == 0x03 && d.u8(1) == 0x00 && d.u8(2) == 0x80) return SpamHit(SpamKind.SwiftPair) }
        mfg[0x0075]?.let { d ->
            val h = d.joinToString("") { "%02X".format(it) }
            if (h.startsWith("42098102141503210109")) return SpamHit(SpamKind.SamsungEasySetup, 1)
            if (h.startsWith("010002000101FF000043")) return SpamHit(SpamKind.SamsungEasySetup, 2)
        }
        // Fast Pair raises its card only in the discoverable form, a bare 3-byte model id. Once paired, an accessory
        // broadcasts a longer account-key filter, which shows nothing.
        Ad.serviceData(ads)[0xFE2C]?.let { d -> if (d.size == 3) return SpamHit(SpamKind.FastPair, (d.u8(0) shl 16) or (d.u8(1) shl 8) or d.u8(2)) }
        val raw = Ad.hex(ads)
        val i = raw.indexOf("6DB643CE97FE427C")
        if (i >= 0) return SpamHit(SpamKind.Lovespouse, raw.substring(i + 16).take(6).toIntOrNull(16))
        return null
    }

    /**
     * A Flipper Zero: by the service UUID of its body colour, else by the name its firmware gives ("Flipper …") or by
     * the address blocks of Flipper Devices Inc. — the colour is then unknown.
     */
    fun flipper(ads: List<AdStructure>, name: String?, address: String = ""): Pair<FlipperColor, FlipperClue>? {
        val u16 = Ad.uuids16(ads)
        val u128 = Ad.uuids128(ads)
        fun has(v: Int) = v in u16 || u128.any { it.startsWith("%08X-0000-1000-8000-00805F9B34FB".format(v), ignoreCase = true) }
        when {
            has(0x3082) -> return FlipperColor.White to FlipperClue.Service
            has(0x3081) -> return FlipperColor.Black to FlipperClue.Service
            has(0x3083) -> return FlipperColor.Transparent to FlipperClue.Service
        }
        if (name != null && name.startsWith("Flipper ", ignoreCase = true)) return FlipperColor.Unknown to FlipperClue.Name
        val oui = address.take(8).uppercase()
        if (oui == "80:E1:26" || oui == "80:E1:27" || oui == "0C:FA:22") return FlipperColor.Unknown to FlipperClue.Address
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

    private class Memory(val firstSeen: Long) {
        var lastSeen = firstSeen
        /** Every distinct frame this address has sent: the cycling only shows across packets. */
        val hits = HashSet<SpamHit>()
    }

    private val memory = HashMap<String, Memory>()
    private val counted = HashSet<String>()
    private val open = HashMap<SpamKind, SpamIncident>()
    private val knownFlippers = LinkedHashMap<String, FlipperSeen>()

    val attacking get() = kinds.any { it.attacking }

    /** [devices] as the scan lists them; [nowEl] and the devices' lastSeen are elapsedRealtime; [wallMs] for the log. */
    fun update(devices: List<BleDevice>, nowEl: Long, wallMs: Long) {
        // The scan keeps an address for a while after its last packet: only the real time of that packet counts.
        val inWindow = devices.filter { nowEl - it.lastSeen <= BleSpam.WINDOW_MS }
        val byKind = HashMap<SpamKind, MutableList<BleDevice>>()
        for (d in inWindow) {
            val m = memory.getOrPut(d.address) { Memory(d.lastSeen) }
            m.lastSeen = maxOf(m.lastSeen, d.lastSeen)
            BleSpam.flipper(d.ads, d.name, d.address)?.let { (c, clue) -> knownFlippers[d.address] = FlipperSeen(d.address, d.name, c, d.rssi, d.lastSeen, clue) }
            val hit = BleSpam.classify(d.ads) ?: continue
            m.hits += hit
            byKind.getOrPut(hit.kind) { ArrayList() } += d
            if (counted.add(d.address)) packets++
        }
        memory.entries.removeAll { nowEl - it.value.lastSeen > BleSpam.MEMORY_MS }
        knownFlippers.entries.removeAll { nowEl - it.value.lastSeen > BleSpam.MEMORY_MS }
        if (counted.size > 5000) counted.clear()

        val states = SpamKind.entries.mapNotNull { k ->
            val list = byKind[k] ?: return@mapNotNull null
            val fresh = list.filter { nowEl - (memory[it.address]?.firstSeen ?: 0L) <= BleSpam.WINDOW_MS }
            val reasons = HashSet<SpamReason>()
            if (k == SpamKind.AppleCrash) reasons += SpamReason.Crafted
            if (fresh.size >= BleSpam.threshold(k)) reasons += SpamReason.Flood
            // One address, several frames: counted per address, so three real products of three kinds stay calm.
            if (list.any { (memory[it.address]?.hits?.size ?: 0) >= BleSpam.CYCLING_FRAMES }) reasons += SpamReason.Cycling
            // Several objects sit at several distances; one radio wearing new addresses does not.
            if (fresh.size >= BleSpam.ONE_RADIO_ADDRESSES && fresh.maxOf { it.rssi } - fresh.minOf { it.rssi } <= BleSpam.ONE_RADIO_SPREAD_DB) reasons += SpamReason.OneRadio
            val detail = if (k == SpamKind.AppleAction || k == SpamKind.AppleCrash) {
                list.flatMap { memory[it.address]?.hits.orEmpty() }.filter { it.kind == k }.mapNotNull { it.variant }.distinct().take(3)
                    .joinToString(", ") { BleSpam.nearbyAction(it) }.ifEmpty { null }
            } else null
            SpamKindState(k, list.size, fresh.size, list.maxOf { it.rssi }, reasons, detail)
        }.sortedWith(compareByDescending<SpamKindState> { it.attacking }.thenByDescending { it.fresh })
        kinds = states

        var log = incidents
        for (k in SpamKind.entries) {
            val s = states.firstOrNull { it.kind == k }
            val cur = open[k]
            if (s != null && s.attacking) {
                val next = if (cur == null) SpamIncident(k, wallMs, null, s.fresh, s.strongest, s.reasons)
                else cur.copy(peakFresh = maxOf(cur.peakFresh, s.fresh), strongest = maxOf(cur.strongest, s.strongest), reasons = cur.reasons + s.reasons)
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
