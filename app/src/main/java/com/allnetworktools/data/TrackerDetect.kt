package com.allnetworktools.data

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import com.allnetworktools.R
import com.allnetworktools.util.S

/** Networks whose tags can be used to follow someone. */
enum class TrackerNet(val label: String, val maker: String) {
    AppleFindMy(S(R.string.find_my_apple), S(R.string.airtag_or_find_my_accessory)),
    GoogleFindHub(S(R.string.find_hub_google), S(R.string.google_network_tag)),
    SamsungFind("SmartThings Find", "Galaxy SmartTag"),
    Tile("Tile", "Tile"),
    Chipolo("Chipolo", "Chipolo"),
    Pebblebee("Pebblebee", "Pebblebee"),
}

/**
 * What an advertisement says about a tracker. [separated] is true when the tag announces it is away
 * from its owner (Apple offline-finding frame, Google "unwanted tracking protection" frame), false when
 * it announces its owner is nearby, and null when the protocol does not tell.
 */
/** What kind of object sends the frames, as far as the frames tell. */
enum class TrackerKind(val label: String) {
    AirTag("AirTag"),
    AppleDevice(S(R.string.apple_device)),
    AirPods("AirPods"),
    GoogleTag(S(R.string.google_find_hub_tag)),
    SmartTag("Galaxy SmartTag"),
    GalaxyBuds("Galaxy Buds"),
    Tile("Tile"),
    Chipolo("Chipolo"),
    Pebblebee("Pebblebee"),
}

data class TrackerSignal(
    val net: TrackerNet,
    val separated: Boolean?,
    /** Precise state when the protocol gives one (SmartTag: "déconnecté depuis moins de 15 min"…). */
    val state: String? = null,
    /**
     * Value that stays the same across a change of Bluetooth address, used to keep following a tag
     * whose address rotates (SmartTag: epoch counter + rotating identifier).
     */
    val linkKey: String? = null,
    /** Apple Find My status byte, shown to help tell what the tag is. */
    val appleStatus: Int? = null,
    /**
     * SmartTag aging counter and properties byte: an offline tag changes identity every 15 minutes, the counter
     * going up by one each time (AirGuard's "15 minute algorithm" links the two on this).
     */
    val agingCounter: Int? = null,
    val props: Int? = null,
    val kind: TrackerKind = when (net) {
        TrackerNet.AppleFindMy -> TrackerKind.AppleDevice
        TrackerNet.GoogleFindHub -> TrackerKind.GoogleTag
        TrackerNet.SamsungFind -> TrackerKind.SmartTag
        TrackerNet.Tile -> TrackerKind.Tile
        TrackerNet.Chipolo -> TrackerKind.Chipolo
        TrackerNet.Pebblebee -> TrackerKind.Pebblebee
    },
)

data class Sighting(val timeMs: Long, val lat: Double?, val lon: Double?, val rssi: Int)

enum class FollowLevel(val label: String) {
    Following(S(R.string.following_you)),
    Watch(S(R.string.watch_2)),
    Nearby(S(R.string.nearby)),
    WithOwner(S(R.string.with_its_owner)),
}

data class FollowAssessment(
    val level: FollowLevel,
    val durationMs: Long,
    /** Largest distance between two places the tag was seen, in metres; null without positions. */
    val spreadM: Double?,
    val places: Int,
    val sightings: Int,
)

object TrackerDetect {
    /** Minimum time with you before a separated tag is reported as following. */
    const val FollowMs = 10 * 60_000L
    const val FollowSpreadM = 300.0
    const val WatchMs = 5 * 60_000L
    const val WatchSpreadM = 100.0
    const val PlaceRadiusM = 150.0

