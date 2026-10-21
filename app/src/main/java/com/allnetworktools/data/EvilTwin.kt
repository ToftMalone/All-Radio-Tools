package com.allnetworktools.data

import com.allnetworktools.R
import com.allnetworktools.util.S

/** How worrying a network looks. */
enum class TwinRisk(val label: String) { High(S(R.string.high_risk)), Medium(S(R.string.suspicious)), Low(S(R.string.worth_noting)), None("Normal") }

data class TwinFinding(val risk: TwinRisk, val title: String, val detail: String, val bssids: Set<String>)

data class SsidReport(val ssid: String, val aps: List<WifiAp>, val findings: List<TwinFinding>) {
    val risk: TwinRisk get() = findings.minByOrNull { it.risk.ordinal }?.risk ?: TwinRisk.None
    val flagged: Set<String> get() = findings.filter { it.risk <= TwinRisk.Medium }.flatMap { it.bssids }.toSet()
}

/**
 * Looks for signs of an "evil twin": another access point that copies the name of a network to
 * lure its clients. Wi-Fi cannot prove an AP is fake from a scan, so each rule explains what it saw.
 */
object EvilTwin {
    /** Security family: an attacker usually drops encryption or swaps enterprise for a password. */
    enum class SecClass { Open, Owe, Wep, Wpa, Wpa2, Wpa3, Enterprise }

    fun secClass(sec: String): SecClass = when {
        "EAP" in sec -> SecClass.Enterprise
        sec == "OWE" -> SecClass.Owe
        sec == "Ouvert" -> SecClass.Open
        sec == "WEP" -> SecClass.Wep
        sec == "WPA3" -> SecClass.Wpa3
        sec == "WPA2" || sec == "WPA2/3" -> SecClass.Wpa2
        sec == "WPA" -> SecClass.Wpa
        else -> SecClass.Wpa2
    }

    fun bytes(bssid: String): List<Int>? = bssid.split(':').takeIf { it.size == 6 }?.map { it.toIntOrNull(16) ?: return null }

    /** Locally administered: chosen by software (phone hotspot, travel router, virtual interface) rather than burnt in by a maker. */
    fun locallyAdministered(bssid: String): Boolean = bytes(bssid)?.let { it[0] and 0x02 != 0 } ?: false

    /**
     * Manufacturer key. APs derive their extra BSSIDs by setting the local bit and changing the first
     * byte, so only bytes 2-3 of the prefix are compared.
     */
    fun makerKey(bssid: String): String? = bytes(bssid)?.let { "%02X:%02X".format(it[1], it[2]) }

    fun prefix(bssid: String): String = bssid.uppercase().take(8)

    /** Networks broadcast by thousands of boxes of different generations: many makers is normal there. */
    private val community = listOf("freewifi", S(R.string.sfr_wifi), "bouygues telecom wi-fi", "orange wi-fi", "eduroam", "fon", "wifi-public", "_sncf")

    private fun isCommunity(ssid: String) = ssid.lowercase().let { s -> community.any { s.startsWith(it) } }

