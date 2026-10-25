package com.allnetworktools

import com.allnetworktools.data.net.CfBucketView
import com.allnetworktools.data.net.CfClock
import com.allnetworktools.data.net.CfConfig
import com.allnetworktools.data.net.CfEngine
import com.allnetworktools.data.net.CfExperience
import com.allnetworktools.data.net.CfHttp
import com.allnetworktools.data.net.CfMath
import com.allnetworktools.data.net.CfMeta
import com.allnetworktools.data.net.CfPacketLoss
import com.allnetworktools.data.net.CfRaw
import com.allnetworktools.data.net.CfScore
import com.allnetworktools.data.net.CfSnapshot
import com.allnetworktools.data.net.CfStep
import com.allnetworktools.data.net.CfTiming
import com.allnetworktools.data.net.CfTransport
import com.allnetworktools.data.net.Stun
import com.allnetworktools.data.net.TurnClient
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Collections
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedTestTest {
    @Test fun percentileAndJitterMatchTheSite() {
        assertEquals(3.7, CfMath.percentile(listOf(4.0, 1.0, 3.0, 2.0), 0.9), 1e-9)
        assertEquals(2.5, CfMath.percentile(listOf(1.0, 2.0, 3.0, 4.0), 0.5), 1e-9)
        assertEquals(0.0, CfMath.percentile(emptyList(), 0.5), 0.0)
        assertEquals(2.0, CfMath.jitter(listOf(10.0, 12.0, 10.0))!!, 1e-9)
        assertNull(CfMath.jitter(listOf(10.0)))
    }

    @Test fun serverTimingAndPing() {
        assertEquals(76.0, CfClock.serverTime("cfSpeedEdge;dur=4, cfSpeedWorker;dur=72, cfL4;desc=\"?proto=TCP&rtt=9513\"")!!, 1e-9)
        assertEquals(12.5, CfClock.serverTime("cfRequestDuration;dur=12.5, cfSpeedEdge;dur=4")!!, 1e-9)
        assertNull(CfClock.serverTime("cfL4;desc=x"))
        val clock = CfClock()
        // 30 ms to first byte of which 20 ms in the server: 10 ms ping; 1 MB of body in 80 ms → 90 ms, 89.3 Mb/s with headers.
        val t = clock.timing(CfRaw(30.0, 80.0, 0, 0.0, "cfSpeedEdge;dur=5, cfSpeedWorker;dur=15"), true, 1_000_000, 0)
        assertEquals(10.0, t.ping, 1e-9)
        assertEquals(90.0, t.duration, 1e-9)
        assertEquals(8 * 1_005_000 / 0.09, t.bps!!, 1.0)
        // Upload: the whole time to first byte is the transfer.
        val u = clock.timing(CfRaw(200.0, 1.0, 300, 0.0, null), false, 1_000_000, 0)
        assertEquals(200.0, u.duration, 1e-9)
        // A new HTTP/1.1 connection calibrates the server-time gap: 30 − 10 (handshake) − 15 = 5 ms, weighted 75 %.
        clock.timing(CfRaw(30.0, 1.0, 300, 10.0, "cfSpeedWorker;dur=15"), true, 0, 0)
        assertEquals(3.75, clock.serverTimeDelta, 1e-9)
    }

    @Test fun aimScores() {
        val s = CfMath.scores(100e6, 50e6, 15.0, 2.0, 40.0, 30.0, 0.0)
        assertEquals(CfScore(55, 3), s[CfExperience.Streaming])
        assertEquals(CfScore(25, 3), s[CfExperience.Gaming])
        assertEquals(CfScore(35, 3), s[CfExperience.Rtc])
        // A very lossy, slow line.
        val bad = CfMath.scores(0.5e6, 0.2e6, 600.0, 150.0, 900.0, 900.0, 0.6)
        assertEquals(0, bad.getValue(CfExperience.Gaming).level)
        // No loaded latency at all: the experiences that need it cannot be scored.
        assertTrue(CfMath.scores(100e6, 50e6, 15.0, 2.0, 0.0, 0.0, 0.0).isEmpty())
    }

    /** A line with [downMbps] / [upMbps] and a 10 ms round trip; every request is answered instantly. */
    private class FakeLine(val downMbps: Double, val upMbps: Double) : CfTransport {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        override fun exchange(lane: Int, down: Boolean, bytes: Long, during: String?): CfRaw {
            if (lane != 1) requests += (if (down) "d" else "u") + bytes
            val payload = if (down) bytes * 8 / (downMbps * 1e3) else 0.0
            val ttfb = 10.0 + if (down) 0.0 else bytes * 8 / (upMbps * 1e3)
            return CfRaw(ttfb, payload, bytes, 0.0, "cfSpeedWorker;dur=0")
        }
        override fun abort(lane: Int) = Unit
    }

    @Test fun planStopsEachDirectionOnceRequestsTakeOverASecond() = runBlocking {
        val line = FakeLine(downMbps = 100.0, upMbps = 50.0)
        var lossStep: CfStep.PacketLoss? = null
        val engine = CfEngine(line, { step, _ -> lossStep = step; CfPacketLoss(step.packets, step.packets, step.packets, emptyList()) })
        var last: CfSnapshot? = null
        engine.run { last = it }
        val s = last!!
        assertTrue(s.finished)
        val transfers = line.requests.filter { it != "d0" }.distinct()
        // Download: 25 MB at 100 Mb/s takes 2 s, so 100 MB and 250 MB never run; upload stops after 10 MB (1.6 s).
        assertEquals(listOf("d100000", "d1000000", "u100000", "u1000000", "d10000000", "u10000000", "d25000000"), transfers)
        assertEquals(1 + 9 + 8 + 6 + 4, line.requests.count { it.startsWith("d") && it != "d0" })
        assertEquals(2 + 20 + 2 * 14, s.latencyPoints.size)
        assertEquals(10.0, s.latency!!, 1e-9)
        assertEquals(100e6, s.download!!, 1e6)
        assertEquals(50e6 * 1.005, s.upload!!, 1e6)
        assertEquals(1000, lossStep!!.packets)
        assertEquals(0.0, s.packetLoss!!.ratio, 0.0)
        assertNotNull(s.scores)
        assertEquals(listOf(100_000L, 1_000_000L, 10_000_000L, 25_000_000L), s.downBuckets.map { it.bytes })
        assertEquals(10, s.downBuckets[0].planned)
    }

    /** A fast line ends on 500 MB then 1 GB each way, each over six connections at once; a slower one or a metered network skips them. */
    @Test fun fastLineFinishesOnBigFilesInParallel() = runBlocking {
        val fast = FakeLine(downMbps = 900.0, upMbps = 300.0)
        var last: CfSnapshot? = null
        CfEngine(fast, { step, _ -> CfPacketLoss(step.packets, step.packets, step.packets, emptyList()) }).run { last = it }
        assertEquals(6, fast.requests.count { it == "d83333333" })
        assertEquals(6, fast.requests.count { it == "d166666666" })
        assertEquals(6, fast.requests.count { it == "u83333333" })
        assertEquals(6, fast.requests.count { it == "u166666666" })
        assertEquals("u166666666", fast.requests.last { it != "d0" })
        // Six connections at 900 Mbit/s each, added up when the transport has no live byte count.
        assertEquals(5.4e9, last!!.download!!, 0.1e9)
        assertTrue(last!!.downBuckets.last().bytes == 1_000_000_000L)
        assertEquals(1.8e9, last!!.upload!!, 0.1e9)

        val metered = FakeLine(downMbps = 900.0, upMbps = 300.0)
        CfEngine(metered, { step, _ -> CfPacketLoss(step.packets, step.packets, step.packets, emptyList()) }, bigFiles = false).run { }
        assertTrue(metered.requests.none { it.endsWith("83333333") || it.endsWith("166666666") })

        // 1 GB at 150 Mbit/s would take 53 s: only the 500 MB file runs, and then 1 GB at 900 Mbit/s fits.
        val mid = FakeLine(downMbps = 150.0, upMbps = 50.0)
        CfEngine(mid, { step, _ -> CfPacketLoss(step.packets, step.packets, step.packets, emptyList()) }).run { }
        assertEquals(6, mid.requests.count { it == "d83333333" })

        val slow = FakeLine(downMbps = 50.0, upMbps = 20.0)
        CfEngine(slow, { step, _ -> CfPacketLoss(step.packets, step.packets, step.packets, emptyList()) }).run { }
        assertTrue(slow.requests.none { it.endsWith("83333333") || it.endsWith("166666666") })
    }

    /** Parallel speed: the bytes from 10 % to 90 % of the total, over that time. */
    @Test fun parallelWindowRate() {
        val ms = 1_000_000L
        // 100 MB/s for 4 s, then a slow tail of 10 MB/s that is left out.
        val samples = (0..40).map { i -> i * 100 * ms to i * 10_000_000L } + (1..10).map { i -> (4000 + i * 100) * ms to 400_000_000L + i * 1_000_000L }
        assertEquals(800e6, CfMath.windowRate(samples, 410_000_000)!!, 1e6)
        assertNull(CfMath.windowRate(samples.take(12), 100_000_000))
        assertNull(CfMath.windowRate(emptyList(), 400_000_000))
    }

    @Test fun packetLossThroughTurnRelays() = runBlocking {
        FakeTurn(user = "u", pass = "p", dropEvery = 10).use { turn ->
            val step = CfStep.PacketLoss(200, batchSize = 10, batchWaitMs = 1, responsesWaitMs = 300)
            val r = CfHttp.runPacketLoss(step, TurnClient(turn.address, "u", "p"), TurnClient(turn.address, "u", "p")) {}
            assertEquals(200, r.sent)
            assertEquals(180, r.received)
            assertEquals((1..20).map { it * 10 }, r.lost)
            assertEquals(0.1, r.ratio, 1e-9)
            assertTrue("allocations were authenticated", turn.authenticated >= 2)
        }
    }

    @Test(expected = java.io.IOException::class)
    fun wrongTurnPasswordFails() = runBlocking {
        FakeTurn(user = "u", pass = "p", dropEvery = 0).use { turn ->
            CfHttp.runPacketLoss(CfStep.PacketLoss(10, 10, 1, 100), TurnClient(turn.address, "u", "nope"), TurnClient(turn.address, "u", "nope")) {}
        }
        Unit
    }

    @Test fun csvLikeTheSite() {
        fun t(bytes: Long, ping: Double, at: Long) = CfTiming(bytes, ping + 2, 5.5, 2.0, bytes + 412, ping, ping + 5.5, 1.25e8, at)
        val s = CfSnapshot.Empty.copy(
            downBuckets = listOf(CfBucketView(1_000_000, 2, listOf(t(1_000_000, 10.0, 1000), t(1_000_000, 11.5, 2000)), listOf(t(0, 30.0, 900), t(0, 31.0, 1500), t(0, 32.0, 2500)))),
            upBuckets = listOf(CfBucketView(100_000, 1, listOf(t(100_000, 12.0, 1700)))),
        )
        assertEquals(
            listOf(
                "time,direction,bytes,latency,bps,duration,serverTime,responseSize,loadedLatencies",
                "1000,download,1000000,10,125000000,15.5,2,1000412,30",
                "1700,upload,100000,12,125000000,17.5,2,100412,",
                "2000,download,1000000,11.5,125000000,17,2,1000412,31 32",
            ),
            com.allnetworktools.data.net.CfCsv.format(s).split("\n"),
        )
    }

    @Test fun stunFingerprintAndAddresses() {
        val tid = ByteArray(12) { it.toByte() }
        val addr = InetSocketAddress(InetAddress.getByName("192.0.2.1"), 32853)
        val msg = Stun.encode(0x0101, tid, listOf(0x0020 to Stun.xorAddress(addr, tid)))
        val m = Stun.decode(msg)!!
        assertEquals(addr, Stun.xorAddress(m[0x0020]!!, tid))
        // RFC 8489 §14.7: CRC-32 of the message up to the attribute, XOR 0x5354554E.
        val crc = java.util.zip.CRC32().apply { update(msg, 0, msg.size - 8) }.value.toInt() xor 0x5354554E
        assertEquals(crc, ByteBuffer.wrap(m[Stun.A_FINGERPRINT]!!).int)
    }
}