    fun classify(ads: List<AdStructure>, address: String? = null): TrackerSignal? {
        Ad.manufacturer(ads)[0x004C]?.let { d ->
            // Continuity TLV: type 0x12 is Find My. A 25-byte payload carries the full rotating key, which
            // accessories only broadcast in "offline finding" mode, i.e. away from their owner's devices.
            var i = 0
            while (i + 2 <= d.size) {
                val t = d.u8(i)
                val l = d.u8(i + 1)
                if (t == 0x12) return TrackerSignal(TrackerNet.AppleFindMy, l >= 0x19, appleStatus = if (l >= 1 && i + 2 < d.size) d.u8(i + 2) else null, kind = appleKind(d, i + 2, l))
                if (t == 0) break
                i += 2 + l
            }
        }
        val sd = Ad.serviceData(ads)
        sd[0xFEAA]?.takeIf { it.isNotEmpty() }?.let { d ->
            // Find Hub network (FMDN): frame 0x40, or 0x41 while "unwanted tracking protection" is on.
            when (d.u8(0)) {
                0x40 -> return TrackerSignal(TrackerNet.GoogleFindHub, false)
                0x41 -> return TrackerSignal(TrackerNet.GoogleFindHub, true)
            }
        }
        val buds = isGalaxyBuds(Ad.name(ads))
        sd[0xFD5A]?.let { return smartTag(it).let { s -> if (buds) s.copy(kind = TrackerKind.GalaxyBuds) else s } }
        val uuids = Ad.uuids16(ads)
        if (0xFD5A in uuids) return TrackerSignal(TrackerNet.SamsungFind, null, kind = if (buds) TrackerKind.GalaxyBuds else TrackerKind.SmartTag)
        // Galaxy Buds out of their case also advertise under their own name with Samsung's manufacturer data.
        if (buds && 0x0075 in Ad.manufacturer(ads)) return TrackerSignal(TrackerNet.SamsungFind, null, kind = TrackerKind.GalaxyBuds)
        if (uuids.any { it == 0xFEED || it == 0xFEEC || it == 0xFD84 }) return TrackerSignal(TrackerNet.Tile, null)
        if (0xFE33 in uuids || sd.containsKey(0xFE33)) return chipolo(sd[0xFE33], address)
        if (0xFA25 in uuids || sd.containsKey(0xFA25)) return TrackerSignal(TrackerNet.Pebblebee, null)
        return null
    }

    private val BudsName = Regex("galaxy\\s*buds|\\bbuds\\s*(\\d|pro|live|fe|core)", RegexOption.IGNORE_CASE)

    /** Samsung earbuds, by the name they advertise ("Galaxy Buds2 Pro (1A2B)"). */
    fun isGalaxyBuds(name: String?) = name != null && BudsName.containsMatchIn(name)

    /**
     * Find My status byte, bits 4–5: the kind of Apple object (as read by the open-source AirGuard app):
     * 0x10 AirTag, 0x30 AirPods, anything else an Apple device (iPhone, iPad, Mac, third-party accessory…). Recent
     * AirTags send the same value as other accessories: [kindFromProbe] tells them apart once connected.
     */
    private fun appleKind(d: ByteArray, o: Int, len: Int): TrackerKind {
        if (len < 1 || o >= d.size) return TrackerKind.AppleDevice
        return when (d.u8(o) and 0x30) {
            0x10 -> TrackerKind.AirTag
            0x30 -> TrackerKind.AirPods
            else -> TrackerKind.AppleDevice
        }
    }

    /**
     * Kind given by what a Find My accessory tells over GATT: its name ("AirTag", "left"/"right" for one AirPod) or
     * the first-generation AirTag service. Null when that says nothing more than the advertisement.
     */
    fun kindFromProbe(name: String?, airTagService: Boolean, vararg models: String?): TrackerKind? {
        val n = name?.trim()?.lowercase()
        val m = models.filterNotNull().map { it.lowercase() }
        return when {
            n != null && n.startsWith("airtag") || m.any { "airtag" in it } -> TrackerKind.AirTag
            n != null && (n == "left" || n == "right" || n.startsWith("airpods")) || m.any { "airpods" in it } -> TrackerKind.AirPods
            airTagService -> TrackerKind.AirTag
            else -> null
        }
    }

