package com.allnetworktools.data.nfc

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer

enum class WifiAuth { Open, Wpa2, Wpa3 }

/** What an NDEF record holds, as the reader shows it and the writer makes it. */
sealed interface NdefContent {
    data class Url(val uri: String) : NdefContent
    data class Text(val text: String, val lang: String = "fr") : NdefContent
    data class Wifi(val ssid: String, val password: String, val auth: WifiAuth) : NdefContent
    data class Contact(val name: String, val phone: String = "", val email: String = "", val org: String = "") : NdefContent
    data class Phone(val number: String) : NdefContent
    data class Sms(val number: String, val body: String = "") : NdefContent
    data class Email(val to: String, val subject: String = "", val body: String = "") : NdefContent
    data class Geo(val lat: Double, val lon: Double) : NdefContent
    data class App(val pkg: String) : NdefContent
    data class Mime(val type: String, val size: Int, val preview: String?) : NdefContent
    data class Other(val tnf: Int, val type: String, val size: Int) : NdefContent
}

object Ndef {
    const val WSC_MIME = "application/vnd.wfa.wsc"

    fun build(c: NdefContent): NdefMessage = NdefMessage(arrayOf(record(c)))

    fun record(c: NdefContent): NdefRecord = when (c) {
        is NdefContent.Url -> NdefRecord.createUri(c.uri)
        is NdefContent.Text -> NdefRecord.createTextRecord(c.lang, c.text)
        is NdefContent.Wifi -> NdefRecord.createMime(WSC_MIME, Wsc.credential(c.ssid, c.password, c.auth))
        is NdefContent.Contact -> NdefRecord.createMime("text/vcard", vcard(c).toByteArray())
        is NdefContent.Phone -> NdefRecord.createUri("tel:" + c.number.filter { it.isDigit() || it == '+' })
        is NdefContent.Sms -> NdefRecord.createUri("sms:" + c.number.filter { it.isDigit() || it == '+' } + if (c.body.isNotEmpty()) "?body=" + enc(c.body) else "")
        is NdefContent.Email -> NdefRecord.createUri(
            "mailto:" + c.to + listOfNotNull(c.subject.takeIf { it.isNotEmpty() }?.let { "subject=" + enc(it) }, c.body.takeIf { it.isNotEmpty() }?.let { "body=" + enc(it) })
                .joinToString("&").let { if (it.isEmpty()) "" else "?$it" },
        )
        is NdefContent.Geo -> NdefRecord.createUri("geo:%.6f,%.6f".format(java.util.Locale.US, c.lat, c.lon))
        is NdefContent.App -> NdefRecord.createApplicationRecord(c.pkg)
        is NdefContent.Mime, is NdefContent.Other -> error("read only")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun dec(s: String) = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

    private fun vcard(c: NdefContent.Contact) = buildString {
        append("BEGIN:VCARD\r\nVERSION:3.0\r\n")
        append("FN:").append(c.name).append("\r\n")
        if (c.org.isNotEmpty()) append("ORG:").append(c.org).append("\r\n")
        if (c.phone.isNotEmpty()) append("TEL:").append(c.phone).append("\r\n")
        if (c.email.isNotEmpty()) append("EMAIL:").append(c.email).append("\r\n")
        append("END:VCARD\r\n")
    }

    fun parse(m: NdefMessage?): List<NdefContent> = m?.records.orEmpty().map(::parse)

    fun parse(r: NdefRecord): NdefContent {
        val type = String(r.type, Charsets.US_ASCII)
        return when {
            r.tnf == NdefRecord.TNF_WELL_KNOWN && r.type.contentEquals(NdefRecord.RTD_TEXT) -> text(r.payload)
            r.tnf == NdefRecord.TNF_WELL_KNOWN && r.type.contentEquals(NdefRecord.RTD_SMART_POSTER) ->
                runCatching { NdefMessage(r.payload).records.map(::parse).firstOrNull { it is NdefContent.Url || it is NdefContent.Phone } }.getOrNull()
                    ?: NdefContent.Other(r.tnf, "Sp", r.payload.size)
            (r.tnf == NdefRecord.TNF_WELL_KNOWN && r.type.contentEquals(NdefRecord.RTD_URI)) || r.tnf == NdefRecord.TNF_ABSOLUTE_URI ->
                uri(r.toUri()?.toString() ?: String(r.payload, Charsets.UTF_8))
            r.tnf == NdefRecord.TNF_MIME_MEDIA -> when (type.lowercase()) {
                WSC_MIME -> Wsc.parse(r.payload) ?: NdefContent.Mime(type, r.payload.size, null)
                "text/vcard", "text/x-vcard" -> contact(String(r.payload, Charsets.UTF_8))
                else -> NdefContent.Mime(type, r.payload.size, if (type.startsWith("text/")) String(r.payload, Charsets.UTF_8).take(200) else null)
            }
            r.tnf == NdefRecord.TNF_EXTERNAL_TYPE && type == "android.com:pkg" -> NdefContent.App(String(r.payload, Charsets.UTF_8))
            else -> NdefContent.Other(r.tnf, type, r.payload.size)
        }
    }

    /** Status byte (UTF-16 flag, language length), language code, then the text. */
    private fun text(p: ByteArray): NdefContent.Text {
        if (p.isEmpty()) return NdefContent.Text("", "")
        val status = p[0].toInt()
        val langLen = status and 0x3F
        val cs = if (status and 0x80 != 0) Charsets.UTF_16 else Charsets.UTF_8
        val lang = String(p, 1, minOf(langLen, p.size - 1), Charsets.US_ASCII)
        val start = minOf(1 + langLen, p.size)
        return NdefContent.Text(String(p, start, p.size - start, cs), lang)
    }

    fun uri(u: String): NdefContent {
        val l = u.lowercase()
        return when {
            l.startsWith("tel:") -> NdefContent.Phone(u.substring(4))
            l.startsWith("sms:") || l.startsWith("smsto:") -> {
                val rest = u.substringAfter(':')
                NdefContent.Sms(rest.substringBefore('?'), query(rest)["body"].orEmpty())
            }
            l.startsWith("mailto:") -> {
                val rest = u.substring(7)
                val q = query(rest)
                NdefContent.Email(rest.substringBefore('?'), q["subject"].orEmpty(), q["body"].orEmpty())
            }
            l.startsWith("geo:") -> {
                val p = u.substring(4).substringBefore('?').substringBefore(';').split(',')
                val lat = p.getOrNull(0)?.toDoubleOrNull()
                val lon = p.getOrNull(1)?.toDoubleOrNull()
                if (lat != null && lon != null) NdefContent.Geo(lat, lon) else NdefContent.Url(u)
            }
            else -> NdefContent.Url(u)
        }
    }

    private fun query(s: String): Map<String, String> = s.substringAfter('?', "").split('&').filter { '=' in it }
        .associate { it.substringBefore('=').lowercase() to dec(it.substringAfter('=')) }

    private fun contact(v: String): NdefContent.Contact {
        fun field(name: String) = v.lineSequence().map { it.trimEnd('\r') }
            .firstOrNull { it.uppercase().startsWith("$name:") || it.uppercase().startsWith("$name;") }?.substringAfter(':')?.trim().orEmpty()
        return NdefContent.Contact(field("FN").ifEmpty { field("N").replace(';', ' ').trim() }, field("TEL"), field("EMAIL"), field("ORG"))
    }

    /** Bytes an NDEF message takes on a type 2 tag, TLV header included, to check it fits before writing. */
    fun sizeOnTag(m: NdefMessage): Int = m.byteArrayLength.let { it + if (it < 0xFF) 2 else 4 }
}

/**
 * Wi-Fi Simple Configuration credential (Wi-Fi Alliance), the record Android reads from a tag to join a network:
 * attributes are 2-byte type, 2-byte length, value.
 */
object Wsc {
    private const val CREDENTIAL = 0x100E
    private const val NETWORK_INDEX = 0x1026
    private const val SSID = 0x1045
    private const val AUTH_TYPE = 0x1003
    private const val ENCRYPTION = 0x100F
    private const val NETWORK_KEY = 0x1027
    private const val MAC = 0x1020

