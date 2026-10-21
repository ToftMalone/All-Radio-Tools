package com.allnetworktools.data

import com.allnetworktools.R
import com.allnetworktools.util.S

/** One AD structure of an advertising payload: length, type, data (Bluetooth Core Spec, Vol 3 Part C §11). */
class AdStructure(val type: Int, val data: ByteArray) {
    val hex: String get() = data.joinToString("") { "%02X".format(it) }

    fun encode(): ByteArray = byteArrayOf((data.size + 1).toByte(), type.toByte()) + data

    override fun equals(other: Any?) = other is AdStructure && other.type == type && other.data.contentEquals(data)
    override fun hashCode() = 31 * type + data.contentHashCode()
    override fun toString() = "AD(0x%02X %s)".format(type, hex)
}

/** What a connection revealed about a device (Generic Access + Device Information services). */
data class GattIdentity(
    val name: String? = null,
    val appearance: Int? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    val firmware: String? = null,
    /** 16-bit UUIDs of the services found, e.g. 0x180D. */
    val services: Set<Int> = emptySet(),
)

internal fun ByteArray.u8(i: Int) = this[i].toInt() and 0xFF
internal fun ByteArray.u16le(i: Int) = u8(i) or (u8(i + 1) shl 8)
internal fun ByteArray.u16be(i: Int) = (u8(i) shl 8) or u8(i + 1)
internal fun ByteArray.hex(from: Int = 0, to: Int = size) = (from until to).joinToString(" ") { "%02X".format(this[it]) }

object Ad {
    const val FLAGS = 0x01
    const val UUID16_PARTIAL = 0x02
    const val UUID16 = 0x03
    const val UUID32_PARTIAL = 0x04
    const val UUID32 = 0x05
    const val UUID128_PARTIAL = 0x06
    const val UUID128 = 0x07
    const val NAME_SHORT = 0x08
    const val NAME = 0x09
    const val TX_POWER = 0x0A
    const val CLASS_OF_DEVICE = 0x0D
    const val SOLICIT16 = 0x14
    const val SOLICIT128 = 0x15
    const val SERVICE_DATA16 = 0x16
    const val APPEARANCE = 0x19
    const val ADV_INTERVAL = 0x1A
    const val SERVICE_DATA32 = 0x20
    const val SERVICE_DATA128 = 0x21
    const val MESH_PB_ADV = 0x29
    const val MESH_MESSAGE = 0x2A
    const val MESH_BEACON = 0x2B
    const val MANUFACTURER = 0xFF

    private val UuidLists = setOf(UUID16_PARTIAL, UUID16, UUID32_PARTIAL, UUID32, UUID128_PARTIAL, UUID128, SOLICIT16, SOLICIT128)

    /** Splits a raw payload (advertising data followed by the scan response) into AD structures. */
    fun parse(bytes: ByteArray): List<AdStructure> {
        val out = mutableListOf<AdStructure>()
        var i = 0
        while (i < bytes.size) {
            val len = bytes.u8(i)
            if (len == 0) break
            val end = minOf(i + 1 + len, bytes.size)
            if (i + 1 >= bytes.size) break
            out += AdStructure(bytes.u8(i + 1), bytes.copyOfRange(i + 2, end))
            i += len + 1
        }
        return out
    }

