package com.allnetworktools

import com.allnetworktools.data.sdr.LoraPhy
import com.allnetworktools.data.sdr.MeshFrame
import com.allnetworktools.data.sdr.MeshRadio
import com.allnetworktools.data.sdr.Meshtastic
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Random
import kotlin.math.roundToInt

class MeshtasticTest {
    /** A minimal protobuf writer, for building what a node sends. */
    private class W {
        val o = ByteArrayOutputStream()
        fun varint(v: Long) { var x = v; while (true) { if (x and 0x7FL.inv() == 0L) { o.write(x.toInt()); return }; o.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 } }
        fun tag(f: Int, w: Int) = varint(((f shl 3) or w).toLong())
        fun u(f: Int, v: Long) { tag(f, 0); varint(v) }
        fun fixed(f: Int, v: Int) { tag(f, 5); for (i in 0 until 4) o.write((v ushr (8 * i)) and 0xFF) }
        fun float(f: Int, v: Float) = fixed(f, java.lang.Float.floatToIntBits(v))
        fun bytes(f: Int, b: ByteArray) { tag(f, 2); varint(b.size.toLong()); o.write(b) }
        fun str(f: Int, s: String) = bytes(f, s.toByteArray())
        fun done() = o.toByteArray()
    }

    private fun header(to: Int, from: Int, id: Int, hopLimit: Int, hopStart: Int, channel: Int): ByteArray {
        val b = ByteArray(16)
        for (i in 0 until 4) { b[i] = (to ushr (8 * i)).toByte(); b[4 + i] = (from ushr (8 * i)).toByte(); b[8 + i] = (id ushr (8 * i)).toByte() }
        b[12] = (hopLimit or (hopStart shl 5)).toByte()
        b[13] = channel.toByte()
        return b
    }

    private fun packet(from: Int, id: Int, port: Int, payload: ByteArray, channel: String = "LongFast"): ByteArray {
        val data = W().apply { u(1, port.toLong()); bytes(2, payload) }.done()
        val c = Meshtastic.Channel(channel, Meshtastic.DEFAULT_PSK)
        return header(Meshtastic.BROADCAST, from, id, 2, 3, c.hash) + Meshtastic.crypt(c.key, from, id, data)
    }

    @Test fun defaultChannelHashAndKeys() {
        // Firmware test/test_channel_keys: the stock LongFast channel hashes to 0x08.
        assertEquals(0x08, Meshtastic.channelHash("LongFast", Meshtastic.DEFAULT_PSK))
        assertArrayEquals(Meshtastic.DEFAULT_PSK, Meshtastic.expandKey(byteArrayOf(1))!!)
        assertEquals(0x02.toByte(), Meshtastic.expandKey(byteArrayOf(2))!![15])
        assertEquals(0, Meshtastic.expandKey(byteArrayOf(0))!!.size)
        // The slots the bands listen on come from the firmware's djb2 of the preset names.
        assertEquals(3L, Meshtastic.slotHash("LongFast") % 4)
        assertEquals(0L, Meshtastic.slotHash("LongSlow") % 2)
        assertEquals(1L, Meshtastic.slotHash("LongMod") % 2)
    }

    @Test fun goldenCiphertext() {
        // meshtastic-sniffer's reference vector, made with openssl: "hi" on TEXT_MESSAGE_APP from 0xdeadbeef, id 0x12345678.
        val ct = byteArrayOf(0xab.toByte(), 0xa2.toByte(), 0x29, 0x93.toByte(), 0xee.toByte(), 0x1d)
        val raw = header(Meshtastic.BROADCAST, 0xdeadbeef.toInt(), 0x12345678, 3, 3, 0x08) + ct
        val p = Meshtastic.decode(raw)!!
        assertEquals("LongFast", p.channel?.name)
        assertEquals(Meshtastic.PORT_TEXT, p.data!!.port)
        assertEquals("hi", String(p.data!!.payload))
        assertEquals("!deadbeef", Meshtastic.nodeId(p.header.from))
        assertEquals(0, p.header.hops)
    }

