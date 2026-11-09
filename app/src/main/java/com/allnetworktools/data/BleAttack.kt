package com.allnetworktools.data

import com.allnetworktools.R
import com.allnetworktools.util.S

/**
 * Advertising frames whose whole purpose is to make a nearby phone show something: a pairing sheet, a
 * setup card, a notification. Real accessories send one of these, of one kind, from one address. The
 * flooding apps cycle through a list of them, which is what gives them away.
 *
 * The byte signatures are those of the open-source detectors that documented them:
 * simondankelmann/Bluetooth-LE-Spam (GPL-3.0) for the frames below, and K3YOMI/Wall-of-Flippers (MIT)
 * for the Flipper Zero service UUIDs. The Apple message and action numbering follows the Continuity
 * dissector of furiousMAC (GPL-2.0) and the PETS papers it comes from.
 *
 * [benignUse] is false for a frame no product sends: finding one is enough on its own.
 */
enum class SpamFrame(val label: String, val benignUse: Boolean) {
    /** Apple Nearby Action, flags 0xC0 or 0x40: the frame behind the pop-ups. */
    ActionModal("Continuity · Action Modal", true),

    /** The same with the tail that locked iOS 17 up. No accessory sends it. */
    Ios17Crash("Continuity · crash iOS 17", false),

    /**
     * Apple Proximity Pairing, the frame behind the "connect?" card. Real AirPods and AirTags send the
     * very same thing, each with its own model byte — 07 19 01 0E 20 is an AirPods Pro — so one of
     * these means nothing on its own. What no product does is walk through model bytes.
     */
    ProximityPairing("Continuity · Proximity Pairing", true),

    /** Microsoft Swift Pair. */
    SwiftPair("Swift Pair", true),

    /** Samsung EasySetup, earbuds card. */
    EasySetupBuds("EasySetup · Buds", true),

    /** Samsung EasySetup, watch card. */
    EasySetupWatch("EasySetup · Watch", true),

    /** Google Fast Pair. */
    FastPair("Fast Pair", true),

    /** Remote control frames for a class of toys, used to set them off in public. */
    Lovespouse("Lovespouse", false),
}

/** A recognised frame, with the Apple action number when the frame carries one. */
data class SpamHit(val frame: SpamFrame, val action: Int? = null) {
    /** "Continuity · Action Modal (Watch Setup)" when Apple's table names the number, else its value. */
    val title: String
        get() {
            val a = action ?: return frame.label
            val named = if (frame == SpamFrame.ActionModal || frame == SpamFrame.Ios17Crash) AdvertDetect.nearbyAction(a) else null
            return "${frame.label} (${named ?: "0x%02X".format(a)})"
        }
}

/** The Flipper Zero announces its own service; the colour is in the UUID. */
enum class FlipperKind(val label: String) {
    Black("Flipper Zero (noir)"), White("Flipper Zero (blanc)"), Transparent("Flipper Zero (transparent)")
}

/** What the advertisements around point to. */
enum class AdvertAbuse(val label: String) {
    KnownAttack(S(R.string.advert_known_attack)),
    PopupSpam(S(R.string.advert_pairing_spam)),
    Cycling(S(R.string.advert_cycling)),
    Flipper(S(R.string.advert_flipper)),
    OneRadio(S(R.string.advert_one_radio)),
}

enum class AdvertLevel(val label: String) {
    Calm(S(R.string.advert_calm)),
    Suspect(S(R.string.advert_suspect)),
    Attack(S(R.string.advert_attack)),
}

/** One thing noticed, with the count it rests on and a sentence saying what was measured. */
data class AdvertFinding(val abuse: AdvertAbuse, val count: Int, val detail: String)

data class AdvertVerdict(
    val level: AdvertLevel,
    /** Addresses sending a pop-up frame right now. */
    val sources: Int,
    /** Addresses alive in the window, pop-up frames or not. */
    val advertisers: Int,
    /** The frames recognised in the window, most recent first. */
    val frames: List<SpamHit>,
    val findings: List<AdvertFinding>,
    /** The addresses the findings rest on: what the page lists so the reading can be checked. */
    val suspects: List<String>,
)