/** Data for the speed test screenshots: a finished run on a 840/610 Mb/s line. */
object SpeedFake {
    val meta = CfMeta("203.0.113.42", 12322, "Free SAS", "Lyon", "Auvergne-Rhône-Alpes", "FR", 45.76, 4.84, "MRS", "Marseille", "FR", 43.44, 5.22)

    fun finished(): CfSnapshot {
        val rnd = java.util.Random(3)
        var t = 0L
        fun bucket(bytes: Long, n: Int, mbps: Double) = CfBucketView(bytes, n, List(n) {
            t += 300
            val bps = mbps * 1e6 * (0.85 + 0.2 * rnd.nextDouble())
            CfTiming(bytes, 12.0, bytes * 8 / bps * 1000, 2.0, bytes, 10.0, bytes * 8 / bps * 1000 + 10, bps, t)
        })
        val down = listOf(bucket(100_000, 10, 120.0), bucket(1_000_000, 8, 420.0), bucket(10_000_000, 6, 760.0), bucket(25_000_000, 4, 810.0), bucket(100_000_000, 3, 840.0))
        val up = listOf(bucket(100_000, 8, 90.0), bucket(1_000_000, 6, 330.0), bucket(10_000_000, 4, 560.0), bucket(25_000_000, 4, 600.0), bucket(50_000_000, 3, 610.0))
        val idle = List(44) { 6.0 + rnd.nextDouble() * 2 }
        val dl = List(20) { 14.0 + rnd.nextDouble() * 8 }
        val ul = List(20) { 22.0 + rnd.nextDouble() * 10 }
        val loss = CfPacketLoss(1000, 1000, 1000, emptyList())
        return CfSnapshot(
            stepIndex = CfConfig.steps.size, stepDone = 0, finished = true,
            download = 842e6, upload = 612e6, latency = CfMath.percentile(idle, 0.5), jitter = CfMath.jitter(idle),
            downLoadedLatency = CfMath.percentile(dl, 0.5), downLoadedJitter = CfMath.jitter(dl),
            upLoadedLatency = CfMath.percentile(ul, 0.5), upLoadedJitter = CfMath.jitter(ul),
            packetLoss = loss, packetLossLive = loss, packetLossError = null,
            latencyPoints = idle, downLoadedPoints = dl, upLoadedPoints = ul,
            downBuckets = down, upBuckets = up,
            scores = CfMath.scores(842e6, 612e6, CfMath.percentile(idle, 0.5), CfMath.jitter(idle), CfMath.percentile(dl, 0.5), CfMath.percentile(ul, 0.5), 0.0),
            durationMs = 41_000,
        )
    }
}