    @Test fun applications() {
        val pos = W().apply { fixed(1, 488_566_000); fixed(2, 23_522_000); u(3, 35); fixed(4, 1_760_000_000); u(19, 9); u(23, 32) }.done()
        val p = Meshtastic.decode(packet(0x1234abcd, 7, Meshtastic.PORT_POSITION, pos))!!
        val position = Meshtastic.position(p.data!!.payload)!!
        assertEquals(48.8566, position.lat!!, 1e-6)
        assertEquals(2.3522, position.lon!!, 1e-6)
        assertEquals(35, position.altitudeM)
        assertEquals(9, position.sats)

        val user = W().apply { str(1, "!1234abcd"); str(2, "Station Paris"); str(3, "PAR"); u(5, 43); u(7, 2); bytes(8, ByteArray(32) { 1 }) }.done()
        val u = Meshtastic.user(Meshtastic.decode(packet(0x1234abcd, 8, Meshtastic.PORT_NODEINFO, user))!!.data!!.payload)!!
        assertEquals("Station Paris", u.longName)
        assertEquals("PAR", u.shortName)
        assertEquals("HELTEC_V3", Meshtastic.hardwareName(u.hwModel!!))
        assertEquals("ROUTER", Meshtastic.roleName(u.role!!))
        assertTrue(u.hasPublicKey)

        val dev = W().apply { u(1, 87); float(2, 4.05f); float(3, 12.5f); float(4, 1.25f); u(5, 3600) }.done()
        val env = W().apply { float(1, 21.5f); float(2, 55f); float(3, 1013.2f) }.done()
        val t1 = Meshtastic.telemetry(W().apply { fixed(1, 1); bytes(2, dev) }.done())!!
        assertEquals(87, t1.battery)
        assertEquals(4.05f, t1.voltage!!, 1e-4f)
        assertEquals(3600, t1.uptimeS)
        val t2 = Meshtastic.telemetry(W().apply { fixed(1, 1); bytes(3, env) }.done())!!
        assertEquals(21.5f, t2.temperature!!, 1e-4f)
        assertEquals(1013.2f, t2.pressure!!, 1e-3f)

        val route = W().apply { fixed(1, 0x11); fixed(1, 0x22); fixed(3, 0x33) }.done()
        assertEquals(Meshtastic.Route(listOf(0x11, 0x22), listOf(0x33)), Meshtastic.route(route))
    }

    @Test fun privateTrafficStaysClosed() {
        // A channel with a key of its own: the header is read, the content is not.
        val secret = ByteArray(16) { (it * 7 + 3).toByte() }
        val data = W().apply { u(1, 1); str(2, "secret") }.done()
        val h = Meshtastic.channelHash("Famille", secret)
        val raw = header(Meshtastic.BROADCAST, 0x42, 99, 3, 3, h) + Meshtastic.crypt(secret, 0x42, 99, data)
        val p = Meshtastic.decode(raw)!!
        assertNull(p.data)
        assertEquals(0x42, p.header.from)
        // A direct message under PKI: channel hash 0, addressed to one node.
        val dm = Meshtastic.decode(header(0x77, 0x42, 100, 3, 3, 0) + ByteArray(30) { 5 })!!
        assertTrue(dm.pki)
        assertNull(Meshtastic.decode(ByteArray(10)))
    }

    /** LongFast at 869.525 MHz and NarrowSlow at 869.442 MHz, each through the HackRF chain of the EU 868 band. */
    @Test fun hearsThroughTheHackRf() {
        val band = Meshtastic.EU868
        val rate = MeshRadio.SAMPLE_RATE.toDouble()
        val cases = listOf(Triple(869_525_000L, 250_000.0, 11), Triple(869_441_650L, 62_500.0, 8), Triple(869_587_500L, 125_000.0, 11))
        for ((hz, bw, sf) in cases) {
            val raw = packet(0x0badcafe, hz.toInt(), Meshtastic.PORT_TEXT, "Salut depuis la radio".toByteArray())
            val ldro = LoraPhy.lowDataRate(sf, bw)
            val sym = LoraTx.symbols(raw, sf, 1, ldro = ldro)
            val cfo = 8_000.0
            val (re, im) = LoraTx.waveform(
                sym, sf, bw, rate, syncWord = Meshtastic.SYNC_WORD, preamble = 16, delayChips = 911.3, cfoHz = cfo,
                ppm = cfo / hz * 1e6, mixHz = (hz - band.loHz).toDouble(), amplitude = 30.0,
            )
            val rnd = Random(hz)
            val buf = ByteArray(2 * re.size)
            for (i in re.indices) {
                buf[2 * i] = (re[i] + 5 * rnd.nextGaussian()).roundToInt().coerceIn(-127, 127).toByte()
                buf[2 * i + 1] = (im[i] + 5 * rnd.nextGaussian()).roundToInt().coerceIn(-127, 127).toByte()
            }
            val got = ArrayList<MeshFrame>()
            val radio = MeshRadio(band) { got += it }
            var i = 0
            while (i < buf.size) { val k = minOf(262144, buf.size - i); radio.feed(buf.copyOfRange(i, i + k), k); i += k }
            radio.close()
            val ok = got.filter { it.frame.crcOk }
            assertEquals("$hz", 1, ok.size)
            assertEquals("$hz", hz, ok[0].hz)
            assertEquals("$hz", sf, ok[0].sf)
            assertEquals("$hz", Meshtastic.SYNC_WORD, ok[0].frame.syncWord)
            val p = Meshtastic.decode(ok[0].frame.payload)
            assertNotNull("$hz", p?.data)
            assertEquals("$hz", "Salut depuis la radio", String(p!!.data!!.payload))
        }
    }

