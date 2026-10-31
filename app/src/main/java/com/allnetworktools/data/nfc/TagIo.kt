package com.allnetworktools.data.nfc

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.NdefFormatable
import android.nfc.tech.NfcA
import android.nfc.tech.NfcB
import android.nfc.tech.NfcF
import android.nfc.tech.NfcV
import android.nfc.tech.TagTechnology
import java.io.IOException
import android.nfc.tech.Ndef as NdefTech

/** A smart card application found on an ISO 14443-4 card. */
enum class CardApp { Payment, Transport, Passport }

data class NdefInfo(
    /** "NFC Forum Type 2"… */
    val type: String,
    val maxSize: Int,
    val used: Int,
    val writable: Boolean,
    val canLock: Boolean,
    val contents: List<NdefContent>,
)

/** Everything read from one tag held to the phone. */
data class TagScan(
    val atMs: Long,
    val uid: ByteArray,
    /** "NfcA", "IsoDep", "Ndef"… */
    val techs: List<String>,
    val chip: ChipInfo,
    val manufacturer: String?,
    val randomUid: Boolean,
    val atqa: String? = null,
    val sak: String? = null,
    /** ISO 14443-4: historical bytes (A) or higher-layer response (B). */
    val historical: String? = null,
    val felicaSystem: String? = null,
    val app: CardApp? = null,
    val ndef: NdefInfo? = null,
    /** Blank but can be made NDEF. */
    val formatable: Boolean = false,
    val message: NdefMessage? = null,
) {
    val uidHex get() = NfcChips.hex(uid)
}

enum class WriteResult { Ok, TooBig, ReadOnly, NotNdef, Lost }

/** One line of a memory dump: address, bytes (null when unreadable), and what that part of the memory is. */
data class DumpRow(val address: Int, val bytes: ByteArray?, val note: DumpNote? = null, val sector: Int? = null)

enum class DumpNote { Uid, Lock, Cc, Config, Trailer, Manufacturer, Protected }

data class Dump(val chip: ChipInfo, val unit: Int, val rows: List<DumpRow>, val unsupported: Boolean = false, val keysFound: Int = 0, val sectors: Int = 0)

/** Talks to a tag: the calls block, so they run on the NFC thread that delivered it. */
object TagIo {
    private fun short(t: String) = t.substringAfterLast('.')

    private inline fun <T : TagTechnology, R> T.use(block: (T) -> R): R? = try {
        connect()
        block(this)
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null // the tag left: Android refuses calls on a stale tag
    } catch (_: IllegalStateException) {
        null
    } finally {
        runCatching { close() }
    }

    fun scan(tag: Tag, nowMs: Long): TagScan {
        val techs = tag.techList.map(::short)
        val uid = tag.id ?: ByteArray(0)
        var chip = ChipInfo("Tag", ChipFamily.Unknown)
        var atqa: String? = null
        var sak: String? = null
        var historical: String? = null
        var felica: String? = null
        var app: CardApp? = null
        val nfcA = NfcA.get(tag)
        if (nfcA != null) {
            val a = nfcA.atqa
            val atqaInt = if (a.size >= 2) ((a[1].toInt() and 0xFF) shl 8) or (a[0].toInt() and 0xFF) else 0
            atqa = "%04X".format(atqaInt)
            sak = "%02X".format(nfcA.sak.toInt() and 0xFF)
            chip = NfcChips.fromSak(nfcA.sak.toInt() and 0xFF, atqaInt)
            if (chip.family == ChipFamily.Ultralight) {
                // GET_VERSION names NTAG and Ultralight EV1; the oldest Ultralight and Ultralight C do not answer it.
                val v = nfcA.use { runCatching { it.transceive(byteArrayOf(0x60)) }.getOrNull() }
                chip = v?.let(NfcChips::fromVersion)
                    ?: NfcChips.ultralight(MifareUltralight.get(tag)?.type == MifareUltralight.TYPE_ULTRALIGHT_C)
            }
        }
        val iso = IsoDep.get(tag)
        if (iso != null) {
            historical = (iso.historicalBytes ?: iso.hiLayerResponse)?.takeIf { it.isNotEmpty() }?.let { NfcChips.hex(it, " ") }
            iso.use { d ->
                d.timeout = 1500
                // DESFire answers its native GetVersion, wrapped in ISO 7816.
                val v = runCatching { d.transceive(byteArrayOf(0x90.toByte(), 0x60, 0x00, 0x00, 0x00)) }.getOrNull()
                if (v != null && v.size >= 9 && v[v.size - 2] == 0x91.toByte() && v[v.size - 1] == 0xAF.toByte()) {
                    NfcChips.desfire(v.copyOfRange(0, v.size - 2))?.let { chip = it }
                    runCatching { d.transceive(byteArrayOf(0x90.toByte(), 0xAF.toByte(), 0x00, 0x00, 0x00)) }
                    runCatching { d.transceive(byteArrayOf(0x90.toByte(), 0xAF.toByte(), 0x00, 0x00, 0x00)) }
                }
                app = cardApp(d)
            }
            if (chip.family == ChipFamily.Unknown || nfcA == null) chip = chip.takeIf { it.family == ChipFamily.Desfire }
                ?: ChipInfo(if (NfcB.get(tag) != null) "ISO 14443-4 (B)" else "ISO 14443-4", ChipFamily.Iso14443_4)
        } else if (nfcA == null && NfcB.get(tag) != null) {
            chip = ChipInfo("ISO 14443-B", ChipFamily.Unknown)
        }
        NfcF.get(tag)?.let { f ->
            felica = f.systemCode?.let { NfcChips.hex(it, "") }
            chip = ChipInfo("FeliCa", ChipFamily.Felica)
        }
        NfcV.get(tag)?.let { v ->
            // Get System Information, addressed to this tag.
            val info = v.use { runCatching { it.transceive(byteArrayOf(0x22, 0x2B) + uid) }.getOrNull() }
            chip = NfcChips.iso15693(uid, info)
        }
        var message: NdefMessage? = null
        val ndef = NdefTech.get(tag)?.let { n ->
            n.use { t ->
                val m = runCatching { t.ndefMessage }.getOrNull()
                message = m
                NdefInfo(ndefType(t.type), t.maxSize, m?.byteArrayLength ?: 0, t.isWritable, runCatching { t.canMakeReadOnly() }.getOrDefault(false), Ndef.parse(m))
            } ?: NdefInfo(ndefType(n.type), n.maxSize, 0, n.isWritable, false, emptyList())
        }
        val maker = NfcChips.manufacturer(uid, iso15693 = NfcV.get(tag) != null)
        return TagScan(
            nowMs, uid, techs, chip, maker, NfcChips.isRandomUid(uid), atqa, sak, historical, felica, app, ndef,
            formatable = ndef == null && NdefFormatable.get(tag) != null, message = message,
        )
    }