    /**
     * [firstSeen] maps each BSSID to when this session first saw it; [sessionStart] lets a BSSID that
     * appears later (while its SSID was already known) be reported.
     */
    fun analyze(
        scan: List<WifiAp>,
        firstSeen: Map<String, Long> = emptyMap(),
        ssidFirstSeen: Map<String, Long> = emptyMap(),
        now: Long = 0L,
    ): List<SsidReport> = scan.filter { it.ssid.isNotBlank() }.groupBy { it.ssid }.map { (ssid, aps) ->
        val findings = mutableListOf<TwinFinding>()
        val bySec = aps.groupBy { secClass(it.security) }
        val protectedAps = aps.filter { secClass(it.security) in setOf(SecClass.Wpa, SecClass.Wpa2, SecClass.Wpa3, SecClass.Enterprise, SecClass.Wep) }
        val open = bySec[SecClass.Open].orEmpty()
        if (open.isNotEmpty() && protectedAps.isNotEmpty()) {
            findings += TwinFinding(
                TwinRisk.High, S(R.string.same_name_no_password),
                S(R.string.this_network_but_open, protectedAps.size, if (protectedAps.size > 1) S(R.string.access_points_protect) else S(R.string.access_point_protects), open.size, if (open.size > 1) S(R.string.advertise_it) else S(R.string.advertises_it)) +
                    S(R.string.a_fake_open_access_point_can),
                open.map { it.bssid }.toSet(),
            )
        }
        val ent = bySec[SecClass.Enterprise].orEmpty()
        val personal = aps.filter { secClass(it.security) in setOf(SecClass.Wpa, SecClass.Wpa2, SecClass.Wpa3) }
        if (ent.isNotEmpty() && personal.isNotEmpty()) {
            val minority = if (ent.size <= personal.size) ent else personal
            findings += TwinFinding(
                TwinRisk.High, S(R.string.enterprise_and_password_mixed),
                S(R.string.this_network_is_advertised_both_as) +
                    S(R.string.rogue_access_points_imitate_a_company),
                minority.map { it.bssid }.toSet(),
            )
        }
        val wpa3Only = bySec[SecClass.Wpa3].orEmpty()
        val weaker = aps.filter { secClass(it.security) in setOf(SecClass.Wpa2, SecClass.Wpa, SecClass.Wep) }
        if (wpa3Only.isNotEmpty() && weaker.isNotEmpty()) {
            findings += TwinFinding(
                TwinRisk.Medium, S(R.string.security_downgraded),
                S(R.string.wpa3_an_older_protocol, wpa3Only.size, if (wpa3Only.size > 1) S(R.string.access_points_require) else S(R.string.access_point_requires), weaker.size, if (weaker.size > 1) "acceptent" else "accepte") +
                    S(R.string.this_is_the_pattern_of_a),
                weaker.map { it.bssid }.toSet(),
            )
        } else {
            val legacy = aps.filter { secClass(it.security) in setOf(SecClass.Wep, SecClass.Wpa) }
            if (legacy.isNotEmpty() && aps.any { secClass(it.security) in setOf(SecClass.Wpa2, SecClass.Wpa3) }) {
                findings += TwinFinding(
                    TwinRisk.Medium, S(R.string.obsolete_encryption),
                    S(R.string.some_access_points_use_wep_or),
                    legacy.map { it.bssid }.toSet(),
                )
            }
        }
        if (aps.size >= 2) {
            val keys = aps.groupBy { makerKey(it.bssid) }
            if (keys.size >= 2) {
                val main = keys.maxBy { it.value.size }
                val others = aps.filter { makerKey(it.bssid) != main.key }
                val community = isCommunity(ssid) || (aps.size >= 4 && keys.size >= 3)
                if (community) {
                    findings += TwinFinding(
                        TwinRisk.Low, S(R.string.several_manufacturers),
                        S(R.string.different_address_prefixes_normal_for_a, keys.size) +
                            S(R.string.only_connect_to_it_with_encryption),
                        emptySet(),
                    )
                } else {
                    val stronger = others.filter { o -> main.value.all { o.rssi >= it.rssi + 10 } }
                    findings += TwinFinding(
                        if (stronger.isNotEmpty()) TwinRisk.High else TwinRisk.Medium,
                        S(R.string.different_manufacturer),
                        S(R.string.an_address_prefix, others.size, if (others.size > 1) S(R.string.access_points_have) else S(R.string.access_point_has), others.map { prefix(it.bssid) }.distinct().joinToString(", ")) +
                            S(R.string.different_from_the_others_one_network, main.value.size, prefix(main.value.first().bssid)) +
                            (if (stronger.isNotEmpty()) S(R.string.and_this_one_is_received_much) else "."),
                        others.map { it.bssid }.toSet(),
                    )
                }
            }
            val soft = aps.filter { a -> locallyAdministered(a.bssid) && aps.none { b -> b !== a && bytes(b.bssid)?.drop(1)?.take(3) == bytes(a.bssid)?.drop(1)?.take(3) } }
            if (soft.isNotEmpty() && soft.size < aps.size) {
                findings += TwinFinding(
                    TwinRisk.Medium, S(R.string.software_generated_address),
                    S(R.string.locally_administered_with_no_link_to, soft.size, if (soft.size > 1) S(R.string.addresses_are) else S(R.string.address_is), if (soft.size > 1) "s" else "") +
                        S(R.string.typical_of_a_personal_hotspot_a),
                    soft.map { it.bssid }.toSet(),
                )
            }
            val known = ssidFirstSeen[ssid]
            val late = aps.filter { a -> known != null && (firstSeen[a.bssid] ?: known) - known > 60_000L && now - (firstSeen[a.bssid] ?: now) < 15 * 60_000L }
            if (late.isNotEmpty()) {
                findings += TwinFinding(
                    TwinRisk.Low, S(R.string.appeared_during_the_scan),
                    S(R.string.while_the_network_was_already_visible, late.size, if (late.size > 1) S(R.string.new_access_points_appeared) else S(R.string.new_access_point_appeared)),
                    late.map { it.bssid }.toSet(),
                )
            }
        }
        SsidReport(ssid, aps.sortedByDescending { it.rssi }, findings.sortedBy { it.risk.ordinal })
    }.sortedWith(compareBy<SsidReport> { it.risk.ordinal }.thenByDescending { it.aps.size }.thenBy { it.ssid.lowercase() })
}