    /** Parses a hex string ("0201061AFF4C00…") as produced by [hex]. */
    fun parseHex(hex: String): List<AdStructure> {
        val h = hex.filter { !it.isWhitespace() }
        if (h.length % 2 != 0) return emptyList()
        return parse(ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
    }

    fun hex(ads: List<AdStructure>): String = ads.joinToString("") { s -> s.encode().joinToString("") { "%02X".format(it) } }

    /** Identity of a structure for merging: one manufacturer entry per company, one service data per UUID. */
    private fun key(s: AdStructure): String = when {
        s.type == MANUFACTURER && s.data.size >= 2 -> "FF:%04X".format(s.data.u16le(0))
        s.type == SERVICE_DATA16 && s.data.size >= 2 -> "16:%04X".format(s.data.u16le(0))
        s.type == SERVICE_DATA32 && s.data.size >= 4 -> "20:" + s.data.hex(0, 4)
        s.type == SERVICE_DATA128 && s.data.size >= 16 -> "21:" + s.data.hex(0, 16)
        else -> "%02X".format(s.type)
    }

    /**
     * Devices alternate advertising and scan-response packets, and some stacks report them
     * separately: keep the newest structure of each kind and the union of UUID lists.
     */
    fun merge(into: MutableMap<String, AdStructure>, incoming: List<AdStructure>) {
        for (s in incoming) {
            val k = key(s)
            val old = into[k]
            into[k] = if (old != null && s.type in UuidLists) unionUuids(old, s) else s
        }
    }

    private fun unionUuids(a: AdStructure, b: AdStructure): AdStructure {
        val size = when (a.type) {
            UUID16_PARTIAL, UUID16, SOLICIT16 -> 2
            UUID32_PARTIAL, UUID32 -> 4
            else -> 16
        }
        val chunks = LinkedHashSet<List<Byte>>()
        for (d in listOf(a.data, b.data)) for (i in 0 until d.size / size) chunks += d.copyOfRange(i * size, (i + 1) * size).toList()
        return AdStructure(a.type, chunks.flatten().toByteArray())
    }

    fun name(ads: List<AdStructure>): String? {
        val s = ads.firstOrNull { it.type == NAME } ?: ads.firstOrNull { it.type == NAME_SHORT } ?: return null
        return String(s.data, Charsets.UTF_8).trim { it.isWhitespace() || it == '\u0000' }.takeIf { it.isNotEmpty() && it.all { c -> !c.isISOControl() } }
    }

    fun flags(ads: List<AdStructure>) = ads.firstOrNull { it.type == FLAGS }?.data?.takeIf { it.isNotEmpty() }?.u8(0)

    fun txPower(ads: List<AdStructure>) = ads.firstOrNull { it.type == TX_POWER }?.data?.takeIf { it.isNotEmpty() }?.get(0)?.toInt()

    fun appearance(ads: List<AdStructure>) = ads.firstOrNull { it.type == APPEARANCE }?.data?.takeIf { it.size >= 2 }?.u16le(0)

    fun classOfDevice(ads: List<AdStructure>) = ads.firstOrNull { it.type == CLASS_OF_DEVICE }?.data?.takeIf { it.size >= 3 }?.let { it.u8(0) or (it.u8(1) shl 8) or (it.u8(2) shl 16) }

    /** Advertising interval announced by the device itself (AD 0x1A, units of 0.625 ms). */
    fun advertisedIntervalMs(ads: List<AdStructure>): Int? {
        val d = ads.firstOrNull { it.type == ADV_INTERVAL }?.data ?: return null
        val units = when (d.size) { 2 -> d.u16le(0); 3 -> d.u16le(0) or (d.u8(2) shl 16); else -> return null }
        return (units * 0.625).toInt().takeIf { it > 0 }
    }

    /** Company identifier → payload, for every manufacturer structure. */
    fun manufacturer(ads: List<AdStructure>): Map<Int, ByteArray> = buildMap {
        ads.filter { it.type == MANUFACTURER && it.data.size >= 2 }.forEach { put(it.data.u16le(0), it.data.copyOfRange(2, it.data.size)) }
    }

    /** 16-bit service UUID → payload of its service data. */
    fun serviceData(ads: List<AdStructure>): Map<Int, ByteArray> = buildMap {
        ads.filter { it.type == SERVICE_DATA16 && it.data.size >= 2 }.forEach { put(it.data.u16le(0), it.data.copyOfRange(2, it.data.size)) }
    }

    /** Every 16-bit service UUID mentioned: lists, service data and 32-bit UUIDs that fit in 16 bits. */
    fun uuids16(ads: List<AdStructure>): Set<Int> = buildSet {
        for (s in ads) when (s.type) {
            UUID16_PARTIAL, UUID16, SOLICIT16 -> for (i in 0 until s.data.size / 2) add(s.data.u16le(i * 2))
            UUID32_PARTIAL, UUID32 -> for (i in 0 until s.data.size / 4) if (s.data.u16le(i * 4 + 2) == 0) add(s.data.u16le(i * 4))
            SERVICE_DATA16 -> if (s.data.size >= 2) add(s.data.u16le(0))
        }
    }

    fun uuid128(d: ByteArray, at: Int): String {
        val b = (0 until 16).map { "%02X".format(d[at + 15 - it]) }
        return listOf(b.subList(0, 4), b.subList(4, 6), b.subList(6, 8), b.subList(8, 10), b.subList(10, 16)).joinToString("-") { it.joinToString("") }
    }

    fun uuids128(ads: List<AdStructure>): Set<String> = buildSet {
        for (s in ads) when (s.type) {
            UUID128_PARTIAL, UUID128, SOLICIT128 -> for (i in 0 until s.data.size / 16) add(uuid128(s.data, i * 16))
            SERVICE_DATA128 -> if (s.data.size >= 16) add(uuid128(s.data, 0))
        }
    }

    fun typeName(type: Int): String = when (type) {
        FLAGS -> "Flags"
        UUID16_PARTIAL -> S(R.string.n_16_bit_uuids_partial)
        UUID16 -> S(R.string.n_16_bit_uuids)
        UUID32_PARTIAL, UUID32 -> S(R.string.n_32_bit_uuids)
        UUID128_PARTIAL, UUID128 -> S(R.string.n_128_bit_uuids)
        NAME_SHORT -> S(R.string.name_short)
        NAME -> "Nom"
        TX_POWER -> S(R.string.tx_power)
        CLASS_OF_DEVICE -> S(R.string.device_class)
        0x12 -> S(R.string.connection_interval)
        SOLICIT16, SOLICIT128, 0x1F -> S(R.string.service_solicitation)
        SERVICE_DATA16, SERVICE_DATA32, SERVICE_DATA128 -> S(R.string.service_data)
        0x17, 0x18 -> S(R.string.target_address)
        APPEARANCE -> S(R.string.appearance)
        ADV_INTERVAL -> S(R.string.advertising_interval)
        0x1B -> S(R.string.le_address)
        0x1C -> S(R.string.le_role)
        0x24 -> "URI"
        MESH_PB_ADV -> "Mesh PB-ADV"
        MESH_MESSAGE -> S(R.string.mesh_message)
        MESH_BEACON -> "Mesh (beacon)"
        0x2C -> "BIGInfo"
        MANUFACTURER -> S(R.string.manufacturer_data)
        else -> S(R.string.unknown_type)
    }

    /** One readable line for the raw-frames list. */
    fun describe(s: AdStructure): String {
        val d = s.data
        return when (s.type) {
            FLAGS -> if (d.isEmpty()) "—" else listOfNotNull(
                if (d.u8(0) and 0x01 != 0) S(R.string.le_limited) else null, if (d.u8(0) and 0x02 != 0) S(R.string.le_general) else null,
                if (d.u8(0) and 0x04 != 0) S(R.string.no_br_edr) else null, if (d.u8(0) and 0x08 != 0) S(R.string.dual_mode_controller) else null,
            ).joinToString(" · ").ifEmpty { "0x%02X".format(d.u8(0)) }
            NAME, NAME_SHORT -> String(d, Charsets.UTF_8)
            TX_POWER -> if (d.isEmpty()) "—" else "${d[0]} dBm"
            APPEARANCE -> if (d.size >= 2) "0x%04X".format(d.u16le(0)) else s.hex
            ADV_INTERVAL -> advertisedIntervalMs(listOf(s))?.let { "$it ms" } ?: s.hex
            UUID16_PARTIAL, UUID16, SOLICIT16 -> (0 until d.size / 2).joinToString(", ") { "0x%04X".format(d.u16le(it * 2)) }
            UUID128_PARTIAL, UUID128, SOLICIT128 -> (0 until d.size / 16).joinToString("\n") { uuid128(d, it * 16) }
            SERVICE_DATA16 -> if (d.size >= 2) "0x%04X · %s".format(d.u16le(0), d.hex(2).ifEmpty { S(R.string.empty) }.take(72)) else s.hex
            MANUFACTURER -> if (d.size >= 2) "${Companies.name(d.u16le(0)) ?: "ID"} (0x%04X) · %s".format(d.u16le(0), d.hex(2).ifEmpty { S(R.string.empty) }.take(72)) else s.hex
            else -> d.hex().take(72).ifEmpty { S(R.string.empty) }
        }
    }
}

/** Bluetooth SIG company identifiers that show up in scans; chipset vendors are flagged. */
object Companies {
    private val chipset = setOf(0x0002, 0x0009, 0x000A, 0x000D, 0x000F, 0x0013, 0x001D, 0x0025, 0x0030, 0x0046, 0x0059, 0x005D, 0x00D2, 0x0131, 0x02E5, 0x02FF)