    private fun ndefType(t: String?) = when (t) {
        NdefTech.NFC_FORUM_TYPE_1 -> "NFC Forum Type 1"
        NdefTech.NFC_FORUM_TYPE_2 -> "NFC Forum Type 2"
        NdefTech.NFC_FORUM_TYPE_3 -> "NFC Forum Type 3"
        NdefTech.NFC_FORUM_TYPE_4 -> "NFC Forum Type 4"
        NdefTech.MIFARE_CLASSIC -> "MIFARE Classic"
        else -> t?.substringAfterLast('.') ?: "NDEF"
    }

    private fun selectOk(d: IsoDep, aid: ByteArray): Boolean {
        val r = runCatching { d.transceive(byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, aid.size.toByte()) + aid + byteArrayOf(0x00)) }.getOrNull() ?: return false
        return r.size >= 2 && r[r.size - 2] == 0x90.toByte() && r[r.size - 1] == 0x00.toByte()
    }

    /** Which well-known application the card holds: only selected, nothing is read here. */
    private fun cardApp(d: IsoDep): CardApp? = when {
        selectOk(d, "2PAY.SYS.DDF01".toByteArray()) -> CardApp.Payment
        selectOk(d, "1TIC.ICA".toByteArray()) -> CardApp.Transport
        selectOk(d, NfcChips.parseHex("A0000002471001")) -> CardApp.Passport
        else -> null
    }

    /** Writes [m]: on an NDEF tag, or after formatting a blank one. */
    fun write(tag: Tag, m: NdefMessage): WriteResult {
        NdefTech.get(tag)?.let { n ->
            return n.use { t ->
                when {
                    !t.isWritable -> WriteResult.ReadOnly
                    m.byteArrayLength > t.maxSize -> WriteResult.TooBig
                    else -> { t.writeNdefMessage(m); WriteResult.Ok }
                }
            } ?: WriteResult.Lost
        }
        NdefFormatable.get(tag)?.let { f -> return f.use { it.format(m); WriteResult.Ok } ?: WriteResult.Lost }
        return WriteResult.NotNdef
    }

    /** An empty record: the tag stays NDEF, its content is gone. */
    fun erase(tag: Tag): WriteResult = write(tag, NdefMessage(NdefRecord(NdefRecord.TNF_EMPTY, null, null, null)))

    /** For good: the tag can never be written again. */
    fun lock(tag: Tag): WriteResult {
        val n = NdefTech.get(tag) ?: return WriteResult.NotNdef
        return n.use { t -> if (!t.canMakeReadOnly()) WriteResult.NotNdef else if (t.makeReadOnly()) WriteResult.Ok else WriteResult.ReadOnly } ?: WriteResult.Lost
    }

    /** MIFARE Classic transport and NFC Forum keys, published by the makers; no other key is tried. */
    val publicKeys: List<ByteArray> = listOf(
        "FFFFFFFFFFFF", "A0A1A2A3A4A5", "D3F7D3F7D3F7", "000000000000", "B0B1B2B3B4B5", "AABBCCDDEEFF",
    ).map(NfcChips::parseHex)

    /** The whole memory the tag lets us read. */
    fun dump(tag: Tag, scan: TagScan): Dump {
        MifareUltralight.get(tag)?.let { u -> return ultralight(u, scan.chip) }
        MifareClassic.get(tag)?.let { c -> return classic(c, scan.chip) }
        if (scan.chip.family == ChipFamily.Classic || scan.chip.family == ChipFamily.Plus) {
            // The phone's NFC controller is not from NXP: Android cannot authenticate MIFARE Classic sectors.
            return Dump(scan.chip, 16, emptyList(), unsupported = true)
        }
        NfcV.get(tag)?.let { v -> return iso15693(v, scan) }
        return Dump(scan.chip, 4, emptyList(), unsupported = true)
    }

    private fun ultralight(u: MifareUltralight, chip: ChipInfo): Dump {
        val pages = chip.units ?: if (u.type == MifareUltralight.TYPE_ULTRALIGHT_C) 48 else 16
        val rows = ArrayList<DumpRow>()
        u.use { t ->
            var p = 0
            while (p < pages) {
                val four = runCatching { t.readPages(p) }.getOrNull() // 4 pages, wrapping at the end
                for (k in 0 until 4) {
                    if (p + k >= pages) break
                    rows += DumpRow(p + k, four?.copyOfRange(4 * k, 4 * k + 4), ultralightNote(p + k, pages, chip.family == ChipFamily.Ntag))
                }
                p += 4
            }
        }
        return Dump(chip, 4, rows)
    }

    fun ultralightNote(page: Int, pages: Int, ntag: Boolean): DumpNote? = when {
        page <= 1 -> DumpNote.Uid
        page == 2 -> DumpNote.Lock
        page == 3 -> DumpNote.Cc
        ntag && page == pages - 5 -> DumpNote.Lock
        ntag && page >= pages - 4 -> DumpNote.Config
        else -> null
    }

    private fun classic(c: MifareClassic, chip: ChipInfo): Dump {
        val rows = ArrayList<DumpRow>()
        var found = 0
        c.use { t ->
            for (s in 0 until t.sectorCount) {
                val first = t.sectorToBlock(s)
                val n = t.getBlockCountInSector(s)
                val keyA = publicKeys.firstOrNull { k -> runCatching { t.authenticateSectorWithKeyA(s, k) }.getOrDefault(false) }
                val keyB = if (keyA == null) publicKeys.firstOrNull { k -> runCatching { t.authenticateSectorWithKeyB(s, k) }.getOrDefault(false) } else null
                val open = keyA != null || keyB != null
                if (open) found++
                for (b in 0 until n) {
                    val block = first + b
                    var data = if (open) runCatching { t.readBlock(block) }.getOrNull() else null
                    // The trailer reads back with key A hidden (zeros): put in the key that opened it.
                    if (data != null && b == n - 1 && keyA != null) data = keyA + data.copyOfRange(6, 16)
                    val note = when {
                        !open -> DumpNote.Protected
                        block == 0 -> DumpNote.Manufacturer
                        b == n - 1 -> DumpNote.Trailer
                        else -> null
                    }
                    rows += DumpRow(block, data, note, s)
                }
            }
        }
        return Dump(chip, 16, rows, keysFound = found, sectors = c.sectorCount)
    }

    private fun iso15693(v: NfcV, scan: TagScan): Dump {
        val blocks = scan.chip.units ?: 28
        val size = scan.chip.unitBytes ?: 4
        val rows = ArrayList<DumpRow>()
        v.use { t ->
            for (b in 0 until blocks) {
                // Read Single Block, addressed: flags, command, UID, block number; the answer starts with a flags byte.
                val r = runCatching { t.transceive(byteArrayOf(0x22, 0x20) + scan.uid + byteArrayOf(b.toByte())) }.getOrNull()
                rows += DumpRow(b, r?.takeIf { it.isNotEmpty() && it[0].toInt() and 0x01 == 0 }?.copyOfRange(1, minOf(r.size, 1 + size)))
            }
        }
        return Dump(scan.chip, size, rows)
    }

    /**
     * One exchange with the tag for the antenna test: a cheap read for type 2 tags, a SELECT for smart cards, Get System
     * Information for ISO 15693; otherwise a fresh connection. True when the tag answered.
     */
    fun ping(tag: Tag, tech: TagTechnology?): Boolean = try {
        val t = tech ?: return false
        if (!t.isConnected) t.connect()
        when (t) {
            is MifareUltralight -> { t.readPages(0); true }
            is IsoDep -> { t.transceive(byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, 0x00)); true }
            is NfcV -> { t.transceive(byteArrayOf(0x02, 0x2B)); true }
            else -> { t.close(); t.connect(); true }
        }
    } catch (_: Exception) {
        false
    }

    /** The technology the antenna test keeps open. */
    fun pingTech(tag: Tag): TagTechnology? =
        MifareUltralight.get(tag) ?: IsoDep.get(tag) ?: NfcV.get(tag) ?: NfcA.get(tag) ?: NfcB.get(tag) ?: NfcF.get(tag)
}
