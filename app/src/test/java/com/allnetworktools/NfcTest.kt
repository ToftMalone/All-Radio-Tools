package com.allnetworktools

import com.allnetworktools.data.nfc.Apdu
import com.allnetworktools.data.nfc.ChipFamily
import com.allnetworktools.data.nfc.Emv
import com.allnetworktools.data.nfc.Ndef
import com.allnetworktools.data.nfc.NdefContent
import com.allnetworktools.data.nfc.NfcChips
import com.allnetworktools.data.nfc.WifiAuth
import com.allnetworktools.data.nfc.Wsc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** NFC: naming chips from their answers, NDEF both ways, and a payment card read through a simulated card. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "fr")
class NfcTest {
    private fun hex(s: String) = NfcChips.parseHex(s)

    @Test
    fun chipsFromTheirAnswers() {
        assertEquals("MIFARE Classic 1K", NfcChips.fromSak(0x08, 0x0004).name)
        assertEquals("MIFARE Classic 4K", NfcChips.fromSak(0x18, 0x0002).name)
        assertEquals(ChipFamily.Ultralight, NfcChips.fromSak(0x00, 0x0044).family)
        assertEquals(ChipFamily.Iso14443_4, NfcChips.fromSak(0x20, 0x0344).family)
        // GET_VERSION of an NTAG215 and of an Ultralight EV1 (MF0UL11).
        NfcChips.fromVersion(hex("0004040201001103"))!!.let {
            assertEquals("NTAG215", it.name); assertEquals(504, it.userBytes); assertEquals(135, it.units)
        }
        assertEquals("NTAG213", NfcChips.fromVersion(hex("0004040201000F03"))!!.name)
        assertEquals(48, NfcChips.fromVersion(hex("0004030101000B03"))!!.userBytes)
        // DESFire GetVersion, first frame: EV1 4K, then EV3 8K.
        assertEquals("MIFARE DESFire EV1 4K", NfcChips.desfire(hex("04010101001805"))!!.name)
        assertEquals("MIFARE DESFire EV3 8K", NfcChips.desfire(hex("04010133001A05"))!!.name)
        assertNull(NfcChips.desfire(hex("04020101001805")))
    }

    @Test
    fun makersAndUids() {
        assertEquals("NXP Semiconductors", NfcChips.manufacturer(hex("04A1B2C3D4E5F6")))
        assertEquals("STMicroelectronics", NfcChips.manufacturer(hex("02A1B2C3D4E5F6")))
        // 4-byte UIDs carry no maker; 08 means a random one.
        assertNull(NfcChips.manufacturer(hex("08A1B2C3")))
        assertTrue(NfcChips.isRandomUid(hex("08A1B2C3")))
        assertFalse(NfcChips.isRandomUid(hex("A1B2C3D4")))
        // ISO 15693, least significant byte first as Android gives it: E0 04 (NXP) 01 (ICODE), SLIX bits set.
        val uid = hex("11223344100104E0")
        assertEquals("NXP Semiconductors", NfcChips.manufacturer(uid, iso15693 = true))
        val info = hex("000F") + uid + hex("00001B0301")
        val chip = NfcChips.iso15693(uid, info)
        assertEquals("ICODE SLIX", chip.name)
        assertEquals(28, chip.units)
        assertEquals(4, chip.unitBytes)
    }

    @Test
    fun ndefBothWays() {
        val all = listOf(
            NdefContent.Url("https://example.org/a?b=c"),
            NdefContent.Text("Bonjour à tous", "fr"),
            NdefContent.Wifi("Maison", "motdepasse123", WifiAuth.Wpa2),
            NdefContent.Wifi("Café libre", "", WifiAuth.Open),
            NdefContent.Contact("Ada Lovelace", "+33600000000", "ada@example.org", "Analytical"),
            NdefContent.Phone("+33612345678"),
            NdefContent.Sms("+33612345678", "On arrive, à tout de suite"),
            NdefContent.Email("hello@example.org", "Salut & bienvenue", "Corps du message"),
            NdefContent.Geo(48.858370, 2.294481),
            NdefContent.App("com.allnetworktools"),
        )
        for (c in all) {
            val back = Ndef.parse(Ndef.build(c)).single()
            assertEquals(c, back)
        }
        assertTrue(Ndef.sizeOnTag(Ndef.build(all[0])) < 40)
    }