/**
 * A TURN server on localhost, just enough of RFC 8656: long-term-credential Allocate (401 first), CreatePermission, Send
 * indications relayed from the allocation's own UDP port, Data indications back. Drops every [dropEvery]th relayed message.
 */
class FakeTurn(private val user: String, private val pass: String, private val dropEvery: Int) : AutoCloseable {
    private val socket = DatagramSocket(0, InetAddress.getLoopbackAddress())
    val address = InetSocketAddress(InetAddress.getLoopbackAddress(), socket.localPort)
    private val realm = "fake.turn"
    private val nonce = "n0nce"
    private val relays = Collections.synchronizedMap(mutableMapOf<InetSocketAddress, DatagramSocket>())
    @Volatile var authenticated = 0
    @Volatile private var open = true
    private var relayed = 0

    private val loop = thread(isDaemon = true) {
        val buf = ByteArray(2048)
        while (open) {
            val p = DatagramPacket(buf, buf.size)
            runCatching { socket.receive(p) }.onFailure { return@thread }
            val raw = buf.copyOf(p.length)
            val m = Stun.decode(raw) ?: continue
            val client = InetSocketAddress(p.address, p.port)
            when (m.type) {
                Stun.ALLOCATE or Stun.REQUEST, Stun.CREATE_PERMISSION or Stun.REQUEST -> {
                    if (!integrityOk(raw)) {
                        reply(client, (m.type and 0x3EEF) or Stun.ERROR, m.tid, listOf(Stun.A_ERROR_CODE to byteArrayOf(0, 0, 4, 1), Stun.A_REALM to realm.toByteArray(), Stun.A_NONCE to nonce.toByteArray()))
                        continue
                    }
                    if (m.type == Stun.ALLOCATE or Stun.REQUEST) {
                        authenticated++
                        val relay = DatagramSocket(0, InetAddress.getLoopbackAddress())
                        relays[client] = relay
                        thread(isDaemon = true) { pump(relay, client) }
                        val at = InetSocketAddress(InetAddress.getLoopbackAddress(), relay.localPort)
                        reply(client, Stun.ALLOCATE or Stun.SUCCESS, m.tid, listOf(Stun.A_XOR_RELAYED_ADDRESS to Stun.xorAddress(at, m.tid)), key())
                    } else {
                        reply(client, Stun.CREATE_PERMISSION or Stun.SUCCESS, m.tid, emptyList(), key())
                    }
                }
                Stun.SEND or Stun.INDICATION -> {
                    val relay = relays[client] ?: continue
                    val peer = Stun.xorAddress(m[Stun.A_XOR_PEER_ADDRESS]!!, m.tid)
                    val data = m[Stun.A_DATA]!!
                    relayed++
                    if (dropEvery > 0 && relayed % dropEvery == 0) continue
                    relay.send(DatagramPacket(data, data.size, peer))
                }
            }
        }
    }