    private val names = mapOf(
        0x0000 to "Ericsson", 0x0001 to "Nokia", 0x0002 to "Intel", 0x0003 to "IBM", 0x0004 to "Toshiba", 0x0006 to "Microsoft",
        0x0008 to "Motorola", 0x0009 to "Infineon", 0x000A to "Qualcomm (CSR)", 0x000D to "Texas Instruments", 0x000F to "Broadcom",
        0x0013 to "Atmel", 0x001D to "Qualcomm", 0x0022 to "NEC", 0x0025 to "NXP", 0x0030 to "STMicroelectronics", 0x0046 to "MediaTek",
        0x004C to "Apple", 0x0055 to "Plantronics (Poly)", 0x0057 to "Harman", 0x0059 to "Nordic Semiconductor", 0x005D to "Realtek",
        0x0067 to "GN Audio (Jabra)", 0x006B to "Polar", 0x0075 to "Samsung", 0x0078 to "Nike", 0x0087 to "Garmin", 0x009E to "Bose",
        0x009F to "Suunto", 0x00C4 to "LG Electronics", 0x00D2 to "Dialog Semiconductor", 0x00E0 to "Google", 0x0131 to "Cypress",
        0x0157 to "Huami (Amazfit, Mi Band)", 0x0171 to "Amazon", 0x01AB to "Meta (Facebook)", 0x01DA to "Logitech", 0x012D to "Sony",
        0x027D to "Huawei", 0x02E5 to "Espressif", 0x02FF to "Silicon Labs", 0x038F to "Xiaomi", 0x0499 to "Ruuvi",
        0x058E to "Meta", 0x05A7 to "Sonos", 0x0822 to "Adafruit",
    )

    fun name(id: Int): String? = names[id]?.let { if (id in chipset) S(R.string.chip, it) else it }

    fun isChipset(id: Int) = id in chipset

    /** Plain vendor name, without the chipset mention. */
    fun plain(id: Int): String? = names[id]
}