    @Test
    fun wifiCredentialAsAndroidWritesIt() {
        // SSID "AB", WPA2-PSK, AES, key "12345678": what an Android phone puts on a tag.
        val p = Wsc.credential("AB", "12345678", WifiAuth.Wpa2)
        assertEquals("100E", NfcChips.hex(p.copyOfRange(0, 2), ""))
        val w = Wsc.parse(p)!!
        assertEquals("AB", w.ssid)
        assertEquals("12345678", w.password)
        assertEquals(WifiAuth.Wpa2, w.auth)
    }

    @Test
    fun tlvNested() {
        val t = Emv.parse(hex("6F1A840E325041592E5359532E4444463031A508BF0C0561034F0101"))
        assertEquals(0x6F, t.single().tag)
        assertEquals("01", NfcChips.hex(t.single().find(0x4F)!!.value, ""))
        assertEquals(listOf(0x9F66 to 4, 0x9F02 to 6, 0x5F2A to 2), Emv.dol(hex("9F66049F02065F2A02")))
    }

    /** A Mastercard that answers like a real one: directory, application, GPO, one record, a two-entry log. */
    private class FakeCard : Apdu {
        val sent = ArrayList<String>()
        override fun send(cmd: ByteArray): ByteArray {
            val h = NfcChips.hex(cmd, "")
            sent += h
            val ok = "9000"
            fun r(s: String) = NfcChips.parseHex(s + ok)
            return when {
                h.startsWith("00A404000E325041592E5359532E4444463031") ->
                    r("6F2C840E325041592E5359532E4444463031A51ABF0C1761154F07A00000000410105007" + NfcChips.hex("MASTERCARD".take(7).toByteArray(), "") + "870101")
                h.startsWith("00A4040007A0000000041010") ->
                    r("6F1C" + "8407A0000000041010" + "A511" + "500A" + NfcChips.hex("MASTERCARD".toByteArray(), "") + "9F4D020B02")
                h.startsWith("80A8") -> r("770E82021980940808010100" + "10010100")
                h == "00B2010C00" -> r("7025" + "5A0854133300898012345F" + "5F24032810315F25032310015F280202505F2007" + NfcChips.hex("DUPONT/".toByteArray(), ""))
                h == "00B2011400" -> r("700F9F420209785F300202019F0702FF00")
                h == "80CA9F4F00" -> r("9F4F109A039F21039F02065F2A029F1A029C01")
                h == "00B2015C00" -> r("2610310930150000000012340978025000")
                h == "00B2025C00" -> r("2610281215000000000099000978025000")
                else -> NfcChips.parseHex("6A82")
            }
        }
    }

    @Test
    fun paymentCardKeepsOnlyTheLastFourDigits() {
        val io = FakeCard()
        val c = Emv.read(io, "261031", hex("01020304"))!!
        assertEquals("Mastercard", c.scheme)
        assertEquals("MASTERCARD", c.label)
        assertEquals("1234", c.last4)
        assertEquals("10/28", c.expiry)
        assertEquals("01/23", c.effective)
        assertEquals("FR", c.country)
        assertEquals("EUR", c.currency)
        assertEquals("DUPONT", c.holder)
        assertEquals(2, c.transactions.size)
        c.transactions[0].let {
            assertEquals("2026-10-31", it.date); assertEquals("09:30", it.time); assertEquals(1234L, it.amount); assertEquals("EUR", it.currency); assertEquals("FR", it.country)
        }
        assertEquals(9900L, c.transactions[1].amount)
        // The full number is in no field of the result.
        assertFalse(c.toString().contains("5413330089801234"))
        assertFalse(c.toString().contains("54133300"))
    }

    @Test
    fun noPaymentApplication() {
        assertNull(Emv.read({ NfcChips.parseHex("6A82") }, "261031"))
    }
}