    /**
     * Galaxy SmartTag service data (0xFD5A, 20 bytes), after a reverse engineering of the SmartTag 2:
     * byte 0 = link with the owner's phone (0x15 connected, 0x11 lost for less than 15 min, 0x12 for
     * 15 min or more), byte 1 = epoch counter (+1 every 15 min), bytes 4-11 = identifier rotating with
     * the epoch, bytes 16-19 = check value. The address rotates on its own 15 min timer, shifted by
     * about 7 min from the epoch, and on every state change: epoch + identifier bridge each rotation.
     */
    /**
     * SmartTag state, read as AirGuard (seemoo-lab, Apache 2.0) does from the 3 low bits of the first byte:
     * 011 overmature offline (away from its owner for long) and 010 offline are the only states a tracking tag
     * can be in; 001 premature offline (just lost its owner) and every value with bit 2 set (connected) are safe.
     */
    fun smartTag(d: ByteArray): TrackerSignal {
        if (d.isEmpty()) return TrackerSignal(TrackerNet.SamsungFind, null)
        val status = d.u8(0)
        val (separated, state) = when (status) {
            0x15 -> false to S(R.string.connected_to_the_owner_s_phone)
            0x11 -> false to S(R.string.disconnected_from_the_owner_for_less)
            0x12 -> true to S(R.string.disconnected_from_the_owner_for_15)
            else -> when (status and 0x07) {
                3 -> true to S(R.string.disconnected_for_a_long_time_0x).format(status)
                2 -> true to S(R.string.disconnected_for_15_min_or_more).format(status)
                1 -> false to S(R.string.disconnected_for_less_than_15_min).format(status)
                else -> false to S(R.string.connected_to_the_owner_0x).format(status)
            }
        }
        val key = if (d.size >= 12) "%02X:".format(d.u8(1)) + d.hex(4, 12).replace(" ", "") else null
        val aging = if (d.size >= 4) (d.u8(3) shl 16) or (d.u8(2) shl 8) or d.u8(1) else null
        val props = if (d.size >= 13) d.u8(12) else null
        return TrackerSignal(TrackerNet.SamsungFind, separated, state, key, agingCounter = aging, props = props)
    }

    /**
     * Chipolo (AirGuard): bit 0 of the second service-data byte is set once the tag has not seen its owner for
     * 30 minutes. Firmware is told by the first byte of the address; from D9 on the bit is gone, the state unknown.
     */
    private fun chipolo(d: ByteArray?, address: String?): TrackerSignal {
        val firmware = address?.take(2)?.uppercase()
        if (d == null || d.size < 2 || firmware == null || firmware > "D8") return TrackerSignal(TrackerNet.Chipolo, null)
        val away = d.u8(1) and 1 == 1
        return TrackerSignal(TrackerNet.Chipolo, away)
    }

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * 6_371_008.8 * asin(sqrt(a))
    }

    fun assess(signal: TrackerSignal, s: List<Sighting>): FollowAssessment {
        val duration = if (s.isEmpty()) 0L else s.maxOf { it.timeMs } - s.minOf { it.timeMs }
        val located = s.filter { it.lat != null && it.lon != null }
        var spread: Double? = null
        val places = mutableListOf<Pair<Double, Double>>()
        if (located.isNotEmpty()) {
            val first = located.first()
            spread = located.maxOf { distanceM(first.lat!!, first.lon!!, it.lat!!, it.lon!!) }
            located.forEach { p ->
                if (places.none { distanceM(it.first, it.second, p.lat!!, p.lon!!) < PlaceRadiusM }) places += p.lat!! to p.lon!!
            }
        }
        val level = when {
            signal.separated == false -> FollowLevel.WithOwner
            spread != null && duration >= FollowMs && (spread >= FollowSpreadM || places.size >= 3) -> FollowLevel.Following
            spread == null && duration >= 2 * FollowMs -> FollowLevel.Watch
            duration >= WatchMs || (spread ?: 0.0) >= WatchSpreadM -> FollowLevel.Watch
            else -> FollowLevel.Nearby
        }
        return FollowAssessment(level, duration, spread, places.size, s.size)
    }
}
