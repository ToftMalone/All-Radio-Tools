package com.allnetworktools

import com.allnetworktools.data.sdr.LoraFrame
import com.allnetworktools.data.sdr.MeshCore
import com.allnetworktools.data.sdr.MeshCoreTracker
import com.allnetworktools.data.sdr.MeshFrame
import com.allnetworktools.data.sdr.Meshtastic
import com.allnetworktools.data.sdr.MeshtasticTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshTrackerTest {
    private fun frame(p: ByteArray, sync: Int, snr: Double = 5.0) =
        MeshFrame(869_525_000L, 250_000.0, 11, LoraFrame(p, true, true, 1, snr, 0.0, sync))

    private fun pb(vararg parts: ByteArray) = parts.fold(ByteArray(0)) { a, b -> a + b }
    private fun bytes(field: Int, b: ByteArray) = byteArrayOf(((field shl 3) or 2).toByte(), b.size.toByte()) + b
    private fun str(field: Int, s: String) = bytes(field, s.toByteArray())
    private fun varint(field: Int, v: Int) = byteArrayOf((field shl 3).toByte(), v.toByte())

    private fun mt(from: Int, id: Int, hopLimit: Int, data: ByteArray): ByteArray {
        val h = ByteArray(16)
        for (i in 0 until 4) { h[i] = 0xFF.toByte(); h[4 + i] = (from ushr (8 * i)).toByte(); h[8 + i] = (id ushr (8 * i)).toByte() }
        h[12] = (hopLimit or (3 shl 5)).toByte()
        h[13] = 0x08
        return h + Meshtastic.crypt(Meshtastic.DEFAULT_PSK, from, id, data)
    }

    @Test fun meshtasticNodesAndRelayedCopies() {
        val t = MeshtasticTracker()
        val user = pb(str(1, "!00c0ffee"), str(2, "Relais du Mont"), str(3, "MONT"), varint(5, 9), varint(7, 2))
        t.update(frame(mt(0x00c0ffee, 1, 3, pb(varint(1, 4), bytes(2, user))), 0x2B), 1000)
        val text = pb(varint(1, 1), str(2, "Bonjour le maillage"))
        // The same message heard directly, then relayed with one hop more.
        t.update(frame(mt(0x00c0ffee, 2, 3, text), 0x2B, snr = 8.0), 2000)
        t.update(frame(mt(0x00c0ffee, 2, 2, text), 0x2B, snr = -3.0), 2500)
        val s = t.snapshot(3000)
        assertEquals(1, s.nodes.size)
        val n = s.nodes[0]
        assertEquals("Relais du Mont", n.longName)
        assertEquals("MONT", n.shortName)
        assertEquals("RAK4631", n.hardware)
        assertEquals("ROUTER", n.role)
        assertEquals(2, n.packets)
        assertEquals(0, n.hops)
        assertEquals(1, s.messages.size)
        assertEquals("Bonjour le maillage", s.messages[0].text)
        assertEquals("Relais du Mont", s.messages[0].fromName)
        assertEquals(2, s.messages[0].copies)
        assertEquals("LongFast", s.messages[0].channel)
        assertEquals(2, s.total)
        assertEquals(2, s.readable)
    }

    @Test fun meshcoreIgnoresOtherNetworks() {
        val t = MeshCoreTracker()
        val advert = "11007E7662676F7F0850A8A355BAAFBFC1EB7B4174C340442D7D7161C9474A2C94006CE7CF682E58408DD8FCC51906ECA98EBF94A037886BDADE7ECD09FD92B839491DF3809C9454F5286D1D3370AC31A34593D569E9A042A3B41FD331DFFB7E18599CE1E60992A076D50238C5B8F85757375354522F50756765744D65736820436F75676172"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        t.update(frame(advert, MeshCore.SYNC_WORD), 1000)
        // A Meshtastic frame on the shared 250 kHz channel: another sync word, not MeshCore's.
        t.update(frame(advert, Meshtastic.SYNC_WORD), 1100)
        // The same advert repeated by a repeater: one packet, heard again.
        t.update(frame(advert, MeshCore.SYNC_WORD), 1200)
        val s = t.snapshot(2000)
        assertEquals(1, s.nodes.size)
        assertEquals("WW7STR/PugetMesh Cougar", s.nodes[0].longName)
        assertEquals("REPEATER", s.nodes[0].role)
        assertEquals(true, s.nodes[0].verified)
        assertEquals(1, s.total)
        assertTrue(s.packets.size == 1)
    }
}
