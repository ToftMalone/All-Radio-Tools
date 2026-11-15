package com.allnetworktools

import com.allnetworktools.data.Ad
import com.allnetworktools.data.AdStructure
import com.allnetworktools.data.Beacons
import com.allnetworktools.data.BleDevice
import com.allnetworktools.data.BleIdentify
import com.allnetworktools.data.BleKind
import com.allnetworktools.data.BleSpam
import com.allnetworktools.data.Earbuds
import com.allnetworktools.data.Glasses
import com.allnetworktools.data.TrackerDetect
import com.allnetworktools.data.net.DnsClient
import com.allnetworktools.data.net.Stun
import com.allnetworktools.data.nfc.Emv
import com.allnetworktools.data.nfc.NfcChips
import com.allnetworktools.data.nfc.Wsc
import com.allnetworktools.data.remoteid.RemoteId
import com.allnetworktools.data.sdr.Acars
import com.allnetworktools.data.sdr.Ais
import com.allnetworktools.data.sdr.Ant
import com.allnetworktools.data.sdr.Aprs
import com.allnetworktools.data.sdr.Ax25
import com.allnetworktools.data.sdr.Horus
import com.allnetworktools.data.sdr.LoraAprs
import com.allnetworktools.data.sdr.LoraWan
import com.allnetworktools.data.sdr.MeshCore
import com.allnetworktools.data.sdr.Meshtastic
import com.allnetworktools.data.sdr.Zigbee
import com.allnetworktools.data.sdr.Zwave
import java.util.Random
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the radios hear is written by strangers: every parser of received bytes must give an answer or nothing, never
 * throw. Random and half-plausible inputs are thrown at them; any exception fails the test with the input that did it.
 */
