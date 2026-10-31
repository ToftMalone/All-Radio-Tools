package com.allnetworktools.data.nfc

/** Families of chips, for the icon, the colour and what can be done with them. */
enum class ChipFamily { Ntag, Ultralight, Classic, Plus, Desfire, Iso14443_4, Felica, Iso15693, Unknown }

/**
 * What a chip is, as far as its answers tell: [name] is a model ("NTAG215", "MIFARE Classic 1K") or a family when the
 * model is not known. Sizes are in bytes, null when unknown.
 */
data class ChipInfo(
    val name: String,
    val family: ChipFamily,
    val totalBytes: Int? = null,
    /** Free for NDEF or the user's data. */
    val userBytes: Int? = null,
    /** Pages (4 bytes) for NTAG / Ultralight, blocks (16 bytes) for MIFARE Classic, blocks for ISO 15693. */
    val units: Int? = null,
    val unitBytes: Int? = null,
)

object NfcChips {
    /** IC manufacturer codes of ISO/IEC 7816-6, the first byte of a 7- or 8-byte unique UID. */
    private val manufacturers = mapOf(
        0x01 to "Motorola", 0x02 to "STMicroelectronics", 0x03 to "Hitachi", 0x04 to "NXP Semiconductors", 0x05 to "Infineon",
        0x06 to "Cylink", 0x07 to "Texas Instruments", 0x08 to "Fujitsu", 0x09 to "Matsushita", 0x0A to "NEC", 0x0B to "Oki",
        0x0C to "Toshiba", 0x0D to "Mitsubishi", 0x0E to "Samsung", 0x0F to "Hynix", 0x10 to "LG", 0x11 to "Emosyn",
        0x12 to "INSIDE Secure", 0x13 to "ORGA", 0x14 to "Sharp", 0x15 to "Atmel", 0x16 to "EM Microelectronic", 0x17 to "KSW Microtec",
        0x19 to "Xicor", 0x1A to "Sony", 0x1B to "Malaysia Microelectronic Solutions", 0x1C to "Emosyn", 0x1D to "Shanghai Fudan",
        0x1E to "Magellan", 0x1F to "Melexis", 0x20 to "Renesas", 0x21 to "TAGSYS", 0x22 to "Transcore", 0x23 to "Shanghai Belling",
        0x24 to "Masktech", 0x25 to "Innovision", 0x26 to "Hitachi ULSI", 0x27 to "Cypak", 0x28 to "Ricoh", 0x29 to "ASK",
        0x2A to "Unicore", 0x2B to "Maxim", 0x2C to "Impinj", 0x2E to "Broadcom", 0x2F to "MStar", 0x31 to "RFIDsec",
        0x34 to "Mikron", 0x35 to "Fraunhofer", 0x36 to "IDS Microchip", 0x39 to "Silicon Craft", 0x3D to "HID Global",
        0x3F to "ams", 0x40 to "Gemalto", 0x45 to "Invengo", 0x4C to "LEGIC", 0x51 to "Giesecke+Devrient", 0x54 to "Intel",
        0x57 to "Qualcomm", 0x58 to "Realtek", 0x5F to "Nordic Semiconductor",
    )

    /**
     * The chip's maker, from the UID: ISO 14443-A double and triple size UIDs (7 or 10 bytes) and ISO 15693 UIDs
     * (8 bytes, E0 then the maker, given least significant byte first by Android) carry it. A 4-byte UID does not:
     * it is a serial or, starting with 08, drawn at random each time.
     */
    fun manufacturer(uid: ByteArray, iso15693: Boolean = false): String? = when {
        iso15693 && uid.size == 8 && uid[7].toInt() and 0xFF == 0xE0 -> manufacturers[uid[6].toInt() and 0xFF]
        !iso15693 && (uid.size == 7 || uid.size == 10) -> manufacturers[uid[0].toInt() and 0xFF]
        else -> null
    }

    /** A 4-byte UID starting with 08 changes at every read: the card hides its identity. */
    fun isRandomUid(uid: ByteArray) = uid.size == 4 && uid[0].toInt() and 0xFF == 0x08

    /**
     * ISO 14443-A cards from SAK and ATQA alone (NXP AN10833). Type 2 tags (SAK 00) and ISO 14443-4 cards (SAK 20) need
     * their GET_VERSION answer to be named, see [fromVersion] and [desfire].
     */
    fun fromSak(sak: Int, atqa: Int): ChipInfo = when (sak) {
        0x08 -> if (atqa == 0x0044) ChipInfo("MIFARE Plus 2K (SL1)", ChipFamily.Plus, 2048, 1440, 128, 16)
        else ChipInfo("MIFARE Classic 1K", ChipFamily.Classic, 1024, 752, 64, 16)
        0x18 -> if (atqa == 0x0042) ChipInfo("MIFARE Plus 4K (SL1)", ChipFamily.Plus, 4096, 3440, 256, 16)
        else ChipInfo("MIFARE Classic 4K", ChipFamily.Classic, 4096, 3440, 256, 16)
        0x09 -> ChipInfo("MIFARE Mini", ChipFamily.Classic, 320, 224, 20, 16)
        0x10 -> ChipInfo("MIFARE Plus 2K (SL2)", ChipFamily.Plus, 2048)
        0x11 -> ChipInfo("MIFARE Plus 4K (SL2)", ChipFamily.Plus, 4096)
        0x00 -> ChipInfo("MIFARE Ultralight / NTAG", ChipFamily.Ultralight)
        0x28 -> ChipInfo("SmartMX + MIFARE Classic 1K", ChipFamily.Classic, 1024, 752, 64, 16)
        0x38 -> ChipInfo("SmartMX + MIFARE Classic 4K", ChipFamily.Classic, 4096, 3440, 256, 16)
        else -> if (sak and 0x20 != 0) ChipInfo("ISO 14443-4", ChipFamily.Iso14443_4) else ChipInfo("ISO 14443-A", ChipFamily.Unknown)
    }