/** One address as the window remembers it. */
data class AdvertSeen(
    val address: String,
    val firstMs: Long,
    val lastMs: Long,
    val rssi: Int,
    /** Every distinct frame this address has sent inside the window. */
    val hits: Set<SpamHit> = emptySet(),
    val flipper: FlipperKind? = null,
)

/**
 * Reads the advertisements around for the pop-up flooding attack: the one that buries a phone under
 * pairing and setup cards.
 *
 * Nothing here transmits. Recognising a frame is not enough to call an attack — your own earbuds send
 * one — so the frames are weighed by how they behave: a product sends one kind of card, from one
 * address, steadily; a flooder cycles through kinds, through Apple action numbers, and through
 * addresses, because that is the only way to keep the pop-ups coming.
 */
object AdvertDetect {
    /** Window the counts are taken over. */
    const val WindowMs = 60_000L

    /** Distinct addresses offering to pair before it is worth saying so. */
    const val PopupAddresses = 4

    /** Addresses offering to pair that no room explains, whatever their signal. */
    const val CrowdAddresses = 8

    /** Distinct frame kinds, or distinct Apple action numbers, that mean something is cycling. */
    const val CyclingKinds = 3

    /** Signal spread, in dB, under which a group reads as one radio rather than as several objects. */
    const val OneRadioSpreadDb = 6

    private const val APPLE = 0x004C
    private const val MICROSOFT = 0x0006
    private const val SAMSUNG = 0x0075
    private const val TYPO_PRODUCTS = 0x00FF
    private const val FAST_PAIR_UUID = 0xFE2C

    /** Apple Nearby Action numbers, from the furiousMAC Continuity dissector. */
    private val NearbyActions = mapOf(
        0x01 to "Apple TV Tap-To-Setup", 0x04 to "Mobile Backup", 0x05 to "Watch Setup",
        0x06 to "Apple TV Pair", 0x07 to "Internet Relay", 0x08 to "Wi-Fi Password",
        0x09 to "iOS Setup", 0x0A to "Repair", 0x0B to "Speaker Setup", 0x0C to "Apple Pay",
        0x0D to "Whole Home Audio Setup", 0x0E to "Developer Tools Pairing Request",
        0x0F to "Answered Call", 0x10 to "Ended Call", 0x11 to "DD Ping", 0x12 to "DD Pong",
        0x13 to "Remote Auto Fill", 0x14 to "Companion Link Prox", 0x15 to "Remote Management",
        0x16 to "Remote Auto Fill Pong", 0x17 to "Remote Display",
    )

    fun nearbyAction(type: Int): String? = NearbyActions[type]

    private fun ByteArray.startsWithHex(prefix: String): Boolean {
        if (size * 2 < prefix.length) return false
        for (i in prefix.indices step 2) {
            if (this.u8(i / 2) != prefix.substring(i, i + 2).toInt(16)) return false
        }
        return true
    }

    private fun ByteArray.hexString() = joinToString("") { "%02X".format(it) }