class FuzzTest {
    private val rnd = Random(20261115)
    private fun bytes(max: Int = 80): ByteArray = ByteArray(rnd.nextInt(max + 1)).also(rnd::nextBytes)
    private fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it) }

    private val failures = LinkedHashMap<String, String>()
    private inline fun probe(name: String, input: () -> String, block: () -> Unit) {
        if (name in failures) return
        try { block() } catch (e: Throwable) {
            failures[name] = "${e.javaClass.simpleName}: ${e.message} at ${e.stackTrace.firstOrNull { it.className.startsWith("com.allnetworktools") }} input=${input()}"
        }
    }

    private val companies = intArrayOf(0x004C, 0x0075, 0x0006, 0x00E0, 0x0D53, 0x03C2, 0x058E, 0x01AB, 0x060C, 0x10F9, 0x0157, 0x038F, 0x0087, 0x0059, 0x02E5, 0x0499, 0x0001)
    private val services = intArrayOf(0xFE2C, 0xFD6F, 0xFEAA, 0xFFFA, 0xFD5A, 0xFEED, 0xFE33, 0xFE45, 0xFD5F, 0xFEB7, 0xFEB8, 0xFCB2, 0xFD84, 0xFEEC, 0x3081, 0x3082, 0x3083, 0xFE9F, 0xFDF0, 0xFE78, 0xFD44, 0x181A, 0xFCD2, 0xFE95)
    private val types = intArrayOf(0xFF, 0xFF, 0xFF, 0x16, 0x16, 0x16, 0x03, 0x02, 0x07, 0x06, 0x09, 0x08, 0x01, 0x0A, 0x19, 0x1A, 0x20, 0x21, 0x0D, 0x14, 0x15, 0x2A, 0x2B, 0x29)

    /** A few AD structures as a hostile advertiser would send: known types, known companies and services, any length. */
    private fun ads(): List<AdStructure> = List(1 + rnd.nextInt(4)) {
        val type = if (rnd.nextInt(8) == 0) rnd.nextInt(256) else types[rnd.nextInt(types.size)]
        val body = bytes(if (rnd.nextInt(6) == 0) 3 else 31)
        if (body.size >= 2 && rnd.nextInt(4) != 0) {
            val id = if (type == 0xFF) companies[rnd.nextInt(companies.size)] else services[rnd.nextInt(services.size)]
            body[0] = id.toByte(); body[1] = (id shr 8).toByte()
            // Apple and Microsoft continuity types, Samsung and Google frames: the first bytes pick the branch.
            if (body.size >= 3 && rnd.nextBoolean()) body[2] = intArrayOf(0x02, 0x05, 0x07, 0x09, 0x0A, 0x0C, 0x0F, 0x10, 0x12, 0x19, 0x01, 0x03, 0x00, 0x42, 0x04, 0x15)[rnd.nextInt(16)].toByte()
            if (body.size >= 4 && rnd.nextBoolean()) body[3] = rnd.nextInt(40).toByte()
        }
        AdStructure(type, body)
    }

    @Test fun bluetoothAdvertisements() {
        repeat(150_000) {
            val a = ads()
            val name = if (rnd.nextInt(3) == 0) listOf("Flipper Abc", "Ray-Ban", "Galaxy Buds2", "AirPods", "", "Quest 3", "\u0000", "Tile")[rnd.nextInt(8)] else null
            val raw = Ad.hex(a)
            probe("Ad.parse", { raw }) { Ad.parse(bytes(70)) }
            probe("Ad.parseHex", { raw }) { Ad.parseHex(raw) }
            probe("Ad.describe", { raw }) { a.forEach { Ad.describe(it) } }
            probe("Ad.accessors", { raw }) { Ad.name(a); Ad.flags(a); Ad.txPower(a); Ad.appearance(a); Ad.classOfDevice(a); Ad.advertisedIntervalMs(a); Ad.manufacturer(a); Ad.serviceData(a); Ad.uuids16(a); Ad.uuids128(a) }
            probe("BleIdentify.identify", { raw }) { BleIdentify.identify(name, a, "C4:7C:8D:1E:2B:3F") }
            probe("Beacons.parse", { raw }) { Beacons.parse(a); Beacons.telemetry(a) }
            probe("Earbuds.parse", { raw }) { Earbuds.parse(a) }
            probe("BleSpam.classify", { raw }) { BleSpam.classify(a); BleSpam.flipper(a, name, "80:E1:26:00:11:22") }
            probe("TrackerDetect.classify", { raw }) { TrackerDetect.classify(a, "C4:7C:8D:1E:2B:3F") }
            probe("Glasses.match", { raw }) { Glasses.match(BleDevice("C4:7C:8D:1E:2B:3F", name, -60, null, BleKind.Unknown, null, true, 0L, raw = raw)) }
            probe("RemoteId.ble", { raw }) { a.forEach { RemoteId.parseServiceData(it.data); RemoteId.parseMessage(it.data) } }
        }
        report()
    }

    @Test fun framesAndPackets() {
        repeat(200_000) {
            val b = bytes(if (rnd.nextInt(5) == 0) 300 else 60)
            val h = { hex(b) }
            probe("RemoteId.wifi", h) { RemoteId.parseWifiVendorIe(b); RemoteId.parseNan(b); RemoteId.parseMessage(b); RemoteId.parseServiceData(b) }
            probe("DnsClient.parse", h) { runCatching { DnsClient.parse(b, 1f) }.onFailure { if (it !is java.io.IOException) throw it } }
            probe("Stun.decode", h) { Stun.decode(b) }
            probe("Emv.parse", h) { Emv.parse(b); Emv.dol(b) }
            probe("Wsc.parse", h) { Wsc.parse(b) }
            probe("NfcChips", h) { NfcChips.fromVersion(b); NfcChips.desfire(b); NfcChips.manufacturer(b); if (b.size >= 8) NfcChips.iso15693(b.copyOf(8), b) }
            probe("Meshtastic", h) {
                Meshtastic.decode(b); Meshtastic.position(b); Meshtastic.user(b); Meshtastic.telemetry(b); Meshtastic.route(b); Meshtastic.neighbors(b)
                Meshtastic.waypoint(b); Meshtastic.mapReport(b); Meshtastic.routingError(b); Meshtastic.data(b)
            }
            probe("MeshCore", h) {
                val p = MeshCore.parse(b)
                MeshCore.advert(b); MeshCore.groupText(b, listOf(MeshCore.PUBLIC))
                if (p != null) { MeshCore.advert(p.payload); MeshCore.groupText(p.payload, listOf(MeshCore.PUBLIC)); MeshCore.addressees(p.type, p.payload) }
            }
            probe("Ax25/Aprs", h) { Ax25.parse(b)?.let { Aprs.decode(it) } }
            probe("LoraAprs", h) { LoraAprs.parse(b)?.let { Aprs.decode(it) } }
            probe("LoraWan.parse", h) { LoraWan.parse(b) }
            probe("Zigbee.parse", h) { Zigbee.parse(b) }
            probe("Zwave.parse", h) { Zwave.parse(b, 0); Zwave.parse(b, 1); Zwave.parse(b, 2) }
            probe("Ant", h) { Ant.parse(b); if (b.size >= 8) Ant.decode(b[0].toInt() and 0xFF, b.copyOfRange(0, 8)) }
            probe("Ais.decode", h) {
                // One bit per byte, as the HDLC decoder hands them over, with a plausible message type in front.
                val bits = ByteArray(b.size * 8) { ((b[it / 8].toInt() shr (7 - it % 8)) and 1).toByte() }
                if (bits.size >= 6 && rnd.nextBoolean()) { val t = 1 + rnd.nextInt(27); for (k in 0 until 6) bits[k] = ((t shr (5 - k)) and 1).toByte() }
                Ais.decode(bits, bits.size); Ais.decode(bits, maxOf(0, bits.size - rnd.nextInt(40)))
            }
            probe("Rs41.parse", h) { com.allnetworktools.data.sdr.Rs41.parse(b.copyOf(maxOf(b.size, 520))) }
            probe("Wmbus", h) {
                val a = IntArray(b.size) { b[it].toInt() and 0xFF }
                com.allnetworktools.data.sdr.Wmbus.parse(a, false); com.allnetworktools.data.sdr.Wmbus.parse(a, true); com.allnetworktools.data.sdr.Wmbus.records(a)
            }
            probe("Vdl2.parse", h) { com.allnetworktools.data.sdr.Vdl2.parse(IntArray(b.size) { b[it].toInt() and 0xFF }, 136_975_000L, 0L, 10.0) }
            probe("Tle.parseAll", h) { com.allnetworktools.data.orbit.Tle.parseAll(String(b, Charsets.ISO_8859_1)) }
            probe("Horus.parse", h) { Horus.parse(b) }
            probe("Acars.parse", h) { Acars.parse(b, b.size, 131.725, 0L) }
        }
        report()
    }

    /** An APRS information field is free text: positions, objects, weather, messages, telemetry, Mic-E, compressed. */
    @Test fun aprsText() {
        val starts = "!=/@;)`':>_T<?}{$*,#%&+.-0123456789"
        val alphabet = "0123456789NSEW/\\.,-_ :;!=@#>*{}|^aBcZ"
        repeat(300_000) {
            val n = rnd.nextInt(70)
            val s = StringBuilder().append(starts[rnd.nextInt(starts.length)])
            repeat(n) { s.append(if (rnd.nextInt(6) == 0) (32 + rnd.nextInt(95)).toChar() else alphabet[rnd.nextInt(alphabet.length)]) }
            val info = s.toString().toByteArray(Charsets.ISO_8859_1)
            val dst = listOf("APRS", "T2SP0W", "S32U6T", "APDR16", "ZZZZZZ", "P0PPPP", "")[rnd.nextInt(7)]
            probe("Aprs.decode", { "$dst>$s" }) { Aprs.decode(com.allnetworktools.data.sdr.Ax25Frame("F4ABC-9", dst, listOf("WIDE1-1"), info)) }
        }
        report()
    }

    private fun report() {
        assertTrue(failures.entries.joinToString("\n", "\n") { "${it.key}: ${it.value}" }, failures.isEmpty())
    }
}