    /**
     * Type 2 tags from their 8-byte GET_VERSION answer (00 vendor type subtype major minor size protocol): NTAG 21x
     * and MIFARE Ultralight EV1. The size byte gives the user memory: 2^(size>>1) bytes, more when its lowest bit is set.
     */
    fun fromVersion(v: ByteArray): ChipInfo? {
        if (v.size < 8) return null
        val type = v[2].toInt() and 0xFF
        val sub = v[3].toInt() and 0xFF
        val size = v[6].toInt() and 0xFF
        return when (type) {
            0x04 -> when (size) {
                0x0B -> page("NTAG210", ChipFamily.Ntag, 20, 48)
                0x0E -> page("NTAG212", ChipFamily.Ntag, 41, 128)
                0x0F -> page("NTAG213", ChipFamily.Ntag, 45, 144)
                0x11 -> page("NTAG215", ChipFamily.Ntag, 135, 504)
                0x13 -> if (sub == 0x05) page("NTAG I2C 1K", ChipFamily.Ntag, 231, 888) else page("NTAG216", ChipFamily.Ntag, 231, 888)
                0x15 -> page("NTAG I2C 2K", ChipFamily.Ntag, 485, 1904)
                else -> ChipInfo("NTAG", ChipFamily.Ntag)
            }
            0x03 -> when (size) {
                0x0B -> page("MIFARE Ultralight EV1 (MF0UL11)", ChipFamily.Ultralight, 20, 48)
                0x0E -> page("MIFARE Ultralight EV1 (MF0UL21)", ChipFamily.Ultralight, 41, 128)
                else -> ChipInfo("MIFARE Ultralight", ChipFamily.Ultralight)
            }
            else -> null
        }
    }

    private fun page(name: String, family: ChipFamily, pages: Int, user: Int) = ChipInfo(name, family, pages * 4, user, pages, 4)

    /** Ultralight without GET_VERSION: the original MF0ICU1 (16 pages) or Ultralight C (48 pages). */
    fun ultralight(c: Boolean) = if (c) page("MIFARE Ultralight C", ChipFamily.Ultralight, 48, 144) else page("MIFARE Ultralight", ChipFamily.Ultralight, 16, 48)

    /**
     * DESFire from the first frame of its native GetVersion (vendor, type 01, subtype, major, minor, size, protocol):
     * the major version tells EV1 (01), EV2 (12) and EV3 (30 and up), the size byte the memory.
     */
    fun desfire(hw: ByteArray): ChipInfo? {
        if (hw.size < 7 || hw[1].toInt() and 0xFF != 0x01) return null
        val major = hw[3].toInt() and 0xFF
        val bytes = sizeOf(hw[5].toInt() and 0xFF)
        val gen = when {
            major == 0x00 -> ""
            major == 0x01 -> " EV1"
            major in 0x10..0x1F -> " EV2"
            major >= 0x30 -> " EV3"
            else -> ""
        }
        val k = bytes?.let { " ${it / 1024}K" }.orEmpty()
        return ChipInfo("MIFARE DESFire$gen$k", ChipFamily.Desfire, bytes)
    }

    private fun sizeOf(b: Int): Int? = if (b < 2 || b > 0x20) null else (1 shl (b shr 1)).let { if (b and 1 == 1) it + it / 2 else it }

    /**
     * ISO 15693 from Get System Information (flags, info flags, UID, then DSFID, AFI, memory size, IC reference when
     * their flags are set): block count and size, and the model for the common NXP ICODE chips.
     */
    fun iso15693(uid: ByteArray, info: ByteArray?): ChipInfo {
        val maker = manufacturer(uid, iso15693 = true)
        var blocks: Int? = null
        var blockSize: Int? = null
        if (info != null && info.size >= 10 && info[0].toInt() and 0x01 == 0) {
            val flags = info[1].toInt()
            var p = 10
            if (flags and 0x01 != 0) p++ // DSFID
            if (flags and 0x02 != 0) p++ // AFI
            if (flags and 0x04 != 0 && p + 1 < info.size) {
                blocks = (info[p].toInt() and 0xFF) + 1
                blockSize = (info[p + 1].toInt() and 0x1F) + 1
            }
        }
        // NXP ICODE: bits 36..35 of the UID tell the model (SLI, SLIX, SLIX2).
        val name = if (maker == "NXP Semiconductors") {
            when ((uid[4].toInt() shr 3) and 0x03) {
                0 -> "ICODE SLI"
                2 -> "ICODE SLIX"
                1 -> "ICODE SLIX2"
                else -> "ICODE"
            }
        } else "ISO 15693"
        val total = if (blocks != null && blockSize != null) blocks * blockSize else null
        return ChipInfo(name, ChipFamily.Iso15693, total, total, blocks, blockSize)
    }

    fun hex(b: ByteArray, sep: String = ":"): String = b.joinToString(sep) { "%02X".format(it.toInt() and 0xFF) }

    fun parseHex(s: String): ByteArray {
        val h = s.filter { it.isLetterOrDigit() }
        return ByteArray(h.length / 2) { h.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }
}
