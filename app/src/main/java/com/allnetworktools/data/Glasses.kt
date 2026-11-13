package com.allnetworktools.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.allnetworktools.R
import com.allnetworktools.util.S
import kotlin.math.pow

/** How sure a match is: a company identifier only one product line uses, or one shared with headsets, or a name. */
enum class GlassesConfidence { High, Medium, Low }

/**
 * Smart glasses heard over Bluetooth LE. The identifiers come from the Bluetooth SIG's assigned numbers (company
 * identifiers and member UUIDs, bitbucket.org/bluetooth-SIG/public), each checked there, and from the published findings
 * of Nearby Glasses (Yves Jeanrenaud), NoPeek and ZuckOff. A match says camera-capable hardware is in range, not that
 * it is recording, nor who wears it.
 */
data class GlassesHit(
    val address: String,
    val product: String,
    /** True: has a camera; false: display only; null: depends on the model. */
    val camera: Boolean?,
    val confidence: GlassesConfidence,
    val evidence: List<String>,
    val name: String?,
    val rssi: Int,
) {
    /** A rough distance from the signal strength (about -59 dBm at one metre), in metres. */
    val distanceM: Double get() = 10.0.pow((-59.0 - rssi) / 20.0).coerceIn(0.3, 60.0)
}

object Glasses {
    private class Rule(val product: () -> String, val camera: Boolean?, val confidence: GlassesConfidence, val evidence: () -> String)

    // Company identifiers (Bluetooth SIG): one product line, or a maker whose other products may match too.
    private val companies: Map<Int, Rule> = mapOf(
        0x0D53 to Rule({ S(R.string.glasses_rayban_meta) }, true, GlassesConfidence.High) { S(R.string.glasses_ev_company, "0x0D53", "Luxottica") },
        0x03C2 to Rule({ "Snap Spectacles" }, true, GlassesConfidence.High) { S(R.string.glasses_ev_company, "0x03C2", "Snapchat") },
        0x058E to Rule({ S(R.string.glasses_meta_wearable) }, true, GlassesConfidence.Medium) { S(R.string.glasses_ev_company, "0x058E", "Meta Platforms Technologies") },
        0x01AB to Rule({ S(R.string.glasses_meta_device) }, true, GlassesConfidence.Medium) { S(R.string.glasses_ev_company, "0x01AB", "Meta Platforms") },
        0x060C to Rule({ S(R.string.glasses_vuzix) }, null, GlassesConfidence.Medium) { S(R.string.glasses_ev_company, "0x060C", "Vuzix") },
        0x10F9 to Rule({ "Even Realities" }, false, GlassesConfidence.Low) { S(R.string.glasses_ev_company, "0x10F9", "Even Realities") },
    )

    // 16-bit service UUIDs registered to these makers (member UUIDs).
    private val services16: Map<Int, Rule> = mapOf(
        0xFE45 to Rule({ "Snap Spectacles" }, true, GlassesConfidence.High) { S(R.string.glasses_ev_service, "0xFE45", "Snapchat") },
        0xFD5F to Rule({ S(R.string.glasses_meta_wearable) }, true, GlassesConfidence.Medium) { S(R.string.glasses_ev_service, "0xFD5F", "Meta Platforms Technologies (Oculus)") },
        0xFEB7 to Rule({ S(R.string.glasses_meta_device) }, true, GlassesConfidence.Medium) { S(R.string.glasses_ev_service, "0xFEB7", "Meta Platforms") },
        0xFEB8 to Rule({ S(R.string.glasses_meta_device) }, true, GlassesConfidence.Medium) { S(R.string.glasses_ev_service, "0xFEB8", "Meta Platforms") },
    )

    /** The service of the HeyCyan glasses platform (sold under several brands, Nilox among them). */
    const val HEYCYAN_SERVICE = "7905FFF0-B5CE-4E99-A40F-4B1E122D00D0"

    // Names, only sent by some models (while pairing, or by every glasses of a brand): lower confidence, easy to fake.
    private class NameRule(val words: List<String>, val product: () -> String, val camera: Boolean?, val confidence: GlassesConfidence)

    private val names = listOf(
        NameRule(listOf("ray-ban", "rayban", "ray ban"), { S(R.string.glasses_rayban_meta) }, true, GlassesConfidence.High),
        NameRule(listOf("oakley meta", "meta hstn", "meta vanguard"), { S(R.string.glasses_rayban_meta) }, true, GlassesConfidence.High),
        NameRule(listOf("spectacles"), { "Snap Spectacles" }, true, GlassesConfidence.High),
        NameRule(listOf("heycyan"), { S(R.string.glasses_heycyan) }, true, GlassesConfidence.High),
        NameRule(listOf("rayneo", "rokid", "inmo air", "brilliant frame", "xreal", "nreal", "vuzix", "visionpro", "vistaview", "aircam"), { S(R.string.glasses_other) }, null, GlassesConfidence.Medium),
        NameRule(listOf("even g1", "even g2", "halliday"), { S(R.string.glasses_display_only) }, false, GlassesConfidence.Low),
    )

    /** Names that say a Meta identifier is a VR headset, not glasses. */
    private val headsetWords = listOf("quest", "oculus")

