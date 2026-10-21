package com.allnetworktools.data

import kotlin.math.pow
import com.allnetworktools.R
import com.allnetworktools.util.S

enum class BeaconKind(val label: String) { IBeacon("iBeacon"), EddystoneUid("Eddystone UID"), EddystoneUrl("Eddystone URL"), EddystoneEid("Eddystone EID"), AltBeacon("AltBeacon") }

/**
 * A proximity beacon as it announces itself. [id] is what identifies it (UUID / major / minor, namespace / instance,
 * URL…), [txAt1m] the power it says it is received with at 1 m, used for the distance.
 */
data class Beacon(
    val kind: BeaconKind,
    val id: String,
    val details: List<Pair<String, String>>,
    val txAt1m: Int?,
    val maker: String? = null,
) {
    /** Log-distance path loss, exponent 2: a rough figure, walls and bodies change it a lot. */
    fun distanceM(rssi: Int): Double? = txAt1m?.let { 10.0.pow((it - rssi) / 20.0) }
}

/** Telemetry of an Eddystone beacon (TLM frame, sent next to its UID or URL frame). */
data class BeaconTelemetry(val batteryMv: Int?, val temperatureC: Double?, val advCount: Long, val uptimeS: Long)

object Beacons {
    private fun hex(d: ByteArray, from: Int, to: Int) = (from until to).joinToString("") { "%02X".format(d[it]) }

    private fun uuid(d: ByteArray, o: Int): String {
        val h = hex(d, o, o + 16)
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
    }

    private val UrlSchemes = listOf("http://www.", "https://www.", "http://", "https://")
    private val UrlCodes = listOf(".com/", ".org/", ".edu/", ".net/", ".info/", ".biz/", ".gov/", ".com", ".org", ".edu", ".net", ".info", ".biz", ".gov")

    /** Eddystone-URL: scheme prefix, then text where bytes 0x00–0x0D stand for common endings. */
    fun eddystoneUrl(d: ByteArray, from: Int): String? {
        if (from >= d.size) return null
        val scheme = UrlSchemes.getOrNull(d.u8(from)) ?: return null
        return buildString {
            append(scheme)
            for (i in from + 1 until d.size) {
                val b = d.u8(i)
                when {
                    b < UrlCodes.size -> append(UrlCodes[b])
                    b in 0x21..0x7E -> append(b.toChar())
                    else -> return null
                }
            }
        }
    }

    /** The beacon in an advertisement, or null. Google's Find Hub frames (0x40/0x41 on 0xFEAA) are trackers, not beacons. */
    fun parse(ads: List<AdStructure>): Beacon? {
        val mfr = Ad.manufacturer(ads)
        mfr[0x004C]?.let { d ->
            // Apple Continuity type 0x02, 21 bytes: UUID, major, minor, measured power.
            if (d.size >= 23 && d.u8(0) == 0x02 && d.u8(1) == 0x15) {
                val id = uuid(d, 2)
                val major = d.u16be(18)
                val minor = d.u16be(20)
                return Beacon(
                    BeaconKind.IBeacon, "$id · $major / $minor",
                    listOf("UUID" to id, "Major" to major.toString(), "Minor" to minor.toString()),
                    d[22].toInt(), BleIdentify.IBeaconUuids[id],
                )
            }
        }
        for ((company, d) in mfr) {
            // AltBeacon: 0xBEAC, 20-byte identifier, reference RSSI at 1 m.
            if (d.size >= 24 && d.u8(0) == 0xBE && d.u8(1) == 0xAC) {
                val id = uuid(d, 2)
                val a = d.u16be(18)
                val b = d.u16be(20)
                return Beacon(
                    BeaconKind.AltBeacon, "$id · $a / $b",
                    listOf(S(R.string.identifier) to id, "ID 2" to a.toString(), "ID 3" to b.toString(), S(R.string.manufacturer) to "0x%04X".format(company)),
                    d[22].toInt(),
                )
            }
        }
        Ad.serviceData(ads)[0xFEAA]?.takeIf { it.size >= 2 }?.let { d ->
            // Eddystone power is given at 0 m: 41 dB more than at 1 m.
            val tx = d[1].toInt() - 41
            when (d.u8(0)) {
                0x00 -> if (d.size >= 18) {
                    val ns = hex(d, 2, 12)
                    val inst = hex(d, 12, 18)
                    return Beacon(BeaconKind.EddystoneUid, "$ns · $inst", listOf(S(R.string.namespace) to ns, "Instance" to inst), tx)
                }
                0x10 -> eddystoneUrl(d, 2)?.let { url -> return Beacon(BeaconKind.EddystoneUrl, url, listOf(S(R.string.address) to url), tx) }
                0x30 -> if (d.size >= 10) {
                    val eid = hex(d, 2, 10)
                    return Beacon(BeaconKind.EddystoneEid, eid, listOf(S(R.string.ephemeral_identifier) to eid), tx)
                }
            }
        }
        return null
    }