    /** The pop-up frame a payload carries, or null. Recognition only: nothing is sent. */
    fun classify(ads: List<AdStructure>): SpamHit? {
        val mfg = Ad.manufacturer(ads)

        mfg[APPLE]?.let { d ->
            // Nearby Action (0x0F) of length 5, with the flag byte the flooders use.
            if (d.startsWithHex("0F05C0") || d.startsWithHex("0F0540")) {
                val action = if (d.size >= 4) d.u8(3) else null
                // A Nearby Action that says length 5 is 7 bytes long. The frame that locked iOS 17 up
                // hangs its payload past that end, starting 00 00 10 — and 0x00 begins no Continuity
                // message, so a well-formed second message cannot look like this. The detectors it was
                // taken from search the whole payload for those bytes; this only reads what trails.
                val tail = if (d.size > 7) d.copyOfRange(7, d.size).hexString() else ""
                return if (tail.startsWith("000010")) SpamHit(SpamFrame.Ios17Crash, action) else SpamHit(SpamFrame.ActionModal, action)
            }
            // Proximity Pairing (0x07) of length 0x19; the byte that follows is the model asked for.
            if (d.startsWithHex("0719")) return SpamHit(SpamFrame.ProximityPairing, if (d.size >= 3) d.u8(2) else null)
        }
        mfg[MICROSOFT]?.let { if (it.startsWithHex("030080")) return SpamHit(SpamFrame.SwiftPair) }
        mfg[SAMSUNG]?.let { d ->
            val h = d.hexString()
            if (h.startsWith("42098102141503210109") && h.endsWith("063C948E00000000C700")) return SpamHit(SpamFrame.EasySetupBuds)
            if (h.startsWith("010002000101FF000043")) return SpamHit(SpamFrame.EasySetupWatch)
        }
        mfg[TYPO_PRODUCTS]?.let { d ->
            val h = d.hexString()
            if (h.startsWith("FFFF006DB643CE97FE427C") || h.startsWith("6DB643CE97FE427C")) return SpamHit(SpamFrame.Lovespouse)
        }
        // Fast Pair raises its card only in the discoverable form: a bare 3-byte model id. Once an
        // accessory is paired it broadcasts a longer account-key filter instead, which shows nothing.
        Ad.serviceData(ads)[FAST_PAIR_UUID]?.let { d ->
            if (d.size == 3) return SpamHit(SpamFrame.FastPair, (d.u8(0) shl 16) or d.u16be(1))
        }
        return null
    }

    /** The Flipper Zero advertises one of three service UUIDs, one per shell colour. */
    fun flipper(ads: List<AdStructure>, address: String, name: String?): FlipperKind? {
        val u16 = Ad.uuids16(ads)
        val u128 = Ad.uuids128(ads)
        fun has(v: Int) = v in u16 || u128.any { it.startsWith("%08X-0000-1000-8000-00805F9B34FB".format(v)) }
        when {
            has(0x3081) -> return FlipperKind.Black
            has(0x3082) -> return FlipperKind.White
            has(0x3083) -> return FlipperKind.Transparent
        }
        // Its own name, and the blocks registered to Flipper Devices Inc.
        if (name?.lowercase()?.startsWith("flipper") == true) return FlipperKind.Black
        val oui = address.take(8).uppercase()
        if (oui == "80:E1:26" || oui == "80:E1:27" || oui == "0C:FA:22") return FlipperKind.Black
        return null
    }