    private fun pump(relay: DatagramSocket, client: InetSocketAddress) {
        val buf = ByteArray(2048)
        while (open) {
            val p = DatagramPacket(buf, buf.size)
            runCatching { relay.receive(p) }.onFailure { return }
            val tid = ByteArray(12).also { java.util.Random().nextBytes(it) }
            val from = InetSocketAddress(p.address, p.port)
            reply(client, Stun.DATA or Stun.INDICATION, tid, listOf(Stun.A_XOR_PEER_ADDRESS to Stun.xorAddress(from, tid), Stun.A_DATA to buf.copyOf(p.length)))
        }
    }

    private fun key() = MessageDigest.getInstance("MD5").digest("$user:$realm:$pass".toByteArray())

    /** Checks MESSAGE-INTEGRITY independently of the client's encoder: HMAC-SHA1 up to the attribute, length patched to end it. */
    private fun integrityOk(raw: ByteArray): Boolean {
        var p = 20
        while (p + 4 <= raw.size) {
            val t = ((raw[p].toInt() and 0xFF) shl 8) or (raw[p + 1].toInt() and 0xFF)
            val l = ((raw[p + 2].toInt() and 0xFF) shl 8) or (raw[p + 3].toInt() and 0xFF)
            if (t == Stun.A_MESSAGE_INTEGRITY) {
                val head = raw.copyOf(p)
                val len = p - 20 + 24
                head[2] = (len shr 8).toByte(); head[3] = len.toByte()
                val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key(), "HmacSHA1")) }.doFinal(head)
                return mac.contentEquals(raw.copyOfRange(p + 4, p + 24))
            }
            p += 4 + (l + 3) / 4 * 4
        }
        return false
    }

    private fun reply(to: InetSocketAddress, type: Int, tid: ByteArray, attrs: List<Pair<Int, ByteArray>>, key: ByteArray? = null) {
        val msg = Stun.encode(type, tid, attrs, key)
        runCatching { socket.send(DatagramPacket(msg, msg.size, to)) }
    }

    override fun close() {
        open = false
        socket.close()
        relays.values.forEach { it.close() }
        loop.join(1000)
    }
}
