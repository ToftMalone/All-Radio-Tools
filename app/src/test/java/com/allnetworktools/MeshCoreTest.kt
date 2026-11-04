package com.allnetworktools

import com.allnetworktools.data.sdr.LoraReceiver
import com.allnetworktools.data.sdr.MeshCore
import com.allnetworktools.data.sdr.MeshFrame
import com.allnetworktools.data.sdr.MeshRadio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.roundToInt

/** MeshCore packets captured on the air, from meshcore-decoder's tests (MIT), with what they are known to contain. */
class MeshCoreTest {
    private fun bytes(h: String) = h.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val advertHex = "11007E7662676F7F0850A8A355BAAFBFC1EB7B4174C340442D7D7161C9474A2C94006CE7CF682E58408DD8FCC51906ECA98EBF94A037886BDADE7ECD09FD92B839491DF3809C9454F5286D1D3370AC31A34593D569E9A042A3B41FD331DFFB7E18599CE1E60992A076D50238C5B8F85757375354522F50756765744D65736820436F75676172"

    @Test fun realAdvert() {
        val p = MeshCore.parse(bytes(advertHex))!!
        assertEquals(MeshCore.ROUTE_FLOOD, p.route)
        assertEquals(MeshCore.TYPE_ADVERT, p.type)
        assertEquals(0, p.path.size)
        val a = MeshCore.advert(p.payload)!!
        assertEquals("7e7662676f7f0850a8a355baafbfc1eb7b4174c340442d7d7161c9474a2c9400", a.keyHex)
        assertEquals(1758455660L, a.timestampS)
        assertEquals(MeshCore.NODE_REPEATER, a.nodeType)
        assertEquals(47.543968, a.lat!!, 1e-6)
        assertEquals(-122.108616, a.lon!!, 1e-6)
        assertEquals("WW7STR/PugetMesh Cougar", a.name)
        // Ed25519 is in the JDK the tests run on; Android has it from version 13.
        assertEquals(true, MeshCore.verify(a))
        val tampered = bytes(advertHex).also { it[it.size - 1] = 'X'.code.toByte() }
        assertEquals(false, MeshCore.verify(MeshCore.advert(MeshCore.parse(tampered)!!.payload)!!))
    }

    @Test fun realPublicChannelMessage() {
        val p = MeshCore.parse(bytes("150011C3C1354D619BAE9590E4D177DB7EEAF982F5BDCF78005D75157D9535FA90178F785D"))!!
        assertEquals(MeshCore.TYPE_GRP_TXT, p.type)
        assertEquals(0x11, MeshCore.PUBLIC.hash)
        val t = MeshCore.groupText(p.payload, listOf(MeshCore.PUBLIC))!!
        assertEquals("Public", t.channel.name)
        assertEquals("🌲 Tree", t.sender)
        assertEquals("☁️", t.text)
        assertEquals(1758484279L, t.timestampS)
        // Without the key it stays closed.
        assertNull(MeshCore.groupText(p.payload, listOf(MeshCore.hashtag("bot"))))
    }

    @Test fun hashtagKeysAndLongPaths() {
        assertEquals("9cd8fcf22a47333b591d96a2b848b73f", MeshCore.hex(MeshCore.hashtag("#test").secret))
        assertEquals("eb50a1bcb3e4e5d7bf69a57c9dada211", MeshCore.hex(MeshCore.hashtag("bot").secret))
        val bot = listOf(MeshCore.PUBLIC, MeshCore.hashtag("bot"))
        val three = MeshCore.parse(bytes("15833fa002860ccae0eed9ca78b9ab0775d477c1f6490a398bf4edc75240"))!!
        assertEquals(3, three.hashSize)
        assertEquals(listOf("3fa002", "860cca", "e0eed9"), three.path.map { MeshCore.hex(it) })
        val t3 = MeshCore.groupText(three.payload, bot)!!
        assertEquals("#bot", t3.channel.name)
        assertEquals("Roy B V4", t3.sender)
        assertEquals("P", t3.text)
        val two = MeshCore.parse(bytes("1540cab3b15626481a5ba64247ab25766e410b026e0678a32da9f0c3946fae5b714cab170f"))!!
        assertEquals(2, two.hashSize)
        assertEquals(0, two.path.size)
        val t2 = MeshCore.groupText(two.payload, bot)!!
        assertEquals("Howl 👾", t2.sender)
        assertEquals("prefix 0101", t2.text)
    }

    @Test fun rejectsWhatIsNotMeshCore() {
        assertNull(MeshCore.parse(bytes("C1")))
        // Version 2 header, reserved path hash size, path running past the end.
        assertNull(MeshCore.parse(bytes("D100AABBCC")))
        assertNull(MeshCore.parse(bytes("11C0AABBCC")))
        assertNull(MeshCore.parse(bytes("1105AABB")))
    }

    /** The advert as a radio would send it at the default 869.618 MHz, 62.5 kHz, SF8, through the HackRF chain. */
    @Test fun hearsAnAdvertThroughTheHackRf() {
        val payload = bytes(advertHex)
        val band = MeshCore.EU868
        val sf = 8
        val bw = 62_500.0
        val sym = LoraTx.symbols(payload, sf, 4)
        val rate = MeshRadio.SAMPLE_RATE.toDouble()
        val (re, im) = LoraTx.waveform(
            sym, sf, bw, rate, syncWord = MeshCore.SYNC_WORD, preamble = 16, delayChips = 300.4, cfoHz = -6_500.0,
            ppm = -6_500.0 / 869.618e6 * 1e6, mixHz = (869_618_000L - band.loHz).toDouble(), amplitude = 30.0,
        )
        val rnd = Random(4)
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
        assertEquals(1, ok.size)
        assertEquals(869_618_000L, ok[0].hz)
        assertEquals(8, ok[0].sf)
        assertEquals(MeshCore.SYNC_WORD, ok[0].frame.syncWord)
        assertNotNull(MeshCore.advert(MeshCore.parse(ok[0].frame.payload)!!.payload))
        assertTrue(LoraReceiver.OS == 2)
    }
}