    fun assess(seen: Collection<AdvertSeen>, nowMs: Long, windowMs: Long = WindowMs): AdvertVerdict {
        val live = seen.filter { nowMs - it.lastMs <= windowMs }
        val sending = live.filter { it.hits.isNotEmpty() }
        val hits = sending.flatMap { it.hits }
        val findings = mutableListOf<AdvertFinding>()
        val suspects = LinkedHashSet<String>()

        // A frame no product sends settles it by itself.
        val crafted = hits.filter { !it.frame.benignUse }.distinctBy { it.frame }
        if (crafted.isNotEmpty()) {
            findings += AdvertFinding(
                AdvertAbuse.KnownAttack,
                crafted.size,
                crafted.joinToString(", ") { it.frame.label },
            )
            sending.filter { s -> s.hits.any { !it.frame.benignUse } }.forEach { suspects += it.address }
        }

        // Cycling: a product sends one kind of card, a flooder walks a list.
        val kinds = hits.map { it.frame }.distinct()
        // A model byte or an action number is the variant inside one kind: AirPods report one, always
        // the same. Walking through them is the move a product has no reason to make.
        val variants = hits.filter { it.action != null }.distinctBy { it.frame to it.action }
        val cycling = kinds.size >= CyclingKinds || variants.size >= CyclingKinds
        if (cycling) {
            val what = if (variants.size >= kinds.size) {
                variants.take(4).joinToString(", ") { it.title }
            } else {
                kinds.take(4).joinToString(", ") { it.label }
            }
            findings += AdvertFinding(AdvertAbuse.Cycling, maxOf(kinds.size, variants.size), S(R.string.advert_cycling_detail, what))
            sending.forEach { suspects += it.address }
        }

        // The same card from more addresses than there are accessories in a room.
        if (sending.size >= PopupAddresses) {
            findings += AdvertFinding(
                AdvertAbuse.PopupSpam,
                sending.size,
                S(R.string.advert_pairing_detail, sending.size, kinds.take(3).joinToString(", ") { it.label }),
            )
            sending.forEach { suspects += it.address }
        }

        // A Flipper Zero nearby is worth naming, whether or not it is the one sending.
        val flippers = live.mapNotNull { it.flipper }
        if (flippers.isNotEmpty()) {
            findings += AdvertFinding(AdvertAbuse.Flipper, flippers.size, flippers.distinct().joinToString(", ") { it.label })
            live.filter { it.flipper != null }.forEach { suspects += it.address }
        }

        // Several objects sit at several distances; one radio wearing many addresses does not.
        val group = sending.filter { it.address in suspects }
        if (group.size >= PopupAddresses) {
            val spread = group.maxOf { it.rssi } - group.minOf { it.rssi }
            if (spread <= OneRadioSpreadDb) {
                findings += AdvertFinding(AdvertAbuse.OneRadio, group.size, S(R.string.advert_one_radio_detail, spread))
            }
        }

        // A handful of addresses offering to pair is a café full of earbuds, not an attack: it takes a
        // crafted frame, a list being walked, one radio behind the addresses, or a frank crowd.
        val oneRadio = findings.any { it.abuse == AdvertAbuse.OneRadio }
        val level = when {
            crafted.isNotEmpty() -> AdvertLevel.Attack
            cycling -> AdvertLevel.Attack
            sending.size >= PopupAddresses && oneRadio -> AdvertLevel.Attack
            sending.size >= CrowdAddresses -> AdvertLevel.Attack
            findings.isEmpty() -> AdvertLevel.Calm
            else -> AdvertLevel.Suspect
        }
        return AdvertVerdict(level, sending.size, live.size, hits.distinct(), findings, suspects.toList())
    }
}

/**
 * Remembers a minute of addresses so the counts outlive the scanner: [BluetoothRepository.bleScan]
 * forgets an address 15 s after its last packet, and a flooder's addresses are the short-lived ones.
 * Every frame an address has sent is kept, because the cycling is the point.
 */
class AdvertWatch(private val windowMs: Long = AdvertDetect.WindowMs) {
    private val seen = LinkedHashMap<String, AdvertSeen>()

    /**
     * The worst reading since the last reset, and when it happened. Flooding comes in bursts: a minute
     * later the window is empty again and the screen would be calm, as if nothing had passed. The page
     * keeps showing this until someone clears it.
     */
    var peak: AdvertVerdict? = null
        private set
    var peakAtMs = 0L
        private set

    val size get() = seen.size

    fun observe(devices: List<BleDevice>, nowMs: Long) {
        for (d in devices) {
            val old = seen[d.address]
            val hit = AdvertDetect.classify(d.ads)
            seen[d.address] = AdvertSeen(
                address = d.address,
                firstMs = old?.firstMs ?: nowMs,
                lastMs = nowMs,
                rssi = d.rssi,
                hits = if (hit == null) old?.hits.orEmpty() else old?.hits.orEmpty() + hit,
                flipper = AdvertDetect.flipper(d.ads, d.address, d.name) ?: old?.flipper,
            )
        }
        seen.values.removeAll { nowMs - it.lastMs > windowMs }
    }

    fun assess(nowMs: Long): AdvertVerdict {
        val verdict = AdvertDetect.assess(seen.values.toList(), nowMs, windowMs)
        val best = peak
        if (verdict.level != AdvertLevel.Calm && (best == null || verdict.level.ordinal >= best.level.ordinal)) {
            peak = verdict
            peakAtMs = nowMs
        }
        return verdict
    }

    fun reset() {
        seen.clear()
        peak = null
        peakAtMs = 0L
    }
}