    private const val AUTH_OPEN = 0x0001
    private const val AUTH_WPA2_PSK = 0x0020
    private const val AUTH_WPA3_SAE = 0x0040
    private const val ENC_NONE = 0x0001
    private const val ENC_AES = 0x0008

    private fun attr(type: Int, value: ByteArray): ByteArray =
        ByteBuffer.allocate(4 + value.size).putShort(type.toShort()).putShort(value.size.toShort()).put(value).array()

    private fun short(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())

    fun credential(ssid: String, password: String, auth: WifiAuth): ByteArray {
        val open = auth == WifiAuth.Open
        val inner = attr(NETWORK_INDEX, byteArrayOf(1)) +
            attr(SSID, ssid.toByteArray()) +
            attr(AUTH_TYPE, short(when (auth) { WifiAuth.Open -> AUTH_OPEN; WifiAuth.Wpa2 -> AUTH_WPA2_PSK; WifiAuth.Wpa3 -> AUTH_WPA3_SAE })) +
            attr(ENCRYPTION, short(if (open) ENC_NONE else ENC_AES)) +
            attr(NETWORK_KEY, if (open) ByteArray(0) else password.toByteArray()) +
            attr(MAC, ByteArray(6) { 0xFF.toByte() })
        return attr(CREDENTIAL, inner)
    }

    fun parse(p: ByteArray): NdefContent.Wifi? {
        val cred = find(p, CREDENTIAL) ?: return null
        val ssid = find(cred, SSID)?.let { String(it, Charsets.UTF_8) } ?: return null
        val key = find(cred, NETWORK_KEY)?.let { String(it, Charsets.UTF_8) }.orEmpty()
        val auth = find(cred, AUTH_TYPE)?.let { ((it[0].toInt() and 0xFF) shl 8) or (it[1].toInt() and 0xFF) } ?: AUTH_OPEN
        val a = when {
            auth and AUTH_WPA3_SAE != 0 -> WifiAuth.Wpa3
            auth and (AUTH_WPA2_PSK or 0x0002 or 0x0008 or 0x0010) != 0 -> WifiAuth.Wpa2
            else -> WifiAuth.Open
        }
        return NdefContent.Wifi(ssid, key, a)
    }

    private fun find(b: ByteArray, type: Int): ByteArray? {
        var i = 0
        while (i + 4 <= b.size) {
            val t = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
            val l = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            if (i + 4 + l > b.size) return null
            if (t == type) return b.copyOfRange(i + 4, i + 4 + l)
            i += 4 + l
        }
        return null
    }
}