    /** EU 433: the full 2 MS/s around 433.5 MHz, LongFast on its slot 433.875 MHz. */
    @Test fun hearsOn433() {
        val band = Meshtastic.EU433
        val raw = packet(0x01020304, 55, Meshtastic.PORT_TEXT, "433".toByteArray())
        val sym = LoraTx.symbols(raw, 11, 1)
        val (re, im) = LoraTx.waveform(sym, 11, 250_000.0, 2_000_000.0, syncWord = 0x2B, preamble = 16, delayChips = 100.0, cfoHz = -3_000.0, ppm = -7.0, mixHz = 375_000.0, amplitude = 30.0)
        val rnd = Random(2)
        val buf = ByteArray(2 * re.size)
        for (i in re.indices) {
            buf[2 * i] = (re[i] + 5 * rnd.nextGaussian()).roundToInt().coerceIn(-127, 127).toByte()
            buf[2 * i + 1] = (im[i] + 5 * rnd.nextGaussian()).roundToInt().coerceIn(-127, 127).toByte()
        }
        val got = ArrayList<MeshFrame>()
        val radio = MeshRadio(band) { got += it }
        radio.feed(buf, buf.size)
        radio.close()
        val ok = got.filter { it.frame.crcOk }
        assertEquals(1, ok.size)
        assertEquals(433_875_000L, ok[0].hz)
        assertEquals("433", String(Meshtastic.decode(ok[0].frame.payload)!!.data!!.payload))
    }

    /** A free frequency: the US 915 MHz band and others, each preset through the HackRF chain. */
    @Test fun hearsOnAFreeFrequency() {
        for ((hz, name) in listOf(906_875_000L to "LongFast", 915_000_000L to "ShortTurbo", 902_125_000L to "LongSlow", 869_525_000L to "MediumFast", 470_300_000L to "LongTurbo")) {
            val preset = Meshtastic.PRESETS.first { it.name == name }
            val band = Meshtastic.custom(hz, preset)
            val raw = packet(0x0badcafe, 99, Meshtastic.PORT_TEXT, "free $name".toByteArray())
            val ldro = LoraPhy.lowDataRate(preset.sf, preset.bwHz)
            val cr = if (name == "LongTurbo" || name == "LongSlow" || name == "LongModerate") 4 else 1
            val sym = LoraTx.symbols(raw, preset.sf, cr, ldro = ldro)
            val (re, im) = LoraTx.waveform(
                sym, preset.sf, preset.bwHz, MeshRadio.SAMPLE_RATE.toDouble(), syncWord = Meshtastic.SYNC_WORD, preamble = 16, delayChips = 400.0, cfoHz = 5_000.0,
                ppm = 5_000.0 / hz * 1e6, mixHz = (hz - band.loHz).toDouble(), amplitude = 30.0,
            )
            val rnd = Random(hz)
            val buf = ByteArray(2 * re.size)
            for (i in re.indices) {
                buf[2 * i] = (re[i] + 5 * rnd.nextGaussian()).roundToInt().coerceIn(-127, 127).toByte()
                buf[2 * i + 1] = (im[i] + 5 * rnd.nextGaussian()).roundToInt().coerceIn(-127, 127).toByte()
            }
            val got = ArrayList<MeshFrame>()
            val radio = MeshRadio(band) { got += it }
            var i = 0
            while (i < buf.size) { val k = minOf(262144, buf.size - i); radio.feed(buf.copyOfRange(i, i + k), k); i += k }
            radio.close()
            val ok = got.filter { it.frame.crcOk }
            assertEquals("$name at $hz", 1, ok.size)
            assertEquals("$name", name.replace("LongSlow", "LongSlow"), Meshtastic.presetFor(ok[0].sf, ok[0].bwHz)?.replace("LongMod", "LongModerate"))
            assertEquals("free $name", String(Meshtastic.decode(ok[0].frame.payload)!!.data!!.payload))
        }
    }
}