    /** What [d] is, when its advertisement points to smart glasses; null otherwise. */
    fun match(d: BleDevice): GlassesHit? {
        val ads = d.raw?.let { runCatching { Ad.parseHex(it) }.getOrNull() } ?: emptyList()
        val companyIds = buildSet {
            d.companyId?.let(::add)
            ads.filter { it.type == Ad.MANUFACTURER && it.data.size >= 2 }.forEach { add(it.data.u16le(0)) }
        }
        val uuids16 = buildSet {
            ads.filter { it.type in setOf(Ad.UUID16, Ad.UUID16_PARTIAL, Ad.SOLICIT16) }.forEach { s -> for (i in 0 until s.data.size / 2) add(s.data.u16le(2 * i)) }
            ads.filter { it.type == Ad.SERVICE_DATA16 && it.data.size >= 2 }.forEach { add(it.data.u16le(0)) }
            d.services.forEach { u -> parse16(u)?.let(::add) }
        }
        val uuids128 = buildSet {
            ads.filter { it.type in setOf(Ad.UUID128, Ad.UUID128_PARTIAL, Ad.SOLICIT128) }.forEach { s ->
                for (i in 0 until s.data.size / 16) add(uuid128(s.data, 16 * i))
            }
            d.services.filter { it.length == 36 }.forEach { add(it.uppercase()) }
        }
        val name = d.name?.takeIf { it.isNotBlank() }
        val lower = name?.lowercase()

        class Found(val product: String, val camera: Boolean?, val confidence: GlassesConfidence, val evidence: String)
        val found = ArrayList<Found>()
        companyIds.forEach { id -> companies[id]?.let { found += Found(it.product(), it.camera, it.confidence, it.evidence()) } }
        uuids16.forEach { u -> services16[u]?.let { found += Found(it.product(), it.camera, it.confidence, it.evidence()) } }
        if (HEYCYAN_SERVICE in uuids128) found += Found(S(R.string.glasses_heycyan), true, GlassesConfidence.High, S(R.string.glasses_ev_service, "7905FFF0…", "HeyCyan"))
        if (lower != null) names.firstOrNull { r -> r.words.any { it in lower } }?.let { r ->
            found += Found(r.product(), r.camera, r.confidence, S(R.string.glasses_ev_name, name))
        }
        if (found.isEmpty()) return null

        // The surest finding names the product; agreeing ones only add evidence.
        val best = found.minBy { it.confidence.ordinal }
        var product = best.product
        var camera = best.camera
        var confidence = best.confidence
        // A Meta identifier with a headset's name: a Quest (cameras too, but not glasses).
        val metaOnly = found.all { it.confidence != GlassesConfidence.High }
        if (metaOnly && lower != null && headsetWords.any { it in lower }) { product = S(R.string.glasses_meta_headset); confidence = GlassesConfidence.Low }
        // Two independent signs (identifier and name, or two identifiers) raise a medium match.
        if (confidence == GlassesConfidence.Medium && found.size >= 2 && found.map { it.evidence }.distinct().size >= 2 && !(lower != null && headsetWords.any { it in lower })) confidence = GlassesConfidence.High
        if (camera == null) camera = found.firstNotNullOfOrNull { it.camera }
        return GlassesHit(d.address, product, camera, confidence, found.map { it.evidence }.distinct(), name, d.rssi)
    }

    private fun parse16(u: String): Int? {
        val s = u.trim().uppercase().removePrefix("0X")
        return when {
            s.length == 4 -> s.toIntOrNull(16)
            s.length == 36 && s.endsWith("-0000-1000-8000-00805F9B34FB") && s.startsWith("0000") -> s.substring(4, 8).toIntOrNull(16)
            else -> null
        }
    }

    /** A 128-bit UUID as sent (least significant byte first), in the usual written form. */
    private fun uuid128(b: ByteArray, o: Int): String {
        val h = (15 downTo 0).joinToString("") { "%02X".format(b[o + it]) }
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
    }
}

/** One device's track in the session: the latest match and when it was first and last heard. */
data class GlassesSeen(val hit: GlassesHit, val firstMs: Long, val lastMs: Long, val bestRssi: Int)

/**
 * The glasses heard this session, shared by the screen and the background engine. Advertising addresses change every
 * quarter of an hour or so, so one pair may appear more than once over time.
 */
class GlassesWatch {
    var seen by mutableStateOf<List<GlassesSeen>>(emptyList())
        private set
    var version by mutableIntStateOf(0)
        private set
    /** Alerts and "nearby" count only at or above this signal: -75 dBm is about 10 m outdoors, 3 to 10 m indoors. */
    var threshold by mutableIntStateOf(-75)

    private val map = LinkedHashMap<String, GlassesSeen>()

    fun update(devices: List<BleDevice>, nowMs: Long) {
        var changed = false
        for (d in devices) {
            val h = Glasses.match(d) ?: continue
            val old = map[d.address]
            map[d.address] = GlassesSeen(h, old?.firstMs ?: nowMs, nowMs, maxOf(old?.bestRssi ?: h.rssi, h.rssi))
            changed = true
        }
        // A track not heard for an hour is let go.
        if (map.values.removeAll { nowMs - it.lastMs > 3_600_000L }) changed = true
        if (changed) { seen = map.values.sortedByDescending { it.lastMs }; version++ }
    }

    /** Camera glasses heard in the last half minute, at or above the threshold. */
    fun nearby(nowMs: Long): List<GlassesSeen> = seen.filter { nowMs - it.lastMs <= 30_000 && it.hit.rssi >= threshold && it.hit.camera != false }

    fun clear() { map.clear(); seen = emptyList(); version++ }
}
