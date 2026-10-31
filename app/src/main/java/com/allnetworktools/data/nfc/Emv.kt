package com.allnetworktools.data.nfc

import java.io.IOException

/** Sends one command APDU and returns the response with its status word (SW1 SW2 at the end). */
fun interface Apdu {
    @Throws(IOException::class)
    fun send(cmd: ByteArray): ByteArray
}

/** One BER-TLV element; [children] when it is constructed. */
class Tlv(val tag: Int, val value: ByteArray, val children: List<Tlv>) {
    fun find(t: Int): Tlv? = if (tag == t) this else children.firstNotNullOfOrNull { it.find(t) }
}

data class EmvTx(
    /** "2026-10-31". */
    val date: String?,
    val time: String?,
    /** In minor units (cents). */
    val amount: Long?,
    val currency: String?,
    val country: String?,
    val merchant: String?,
)

/**
 * What a contactless payment card tells any terminal before a PIN: network, application, the last four digits, validity
 * and, when the card keeps one, its log of recent payments. The full number is never kept, only its last four digits.
 */
data class EmvCard(
    val scheme: String,
    val label: String?,
    val aid: String,
    val last4: String?,
    /** "10/28". */
    val expiry: String?,
    val effective: String?,
    val country: String?,
    val currency: String?,
    val holder: String?,
    val applications: List<String>,
    val transactions: List<EmvTx>,
    val logSupported: Boolean,
)

object Emv {
    private val PPSE = "2PAY.SYS.DDF01".toByteArray()

    /** Applications tried when the card has no contactless directory. */
    private val KNOWN_AIDS = listOf(
        "A0000000041010", "A0000000031010", "A0000000421010", "A0000000032010", "A0000000043060", "A000000025010801", "A0000000651010", "A0000001523010", "A000000333010101",
    )

    fun scheme(aid: String): String = when {
        aid.startsWith("A000000003") -> "Visa"
        aid.startsWith("A000000004") -> "Mastercard"
        aid.startsWith("A000000042") -> "CB"
        aid.startsWith("A000000025") -> "American Express"
        aid.startsWith("A000000065") -> "JCB"
        aid.startsWith("A000000152") -> "Discover"
        aid.startsWith("A000000333") -> "UnionPay"
        aid.startsWith("A000000277") -> "Interac"
        else -> "EMV"
    }

    // ---- TLV ----

    fun parse(b: ByteArray, from: Int = 0, to: Int = b.size): List<Tlv> {
        val out = ArrayList<Tlv>()
        var i = from
        while (i < to) {
            val first = b[i].toInt() and 0xFF
            if (first == 0x00 || first == 0xFF) { i++; continue } // padding
            var tag = first
            i++
            if (first and 0x1F == 0x1F) {
                while (i < to) {
                    val nb = b[i].toInt() and 0xFF
                    tag = (tag shl 8) or nb
                    i++
                    if (nb and 0x80 == 0) break
                }
            }
            if (i >= to) break
            var len = b[i].toInt() and 0xFF
            i++
            if (len and 0x80 != 0) {
                val n = len and 0x7F
                if (n == 0 || n > 3 || i + n > to) break
                len = 0
                repeat(n) { len = (len shl 8) or (b[i].toInt() and 0xFF); i++ }
            }
            if (i + len > to) break
            val value = b.copyOfRange(i, i + len)
            val constructed = (tag.let { var t = it; while (t > 0xFF) t = t shr 8; t } and 0x20) != 0
            out += Tlv(tag, value, if (constructed) parse(value) else emptyList())
            i += len
        }
        return out
    }

    private fun List<Tlv>.find(t: Int): Tlv? = firstNotNullOfOrNull { it.find(t) }
    private fun List<Tlv>.all(t: Int): List<Tlv> = flatMap { collect(it, t) }
    private fun collect(x: Tlv, t: Int): List<Tlv> = (if (x.tag == t) listOf(x) else emptyList()) + x.children.flatMap { collect(it, t) }