    /** Eddystone TLM, version 0 (unencrypted). */
    fun telemetry(ads: List<AdStructure>): BeaconTelemetry? {
        val d = Ad.serviceData(ads)[0xFEAA] ?: return null
        if (d.size < 14 || d.u8(0) != 0x20 || d.u8(1) != 0x00) return null
        val mv = d.u16be(2).takeIf { it != 0 }
        val rawT = d.u16be(4)
        val temp = if (rawT == 0x8000) null else rawT.toShort() / 256.0
        val adv = ((d.u8(6).toLong() shl 24) or (d.u8(7).toLong() shl 16) or (d.u8(8).toLong() shl 8) or d.u8(9).toLong())
        val sec = ((d.u8(10).toLong() shl 24) or (d.u8(11).toLong() shl 16) or (d.u8(12).toLong() shl 8) or d.u8(13).toLong())
        return BeaconTelemetry(mv, temp, adv, sec / 10)
    }
}

/** One earbud or the case: charge in percent (null when unknown) and whether it is charging. */
data class Battery(val percent: Int?, val charging: Boolean)

data class EarbudsStatus(val model: String, val left: Battery?, val right: Battery?, val case: Battery?, val single: Battery?)

/**
 * Battery of AirPods and Beats, from the "proximity pairing" frame they broadcast while out of their case or with the
 * lid open (Apple type 0x07), read as the open-source OpenPods app does: one nibble per earbud and for the case, in
 * tens of percent (15 = unknown), flags for charging, and a bit telling which nibble is the left one.
 */
object Earbuds {
    /** Headphones with one battery (no left/right). */
    private val Single = setOf(0x0A20, 0x0620, 0x0920, 0x0C20, 0x1020)

    private fun level(n: Int): Int? = if (n in 0..10) n * 10 else null

    fun parse(ads: List<AdStructure>): EarbudsStatus? {
        val d = Ad.manufacturer(ads)[0x004C] ?: return null
        if (d.size < 8 || d.u8(0) != 0x07 || d.u8(1) < 6) return null
        val model = d.u16be(3)
        val name = BleIdentify.AirPods[model] ?: S(R.string.apple_beats_earbuds_0x).format(model)
        val status = d.u8(5)
        val flip = (status shr 4) and 0x02 == 0
        val battery = d.u8(6)
        val charge = d.u8(7) shr 4
        val caseLevel = d.u8(7) and 0x0F
        val leftN = if (flip) battery shr 4 else battery and 0x0F
        val rightN = if (flip) battery and 0x0F else battery shr 4
        val leftCharging = charge and (if (flip) 0x02 else 0x01) != 0
        val rightCharging = charge and (if (flip) 0x01 else 0x02) != 0
        if (model in Single) return EarbudsStatus(name, null, null, null, Battery(level(battery shr 4) ?: level(battery and 0x0F), charge and 0x03 != 0))
        return EarbudsStatus(
            name,
            Battery(level(leftN), leftCharging),
            Battery(level(rightN), rightCharging),
            Battery(level(caseLevel), charge and 0x04 != 0),
            null,
        )
    }
}