    /** Data object lists (PDOL, log format): tag and length pairs, no values. */
    fun dol(b: ByteArray): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i < b.size) {
            var tag = b[i].toInt() and 0xFF
            i++
            if (tag and 0x1F == 0x1F) {
                while (i < b.size) { val nb = b[i].toInt() and 0xFF; tag = (tag shl 8) or nb; i++; if (nb and 0x80 == 0) break }
            }
            if (i >= b.size) break
            out += tag to (b[i].toInt() and 0xFF)
            i++
        }
        return out
    }

    // ---- commands ----

    private fun ok(r: ByteArray) = r.size >= 2 && r[r.size - 2] == 0x90.toByte() && r[r.size - 1] == 0x00.toByte()
    private fun body(r: ByteArray) = r.copyOfRange(0, r.size - 2)

    private fun select(aid: ByteArray) = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, aid.size.toByte()) + aid + byteArrayOf(0x00)
    private fun readRecord(sfi: Int, rec: Int) = byteArrayOf(0x00, 0xB2.toByte(), rec.toByte(), ((sfi shl 3) or 4).toByte(), 0x00)
    private fun getData(tag: Int) = byteArrayOf(0x80.toByte(), 0xCA.toByte(), (tag shr 8).toByte(), tag.toByte(), 0x00)

    /** Values a contactless terminal would give for the card's PDOL: a zero-amount purchase in France, in euros. */
    fun pdolData(pdol: List<Pair<Int, Int>>, today: String, random: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for ((tag, len) in pdol) {
            val v: ByteArray = when (tag) {
                0x9F66 -> byteArrayOf(0x36, 0x00, 0x40, 0x00) // TTQ: contactless EMV, online capable
                0x9F1A, 0x5F2A -> if (tag == 0x9F1A) byteArrayOf(0x02, 0x50) else byteArrayOf(0x09, 0x78)
                0x9A -> NfcChips.parseHex(today)
                0x9C -> byteArrayOf(0x00)
                0x9F37 -> random
                0x9F35 -> byteArrayOf(0x22)
                0x9F33 -> byteArrayOf(0xE0.toByte(), 0xF8.toByte(), 0xC8.toByte())
                0x9F40 -> byteArrayOf(0x60, 0x00, 0xF0.toByte(), 0xA0.toByte(), 0x01)
                else -> ByteArray(len)
            }
            val fit = ByteArray(len)
            // Numbers are right-aligned, everything else left-aligned.
            if (tag in setOf(0x9F02, 0x9F03, 0x9F1A, 0x5F2A)) v.copyInto(fit, maxOf(0, len - v.size), maxOf(0, v.size - len)) else v.copyInto(fit, 0, 0, minOf(len, v.size))
            out.write(fit)
        }
        return out.toByteArray()
    }

    /**
     * Reads the card: the contactless directory (or known applications), the first application's file, Get Processing
     * Options, the records it lists, and the transaction log when there is one. Null when no payment application answers.
     */
    fun read(io: Apdu, today: String, random: ByteArray = ByteArray(4) { (Math.random() * 256).toInt().toByte() }): EmvCard? {
        val apps = ArrayList<Pair<String, String?>>() // AID, label
        val ppse = io.send(select(PPSE))
        if (ok(ppse)) {
            val t = parse(body(ppse))
            t.all(0x61).forEach { e ->
                val aid = e.find(0x4F)?.value?.let { NfcChips.hex(it, "") } ?: return@forEach
                apps += aid to e.find(0x50)?.value?.let { String(it, Charsets.ISO_8859_1).trim() }
            }
        }
        var aid: String? = null
        var fci: List<Tlv> = emptyList()
        for (a in apps.map { it.first } + KNOWN_AIDS) {
            val r = io.send(select(NfcChips.parseHex(a)))
            if (ok(r)) { aid = a; fci = parse(body(r)); break }
        }
        aid ?: return null
        val label = fci.find(0x9F12)?.value?.let { String(it, Charsets.ISO_8859_1).trim() } ?: fci.find(0x50)?.value?.let { String(it, Charsets.ISO_8859_1).trim() }
            ?: apps.firstOrNull { it.first == aid }?.second
        val found = ArrayList<Tlv>(fci)

        // Get Processing Options, then the records of its file locator.
        val pdol = fci.find(0x9F38)?.value?.let(::dol).orEmpty()
        val data = pdolData(pdol, today, random)
        val gpo = io.send(byteArrayOf(0x80.toByte(), 0xA8.toByte(), 0x00, 0x00, (data.size + 2).toByte(), 0x83, data.size.toByte()) + data + byteArrayOf(0x00))
        val afl = ArrayList<IntArray>()
        if (ok(gpo)) {
            val g = body(gpo)
            val t = parse(g)
            found += t
            val raw = when {
                g.isNotEmpty() && g[0] == 0x80.toByte() -> t.find(0x80)?.value?.let { it.copyOfRange(minOf(2, it.size), it.size) }
                else -> t.find(0x94)?.value
            }
            if (raw != null) for (k in 0 until raw.size / 4) {
                afl += intArrayOf((raw[4 * k].toInt() and 0xFF) shr 3, raw[4 * k + 1].toInt() and 0xFF, raw[4 * k + 2].toInt() and 0xFF)
            }
        }
        // No usable file locator: look through the usual record files.
        if (afl.isEmpty()) for (sfi in 1..4) afl += intArrayOf(sfi, 1, 4)
        for ((sfi, first, last) in afl.map { Triple(it[0], it[1], it[2]) }) {
            for (rec in first..last) {
                val r = runCatching { io.send(readRecord(sfi, rec)) }.getOrNull() ?: continue
                if (ok(r)) found += parse(body(r)) else if (afl.size == 4 && rec == first) break
            }
        }

        // The number: tag 5A, or the start of track 2 equivalent data (57) up to its separator D.
        val pan = found.find(0x5A)?.value?.let { NfcChips.hex(it, "").trimEnd('F') }
            ?: found.find(0x57)?.value?.let { NfcChips.hex(it, "").substringBefore('D') }
        val track2Expiry = found.find(0x57)?.value?.let { NfcChips.hex(it, "").substringAfter('D', "").take(4) }?.takeIf { it.length == 4 }
        val expiry = found.find(0x5F24)?.value?.let { NfcChips.hex(it, "") }?.let { "${it.substring(2, 4)}/${it.substring(0, 2)}" }
            ?: track2Expiry?.let { "${it.substring(2, 4)}/${it.substring(0, 2)}" }
        val effective = found.find(0x5F25)?.value?.let { NfcChips.hex(it, "") }?.let { "${it.substring(2, 4)}/${it.substring(0, 2)}" }
        val country = found.find(0x5F28)?.value?.let { country(NfcChips.hex(it, "").toInt()) }
        val currency = found.find(0x9F42)?.value?.let { currency(NfcChips.hex(it, "").toInt()) }
        val holder = found.find(0x5F20)?.value?.let { String(it, Charsets.ISO_8859_1).trim().replace('/', ' ').trim() }?.takeIf { it.isNotBlank() && it != "CARDHOLDER" }

        // Transaction log: where (log entry: file and number of records) and how (log format).
        val entry = fci.find(0x9F4D)?.value ?: fci.find(0xDF60)?.value
        val txs = ArrayList<EmvTx>()
        if (entry != null && entry.size >= 2) {
            val format = runCatching { io.send(getData(0x9F4F)) }.getOrNull()?.takeIf(::ok)?.let { parse(body(it)).find(0x9F4F)?.value }?.let(::dol)
            if (format != null) {
                val sfi = entry[0].toInt() and 0xFF
                for (rec in 1..(entry[1].toInt() and 0xFF)) {
                    val r = runCatching { io.send(readRecord(sfi, rec)) }.getOrNull() ?: break
                    if (!ok(r)) break
                    logEntry(body(r), format)?.let { txs += it }
                }
            }
        }
        return EmvCard(
            scheme(aid), label, aid, pan?.takeLast(4), expiry, effective, country, currency, holder,
            apps.map { (a, l) -> l ?: scheme(a) }.distinct(), txs, entry != null,
        )
    }

    /** One record of the log, cut by the log format; empty records (all zero) are skipped. */
    fun logEntry(b: ByteArray, format: List<Pair<Int, Int>>): EmvTx? {
        if (b.all { it == 0.toByte() }) return null
        var i = 0
        val v = HashMap<Int, ByteArray>()
        for ((tag, len) in format) {
            if (i + len > b.size) break
            v[tag] = b.copyOfRange(i, i + len)
            i += len
        }
        fun bcd(t: Int) = v[t]?.let { NfcChips.hex(it, "") }
        val date = bcd(0x9A)?.takeIf { it.length == 6 }?.let { "20${it.substring(0, 2)}-${it.substring(2, 4)}-${it.substring(4, 6)}" }
        val time = bcd(0x9F21)?.takeIf { it.length == 6 }?.let { "${it.substring(0, 2)}:${it.substring(2, 4)}" }
        val amount = bcd(0x9F02)?.toLongOrNull()
        return EmvTx(
            date, time, amount, bcd(0x5F2A)?.toIntOrNull()?.let(::currency), bcd(0x9F1A)?.toIntOrNull()?.let(::country),
            v[0x9F4E]?.let { String(it, Charsets.ISO_8859_1).trim() }?.takeIf { it.isNotBlank() },
        )
    }

    fun currency(n: Int): String = when (n) {
        978 -> "EUR"; 840 -> "USD"; 826 -> "GBP"; 756 -> "CHF"; 124 -> "CAD"; 392 -> "JPY"; 36 -> "AUD"; 156 -> "CNY"
        752 -> "SEK"; 578 -> "NOK"; 208 -> "DKK"; 985 -> "PLN"; 203 -> "CZK"; 348 -> "HUF"; 946 -> "RON"; 949 -> "TRY"
        643 -> "RUB"; 980 -> "UAH"; 356 -> "INR"; 360 -> "IDR"; 704 -> "VND"; 764 -> "THB"; 410 -> "KRW"; 784 -> "AED"; 504 -> "MAD"
        else -> "%03d".format(n)
    }

    fun country(n: Int): String = when (n) {
        250 -> "FR"; 56 -> "BE"; 756 -> "CH"; 442 -> "LU"; 276 -> "DE"; 724 -> "ES"; 380 -> "IT"; 620 -> "PT"; 528 -> "NL"
        826 -> "GB"; 840 -> "US"; 124 -> "CA"; 752 -> "SE"; 616 -> "PL"; 203 -> "CZ"; 642 -> "RO"; 643 -> "RU"; 804 -> "UA"
        792 -> "TR"; 392 -> "JP"; 410 -> "KR"; 156 -> "CN"; 356 -> "IN"; 360 -> "ID"; 704 -> "VN"; 764 -> "TH"; 504 -> "MA"; 12 -> "DZ"; 788 -> "TN"
        else -> "%03d".format(n)
    }
}
