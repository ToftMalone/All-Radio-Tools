package com.allnetworktools.data.net

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.json.JSONObject

/*
 * speed.cloudflare.com, measured the way the site measures it: the site runs Cloudflare's open-source engine
 * (@cloudflare/speedtest 1.14) with its default configuration, and this file ports that engine step for step — the
 * same 25-step plan of latency, download, upload and packet-loss rounds against the same endpoints, the same rules
 * for stopping a direction early, the same per-request timing (time to first byte minus the server's own time) and
 * the same results maths (90th percentile bandwidth, median latency, jitter, loaded latency, AIM scores).
 */

sealed interface CfStep {
    data class Latency(val packets: Int) : CfStep
    /**
     * [final]: a big download that runs even after the ladder stopped, so a fast line can show its full speed; skipped
     * when big files are off (metered network) or when it would take more than [CfConfig.FINAL_MAX_SECONDS]. It is
     * split over [streams] connections at once: one TCP connection alone tops out around 600 Mbit/s on a phone.
     */
    data class Transfer(
        val down: Boolean,
        val bytes: Long,
        val count: Int,
        val bypassMinDuration: Boolean = false,
        val final: Boolean = false,
        val streams: Int = 1,
    ) : CfStep
    data class PacketLoss(val packets: Int, val batchSize: Int, val batchWaitMs: Long, val responsesWaitMs: Long, val connectionTimeoutMs: Long = 5000) : CfStep
}

object CfConfig {
    const val HOST = "speed.cloudflare.com"
    /** The site overrides the engine's default relay (turn.speed.cloudflare.com:50000) with this one. */
    const val TURN_URI = "turn.cloudflare.com:3478"

    val steps: List<CfStep> = listOf(
        CfStep.Latency(2), // initial ttfb estimation
        CfStep.Transfer(true, 100_000, 1, bypassMinDuration = true), // initial download estimation
        CfStep.Latency(20),
        CfStep.Transfer(true, 100_000, 9),
        CfStep.Latency(2),
        CfStep.Transfer(true, 1_000_000, 8),
        CfStep.Latency(2),
        CfStep.Transfer(false, 100_000, 8),
        CfStep.Latency(2),
        CfStep.PacketLoss(1000, batchSize = 10, batchWaitMs = 10, responsesWaitMs = 3000),
        CfStep.Transfer(false, 1_000_000, 6),
        CfStep.Latency(2),
        CfStep.Transfer(true, 10_000_000, 6),
        CfStep.Latency(2),
        CfStep.Transfer(false, 10_000_000, 4),
        CfStep.Latency(2),
        CfStep.Transfer(true, 25_000_000, 4),
        CfStep.Latency(2),
        CfStep.Transfer(false, 25_000_000, 4),
        CfStep.Latency(2),
        CfStep.Transfer(true, 100_000_000, 3),
        CfStep.Latency(2),
        CfStep.Transfer(false, 50_000_000, 3),
        CfStep.Latency(2),
        CfStep.Transfer(true, 250_000_000, 2),
        // Beyond the site: one connection caps out near 600 Mbit/s, so finish on big files over parallel connections.
        CfStep.Latency(2),
        CfStep.Transfer(true, 500_000_000, 1, final = true, streams = CfConfig.FINAL_STREAMS),
        CfStep.Latency(2),
        CfStep.Transfer(true, 1_000_000_000, 1, final = true, streams = CfConfig.FINAL_STREAMS),
    )

    /** A final file that would take longer than this at the speed measured so far is skipped: it would add nothing. */
    const val FINAL_MAX_SECONDS = 40.0
    /** Parallel connections of the final downloads, as many as Ookla's app opens on a fast line. */
    const val FINAL_STREAMS = 6
    /** Their speed: the bytes from 10 % to 90 % of the total over that time, if it lasted this long. */
    const val FINAL_MIN_WINDOW_MS = 1000.0

    // The site's own overrides of the engine defaults (400 ms, 250 ms, 20 points).
    const val LOADED_LATENCY_THROTTLE_MS = 100L
    const val FINISH_REQUEST_DURATION = 1000.0
    const val ESTIMATED_SERVER_TIME = 0.0
    const val LATENCY_PERCENTILE = 0.5
    const val BANDWIDTH_PERCENTILE = 0.9
    const val BANDWIDTH_MIN_REQUEST_DURATION = 10.0
    const val LOADED_REQUEST_MIN_DURATION = 100.0
    const val LOADED_LATENCY_MAX_POINTS = 100
    const val MAX_RETRIES = 3
}

/** One request as the transport saw it: milliseconds, and bytes received (headers and body). */
class CfRaw(val ttfb: Double, val payloadMs: Double, val transferSize: Long, val connectMs: Double, val serverTiming: String?)

/** One request as the engine scores it. [ping] is the time to first byte minus the server's time; bps is null when unusable. */
data class CfTiming(
    val bytes: Long,
    val ttfb: Double,
    val payloadMs: Double,
    val serverTime: Double,
    val transferSize: Long,
    val ping: Double,
    val duration: Double,
    val bps: Double?,
    val atMs: Long,
)

class CfHttpException(val status: Int, val retryAfter: String?) : IOException("HTTP $status")

/**
 * Requests on lanes: 0 carries the measurements, 1 the latency pings sent while a transfer loads the line, and
 * [CfEngine.STREAM_LANE] onwards the parallel connections of the final downloads.
 */
interface CfTransport {
    fun exchange(lane: Int, down: Boolean, bytes: Long, during: String?): CfRaw
    /** Aborts whatever [lane] is doing, from another thread. */
    fun abort(lane: Int)
    /** Body bytes received so far on every lane, read live while requests run; 0 when the transport cannot tell. */
    fun received(): Long = 0
}

/** The engine's request timing, with its running estimate of the gap between the server's own time and what the client sees. */
class CfClock(var serverTimeDelta: Double = 0.0) {
    fun timing(raw: CfRaw, down: Boolean, bytes: Long, atMs: Long): CfTiming {
        val serverTime = serverTime(raw.serverTiming)
        // On a new HTTP/1.1 connection, the time to first byte minus the TCP handshake is the server's total time.
        if (serverTime != null && raw.connectMs > 0) {
            val delta = maxOf(0.0, raw.ttfb - raw.connectMs) - serverTime
            if (delta > 0 && delta <= 15 && delta <= serverTime && serverTime <= 150) {
                serverTimeDelta = serverTimeDelta * 0.25 + delta * 0.75
            }
        }
        val base = serverTime ?: CfConfig.ESTIMATED_SERVER_TIME
        var ping = raw.ttfb - base - serverTimeDelta
        if (ping <= 1) ping = maxOf(0.0, raw.ttfb - base)
        val duration = if (down) ping + raw.payloadMs else raw.ttfb
        val bits = if (down && raw.transferSize > 0) 8.0 * raw.transferSize else 8.0 * bytes * 1.005
        val bps = if (duration == 0.0) null else bits / (duration / 1000)
        return CfTiming(bytes, raw.ttfb, raw.payloadMs, serverTime ?: -1.0, raw.transferSize, ping, duration, bps, atMs)
    }

    companion object {
        private val reqDur = Regex("""(?:^|,\s*)cfReq(?:uest)?Dur(?:ation)?;\s*dur=([0-9.]+)""", RegexOption.IGNORE_CASE)
        private val speedDur = Regex("""(?:^|,\s*)cfSpeed[a-zA-Z]*;\s*dur=([0-9.]+)""", RegexOption.IGNORE_CASE)

        fun serverTime(header: String?): Double? {
            header ?: return null
            reqDur.find(header)?.groupValues?.get(1)?.toDoubleOrNull()?.let { if (it > 0.01) return it }
            val sum = speedDur.findAll(header).sumOf { it.groupValues[1].toDoubleOrNull() ?: 0.0 }
            return if (sum > 0.01) sum else null
        }
    }
}

enum class CfExperience { Streaming, Gaming, Rtc }

/** [level] 0 bad, 1 poor, 2 average, 3 good, 4 great. */
data class CfScore(val points: Int, val level: Int)

data class CfPacketLoss(val total: Int, val sent: Int, val received: Int, val lost: List<Int>) {
    val ratio: Double get() = if (sent == 0) 0.0 else lost.size.toDouble() / sent
}

/** [parallel]: one final download over several connections, whose single timing is their combined speed. */
class CfBucket(val bytes: Long, val parallel: Boolean = false) {
    val timings = mutableListOf<CfTiming>()
    val side = mutableListOf<CfTiming>()
    var planned = 0
}

data class CfBucketView(val bytes: Long, val planned: Int, val timings: List<CfTiming>, val side: List<CfTiming> = emptyList())

data class CfSnapshot(
    val stepIndex: Int,
    val stepDone: Int,
    val finished: Boolean,
    val download: Double?,
    val upload: Double?,
    val latency: Double?,
    val jitter: Double?,
    val downLoadedLatency: Double?,
    val downLoadedJitter: Double?,
    val upLoadedLatency: Double?,
    val upLoadedJitter: Double?,
    val packetLoss: CfPacketLoss?,
    val packetLossLive: CfPacketLoss?,
    val packetLossError: String?,
    val latencyPoints: List<Double>,
    val downLoadedPoints: List<Double>,
    val upLoadedPoints: List<Double>,
    val downBuckets: List<CfBucketView>,
    val upBuckets: List<CfBucketView>,
    /** Null until the test ends; an experience is missing when one of its inputs could not be measured. */
    val scores: Map<CfExperience, CfScore>?,
    val durationMs: Long,
) {
    val step: CfStep? get() = CfConfig.steps.getOrNull(stepIndex)
    val downPoints: List<CfTiming> get() = downBuckets.flatMap { it.timings }.sortedBy { it.atMs }
    val upPoints: List<CfTiming> get() = upBuckets.flatMap { it.timings }.sortedBy { it.atMs }

    companion object {
        val Empty = CfSnapshot(-1, 0, false, null, null, null, null, null, null, null, null, null, null, null, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), null, 0)
    }
}

/** The site's "download CSV": one row per download and upload request, with the loaded-latency pings sent during it. */
object CfCsv {
    fun format(s: CfSnapshot): String {
        class Row(val time: Long, val cells: List<String>)
        val rows = mutableListOf<Row>()
        listOf("download" to s.downBuckets, "upload" to s.upBuckets).forEach { (dir, buckets) ->
            buckets.sortedBy { it.bytes }.forEach { b ->
                // Each ping goes to the first request that ended after it, or the last one.
                val owner = b.side.map { p -> b.timings.indexOfFirst { it.atMs > p.atMs }.let { i -> if (i >= 0) i else b.timings.size - 1 } }
                b.timings.forEachIndexed { i, t ->
                    val loaded = b.side.filterIndexed { k, _ -> owner[k] == i }.joinToString(" ") { num(it.ping) }
                    rows += Row(t.atMs, listOf(t.atMs.toString(), dir, b.bytes.toString(), num(t.ping), t.bps?.let(::num) ?: "", num(t.duration), num(t.serverTime), t.transferSize.toString(), loaded))
                }
            }
        }
        return buildString {
            append("time,direction,bytes,latency,bps,duration,serverTime,responseSize,loadedLatencies")
            rows.sortedBy { it.time }.forEach { r -> append('\n').append(r.cells.joinToString(",")) }
        }
    }

    /** Numbers as JavaScript prints them: no ".0" on whole values, no exponent. */
    fun num(v: Double): String = when {
        v.isNaN() -> "NaN"
        v == Math.rint(v) && abs(v) < 1e21 -> v.toLong().toString()
        else -> java.math.BigDecimal(v.toString()).toPlainString()
    }
}

/** The engine's results maths. */
object CfMath {
    /** Linear interpolation between the closest ranks, 0 for no values. */
    fun percentile(values: List<Double>, p: Double): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        val idx = (s.size - 1) * p
        val rem = idx % 1
        if (rem == 0.0) return s[idx.toInt()]
        val lo = s[floor(idx).toInt()]
        val hi = s[ceil(idx).toInt()]
        return lo + (hi - lo) * rem
    }

    /** Mean absolute difference between consecutive samples; null under two samples. */
    fun jitter(values: List<Double>): Double? =
        if (values.size < 2) null else values.zipWithNext { a, b -> abs(a - b) }.sum() / (values.size - 1)

    fun bandwidth(buckets: Collection<CfBucket>): Double = percentile(
        buckets.filter { !it.parallel }.flatMap { it.timings }.filter { it.duration >= CfConfig.BANDWIDTH_MIN_REQUEST_DURATION }.mapNotNull { it.bps }.filter { it != 0.0 },
        CfConfig.BANDWIDTH_PERCENTILE,
    )

    /** The site's 90th percentile, or the parallel final downloads' combined speed when they went faster. */
    fun download(buckets: Collection<CfBucket>): Double =
        maxOf(bandwidth(buckets), buckets.filter { it.parallel }.flatMap { it.timings }.mapNotNull { it.bps }.maxOrNull() ?: 0.0)

    /**
     * Combined speed of parallel connections from (nanoseconds, bytes so far) samples: the bytes moved between 10 % and
     * 90 % of [total], leaving out the slow start and the tail where few connections are left. Null under a second.
     */
    fun windowRate(samples: List<Pair<Long, Long>>, total: Long): Double? {
        val inside = samples.filter { it.second >= total / 10 && it.second <= total * 9 / 10 }
        if (inside.size < 2) return null
        val (t0, b0) = inside.first()
        val (t1, b1) = inside.last()
        val ms = (t1 - t0) / 1e6
        return if (ms < CfConfig.FINAL_MIN_WINDOW_MS || b1 <= b0) null else (b1 - b0) * 8.0 / (ms / 1000)
    }

    /** Pings sent during the transfer sizes that kept the line busy (every request at least 100 ms), the last 100. */
    fun loadedPings(buckets: Collection<CfBucket>): List<Double> = buckets
        .filter { b -> b.timings.isNotEmpty() && b.timings.minOf { it.duration } >= CfConfig.LOADED_REQUEST_MIN_DURATION }
        .flatMap { it.side }
        .takeLast(CfConfig.LOADED_LATENCY_MAX_POINTS)
        .map { it.ping }

    private fun threshold(domain: List<Double>, range: List<Int>): (Double) -> Int = { v ->
        var i = 0
        while (i < domain.size && v >= domain[i]) i++
        range[i]
    }

    private val scoring: Map<String, (Double) -> Int> = mapOf(
        "packetLoss" to threshold(listOf(0.01, 0.05, 0.25, 0.5), listOf(10, 5, 0, -10, -20)),
        "latency" to threshold(listOf(10.0, 20.0, 50.0, 100.0, 500.0), listOf(20, 10, 5, 0, -10, -20)),
        "loadedLatencyIncrease" to threshold(listOf(10.0, 20.0, 50.0, 100.0, 500.0), listOf(20, 10, 5, 0, -10, -20)),
        "jitter" to threshold(listOf(10.0, 20.0, 100.0, 500.0), listOf(10, 5, 0, -10, -20)),
        "download" to threshold(listOf(1e6, 10e6, 50e6, 100e6), listOf(0, 5, 10, 20, 30)),
        "upload" to threshold(listOf(1e6, 10e6, 50e6, 100e6), listOf(0, 5, 10, 20, 30)),
    )

    private val experiences: Map<CfExperience, Pair<List<String>, List<Double>>> = mapOf(
        CfExperience.Streaming to (listOf("latency", "packetLoss", "download", "loadedLatencyIncrease") to listOf(15.0, 20.0, 40.0, 60.0)),
        CfExperience.Gaming to (listOf("latency", "packetLoss", "loadedLatencyIncrease") to listOf(5.0, 15.0, 25.0, 30.0)),
        CfExperience.Rtc to (listOf("latency", "jitter", "packetLoss", "loadedLatencyIncrease") to listOf(5.0, 15.0, 25.0, 40.0)),
    )

    /**
     * AIM scores. Values follow the site's summary: absent when never measured, an unmeasured packet loss counts 0 points,
     * and a missing loaded latency next to a measured one makes the increase NaN, which scores like the best band.
     */
    fun scores(download: Double?, upload: Double?, latency: Double?, jitter: Double?, downLoaded: Double?, upLoaded: Double?, packetLoss: Double?): Map<CfExperience, CfScore> {
        val increase = if (latency != null && latency != 0.0 && ((downLoaded ?: 0.0) != 0.0 || (upLoaded ?: 0.0) != 0.0)) {
            maxOf(downLoaded ?: Double.NaN, upLoaded ?: Double.NaN) - latency
        } else null
        val values = mapOf(
            "packetLoss" to packetLoss, "latency" to latency, "loadedLatencyIncrease" to increase,
            "jitter" to if (latency != null) jitter ?: Double.NaN else null, "download" to download, "upload" to upload,
        )
        val points = buildMap {
            scoring.forEach { (k, fn) ->
                val v = values[k]
                when {
                    v != null -> put(k, fn(v))
                    k == "packetLoss" -> put(k, 0)
                }
            }
        }
        return buildMap {
            experiences.forEach { (e, def) ->
                val (input, thresholds) = def
                if (input.all { it in points }) {
                    val sum = maxOf(0, input.sumOf { points.getValue(it) })
                    put(e, CfScore(sum, threshold(thresholds, listOf(0, 1, 2, 3, 4))(sum.toDouble())))
                }
            }
        }
    }
}

/**
 * Runs the plan. [run] picks up where a paused run stopped: the step, and the requests already made in it. A request cut
 * by a pause is lost, as on the site.
 */
class CfEngine(
    private val transport: CfTransport,
    private val packetLoss: suspend (CfStep.PacketLoss, (CfPacketLoss) -> Unit) -> CfPacketLoss,
    private val now: () -> Long = System::currentTimeMillis,
    /** The 500 MB and 1 GB final downloads; off on a metered network so a test never eats a data plan. */
    private val bigFiles: Boolean = true,
) {
    private val lock = Any()
    private val latency = mutableListOf<CfTiming>()
    private val down = LinkedHashMap<Long, CfBucket>()
    private val up = LinkedHashMap<Long, CfBucket>()
    private var downStarted = false
    private var upStarted = false
    private var downDone = false
    private var upDone = false
    private var loss: CfPacketLoss? = null
    private var lossLive: CfPacketLoss? = null
    private var lossError: String? = null
    private val clock = CfClock()
    private var stepIndex = 0
    private var counter = 0
    private var plannedStep = -1
    private val round = mutableListOf<Double>()
    private var runMs = 0L
    var finished = false
        private set

    suspend fun run(onUpdate: (CfSnapshot) -> Unit) {
        val started = now()
        val emit = { onUpdate(snapshot(runMs + now() - started)) }
        try {
            while (stepIndex < CfConfig.steps.size) {
                emit()
                when (val step = CfConfig.steps[stepIndex]) {
                    is CfStep.Latency -> while (counter < step.packets) {
                        val t = request(0, true, 0, "idle")
                        synchronized(lock) { latency += t }
                        counter++
                        emit()
                    }
                    is CfStep.Transfer -> when {
                        step.final -> if (runFinal(step)) parallel(step, emit)
                        !(if (step.down) downDone else upDone) -> transfer(step, emit)
                    }
                    is CfStep.PacketLoss -> {
                        synchronized(lock) { lossLive = CfPacketLoss(step.packets, 0, 0, emptyList()); lossError = null }
                        emit()
                        try {
                            val r = packetLoss(step) { synchronized(lock) { lossLive = it }; emit() }
                            synchronized(lock) { loss = r; lossLive = r }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            synchronized(lock) { lossError = e.message ?: e.javaClass.simpleName; lossLive = null }
                        }
                    }
                }
                stepIndex++
                counter = 0
                round.clear()
            }
            finished = true
        } finally {
            runMs += now() - started
        }
        onUpdate(snapshot(runMs))
    }

    private fun runFinal(step: CfStep.Transfer): Boolean {
        if (!bigFiles || !step.down) return false
        val bps = synchronized(lock) { if (down.isEmpty()) 0.0 else CfMath.download(down.values) }
        return bps > 0 && step.bytes * 8.0 / bps < CfConfig.FINAL_MAX_SECONDS
    }

    private suspend fun transfer(step: CfStep.Transfer, emit: () -> Unit) = coroutineScope {
        val bucket = synchronized(lock) {
            if (step.down) downStarted = true else upStarted = true
            (if (step.down) down else up).getOrPut(step.bytes) { CfBucket(step.bytes) }.also {
                if (plannedStep != stepIndex) { it.planned += step.count; plannedStep = stepIndex }
            }
        }
        val side = loadedPings(bucket, step.down, emit)
        try {
            while (counter < step.count) {
                val t = request(0, step.down, step.bytes, null)
                if (t.bps != null && t.bps.isFinite() && t.bps > 0) {
                    synchronized(lock) { bucket.timings += t }
                    round += t.duration
                }
                counter++
                emit()
            }
        } finally {
            side.cancel()
        }
        val lastOfType = CfConfig.steps.drop(stepIndex + 1).none { it is CfStep.Transfer && it.down == step.down }
        val slowEnough = !step.bypassMinDuration && round.isNotEmpty() && round.min() > CfConfig.FINISH_REQUEST_DURATION
        if (lastOfType || slowEnough) synchronized(lock) { if (step.down) downDone = true else upDone = true }
    }

    /** Latency under load: zero-byte pings on their own connection, one every 400 ms after the previous answer. */
    private fun CoroutineScope.loadedPings(bucket: CfBucket, down: Boolean, emit: () -> Unit): Job {
        val sideClock = CfClock(clock.serverTimeDelta)
        return launch {
            delay(20)
            while (isActive) {
                runCatching { io(1) { transport.exchange(1, true, 0, if (down) "download" else "upload") } }
                    .onSuccess { raw -> synchronized(lock) { bucket.side += sideClock.timing(raw, true, 0, now()) }; emit() }
                    .onFailure { if (it is CancellationException) throw it }
                delay(CfConfig.LOADED_LATENCY_THROTTLE_MS)
            }
        }
    }

    /**
     * A final download split over [CfStep.Transfer.streams] connections at once, measured as one: the bytes every
     * connection received together over the time they all ran, sampled live. A failure only skips it.
     */
    private suspend fun parallel(step: CfStep.Transfer, emit: () -> Unit) = coroutineScope {
        val bucket = synchronized(lock) {
            downStarted = true
            down.getOrPut(step.bytes) { CfBucket(step.bytes, parallel = true) }.also {
                if (plannedStep != stepIndex) { it.planned += 1; plannedStep = stepIndex }
            }
        }
        val side = loadedPings(bucket, true, emit)
        val per = step.bytes / step.streams
        val base = transport.received()
        val samples = mutableListOf<Pair<Long, Long>>()
        val sampler = launch {
            while (isActive) {
                synchronized(samples) { samples += System.nanoTime() to transport.received() - base }
                delay(100)
            }
        }
        val started = System.nanoTime()
        try {
            val raws = (0 until step.streams).map { i ->
                async {
                    io(STREAM_LANE + i) { transport.exchange(STREAM_LANE + i, true, per, null) }
                }
            }.awaitAll()
            sampler.cancelAndJoin()
            val ms = (System.nanoTime() - started) / 1e6
            val bps = CfMath.windowRate(synchronized(samples) { samples.toList() }, per * step.streams)
                // Too fast for the window: everything over the whole time. No live byte counts: each connection's speed added up.
                ?: if (transport.received() > base) per * step.streams * 8.0 / (ms / 1000)
                else raws.sumOf { clock.timing(it, true, per, now()).bps ?: 0.0 }
            if (bps > 0) synchronized(lock) {
                bucket.timings += CfTiming(step.bytes, raws.minOf { it.ttfb }, ms, -1.0, raws.sumOf { it.transferSize }, raws.minOf { it.ttfb }, ms, bps, now())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A bonus measurement: losing it leaves the ladder's result.
        } finally {
            sampler.cancel()
            side.cancel()
        }
        counter++
        emit()
    }

    /** One request on [lane], retried up to three times after a 429 as the server's Retry-After says. */
    private suspend fun request(lane: Int, down: Boolean, bytes: Long, during: String?): CfTiming {
        var retries = 0
        while (true) {
            try {
                val raw = io(lane) { transport.exchange(lane, down, bytes, during) }
                return clock.timing(raw, down, bytes, now())
            } catch (e: CfHttpException) {
                if (e.status != 429 || retries++ >= CfConfig.MAX_RETRIES) throw e
                delay(retryDelay(e.retryAfter))
            }
        }
    }

    private fun retryDelay(retryAfter: String?): Long {
        retryAfter ?: return 5000
        retryAfter.trim().toDoubleOrNull()?.let { if (it >= 0) return (it * 1000).toLong() }
        return runCatching { maxOf(0L, java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US).parse(retryAfter)!!.time - now()) }.getOrDefault(5000)
    }

    /** Blocking transport call that a cancelled coroutine aborts by closing the lane's connection. */
    private suspend fun <T> io(lane: Int, block: () -> T): T = coroutineScope {
        val done = AtomicBoolean(false)
        val watch = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { if (!done.get()) transport.abort(lane) }
        }
        try {
            runInterruptible(Dispatchers.IO) { block() }
        } finally {
            done.set(true)
            watch.cancel()
        }
    }

    companion object {
        /** First lane of the parallel connections. */
        const val STREAM_LANE = 2
    }

    fun snapshot(durationMs: Long = runMs): CfSnapshot = synchronized(lock) {
        val pings = latency.map { it.ping }
        val lat = if (pings.isEmpty()) null else CfMath.percentile(pings, CfConfig.LATENCY_PERCENTILE)
        val jit = CfMath.jitter(pings)
        val dl = if (downStarted) CfMath.download(down.values) else null
        val ul = if (upStarted) CfMath.bandwidth(up.values) else null
        val dPings = if (downStarted) CfMath.loadedPings(down.values) else null
        val uPings = if (upStarted) CfMath.loadedPings(up.values) else null
        val dLoaded = dPings?.let { CfMath.percentile(it, CfConfig.LATENCY_PERCENTILE) }
        val uLoaded = uPings?.let { CfMath.percentile(it, CfConfig.LATENCY_PERCENTILE) }
        CfSnapshot(
            stepIndex = stepIndex,
            stepDone = counter,
            finished = finished,
            download = dl,
            upload = ul,
            latency = lat,
            jitter = jit,
            downLoadedLatency = dLoaded,
            downLoadedJitter = dPings?.let(CfMath::jitter),
            upLoadedLatency = uLoaded,
            upLoadedJitter = uPings?.let(CfMath::jitter),
            packetLoss = loss,
            packetLossLive = lossLive,
            packetLossError = lossError,
            latencyPoints = pings,
            downLoadedPoints = dPings.orEmpty(),
            upLoadedPoints = uPings.orEmpty(),
            downBuckets = down.values.map { CfBucketView(it.bytes, it.planned, it.timings.toList(), it.side.toList()) },
            upBuckets = up.values.map { CfBucketView(it.bytes, it.planned, it.timings.toList(), it.side.toList()) },
            scores = if (finished) CfMath.scores(dl, ul, lat, jit, dLoaded, uLoaded, loss?.ratio) else null,
            durationMs = durationMs,
        )
    }
}

/** Where the test runs from and to, as speed.cloudflare.com/meta reports it. */
data class CfMeta(
    val clientIp: String?,
    val asn: Int?,
    val asOrganization: String?,
    val city: String?,
    val region: String?,
    val country: String?,
    val lat: Double?,
    val lon: Double?,
    val colo: String?,
    val coloCity: String?,
    val coloCountry: String?,
    val coloLat: Double?,
    val coloLon: Double?,
)

/** HTTP/1.1 over TLS on a kept-alive socket per lane, timed like the browser's resource timing. */
class CfHttp(private val host: String = CfConfig.HOST) : CfTransport {
    /** The site tags every request of a run with a random measurement id. */
    private val measId = (Math.random() * 1e16).toLong().toString()

    private val lanes = ConcurrentHashMap<Int, Lane>()
    private val live = AtomicLong()

    override fun received(): Long = live.get()

    override fun exchange(lane: Int, down: Boolean, bytes: Long, during: String?): CfRaw =
        lanes.getOrPut(lane) { Lane() }.exchange(down, bytes, during)

    override fun abort(lane: Int) {
        lanes[lane]?.closeSocket()
    }

    fun close() {
        lanes.values.forEach { it.closeSocket() }
        lanes.clear()
    }

    private inner class Lane {
        @Volatile private var socket: Socket? = null
        private var input: InputStream? = null
        private var output: OutputStream? = null
        private val buf = ByteArray(64 * 1024)

        fun closeSocket() {
            runCatching { socket?.close() }
            socket = null
        }

        fun exchange(down: Boolean, bytes: Long, during: String?): CfRaw {
            val path = (if (down) "/__down" else "/__up") + "?measId=$measId&" + (during?.let { "during=$it&" } ?: "") + "bytes=$bytes"
            val reused = socket != null
            val connectMs = if (reused) 0.0 else connect()
            return try {
                send(path, if (down) 0 else bytes, connectMs)
            } catch (e: IOException) {
                closeSocket()
                // A kept-alive connection the server already closed fails before any answer: retry once on a new one.
                if (!reused || e is CfHttpException || e is Answered || Thread.currentThread().isInterrupted) throw (e as? Answered)?.cause ?: e
                try {
                    send(path, if (down) 0 else bytes, connect())
                } catch (again: Answered) {
                    throw again.cause
                }
            }
        }

        /** Returns the TCP handshake time (the browser's connectStart → secureConnectionStart). */
        private fun connect(): Double {
            val raw = Socket()
            socket = raw
            raw.tcpNoDelay = true
            val t0 = System.nanoTime()
            raw.connect(InetSocketAddress(host, 443), 10_000)
            val tcp = (System.nanoTime() - t0) / 1e6
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, 443, true) as SSLSocket
            socket = ssl
            ssl.soTimeout = 30_000
            ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            ssl.startHandshake()
            input = BufferedInputStream(ssl.inputStream, 64 * 1024)
            output = ssl.outputStream
            return tcp
        }

        private fun send(path: String, upload: Long, connectMs: Double): CfRaw {
            val out = output!!
            val inp = input!!
            val head = buildString {
                append(if (upload > 0) "POST" else "GET").append(' ').append(path).append(" HTTP/1.1\r\n")
                append("Host: ").append(host).append("\r\n")
                append("User-Agent: ").append(USER_AGENT).append("\r\n")
                append("Accept: */*\r\n")
                append("Referer: https://").append(host).append("/\r\n")
                if (upload > 0) append("Content-Type: text/plain;charset=UTF-8\r\nContent-Length: ").append(upload).append("\r\n")
                append("Connection: keep-alive\r\n\r\n")
            }.toByteArray()
            val t0 = System.nanoTime()
            out.write(head)
            var left = upload
            while (left > 0) {
                val n = minOf(left, ZEROS.size.toLong()).toInt()
                out.write(ZEROS, 0, n)
                left -= n
            }
            out.flush()
            val first = inp.read()
            if (first < 0) throw IOException("connection closed")
            val tFirst = System.nanoTime()
            try {
                var received = 1L
                val lines = mutableListOf<String>()
                val line = StringBuilder().append(first.toChar())
                while (true) {
                    val c = inp.read()
                    if (c < 0) throw IOException("connection closed")
                    received++
                    if (c == '\n'.code) {
                        val l = line.toString().trimEnd('\r')
                        line.setLength(0)
                        if (l.isEmpty()) break
                        lines += l
                    } else line.append(c.toChar())
                }
                val status = lines.first().split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("bad status line")
                val headers = lines.drop(1).mapNotNull { h -> h.indexOf(':').takeIf { it > 0 }?.let { h.substring(0, it).trim().lowercase() to h.substring(it + 1).trim() } }
                fun header(name: String) = headers.filter { it.first == name }.map { it.second }
                val length = header("content-length").firstOrNull()?.toLongOrNull()
                val chunked = header("transfer-encoding").any { it.contains("chunked", true) }
                received += when {
                    chunked -> readChunked(inp)
                    length != null -> readExactly(inp, length)
                    else -> readToEnd(inp).also { closeSocket() }
                }
                val tEnd = System.nanoTime()
                if (header("connection").any { it.equals("close", true) }) closeSocket()
                if (status !in 200..299) {
                    closeSocket()
                    throw CfHttpException(status, header("retry-after").firstOrNull())
                }
                return CfRaw(
                    ttfb = (tFirst - t0) / 1e6,
                    payloadMs = (tEnd - tFirst) / 1e6,
                    transferSize = received,
                    connectMs = connectMs,
                    serverTiming = header("server-timing").joinToString(", ").ifEmpty { null },
                )
            } catch (e: CfHttpException) {
                throw e
            } catch (e: IOException) {
                throw Answered(e)
            }
        }

        private fun readExactly(inp: InputStream, n: Long): Long {
            var left = n
            while (left > 0) {
                val r = inp.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
                if (r < 0) throw IOException("connection closed")
                left -= r
                live.addAndGet(r.toLong())
            }
            return n
        }

        private fun readToEnd(inp: InputStream): Long {
            var total = 0L
            while (true) {
                val r = inp.read(buf)
                if (r < 0) return total
                total += r
                live.addAndGet(r.toLong())
            }
        }

        private fun readChunked(inp: InputStream): Long {
            var total = 0L
            while (true) {
                val sizeLine = readLine(inp)
                total += sizeLine.length + 2
                val size = sizeLine.substringBefore(';').trim().toLong(16)
                if (size == 0L) {
                    while (true) {
                        val trailer = readLine(inp)
                        total += trailer.length + 2
                        if (trailer.isEmpty()) return total
                    }
                }
                total += readExactly(inp, size)
                total += readLine(inp).length + 2
            }
        }

        private fun readLine(inp: InputStream): String {
            val sb = StringBuilder()
            while (true) {
                val c = inp.read()
                if (c < 0) throw IOException("connection closed")
                if (c == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(c.toChar())
            }
        }
    }

    /** A failure after the server started answering: never retried. */
    private class Answered(override val cause: IOException) : IOException(cause)

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android) AllRadioTools"
        private val ZEROS = ByteArray(64 * 1024) { '0'.code.toByte() }

        private fun get(path: String): String {
            val c = URL("https://${CfConfig.HOST}$path").openConnection() as HttpURLConnection
            c.connectTimeout = 5000; c.readTimeout = 5000
            c.setRequestProperty("User-Agent", USER_AGENT)
            c.setRequestProperty("Referer", "https://${CfConfig.HOST}/")
            c.setRequestProperty("Origin", "https://${CfConfig.HOST}")
            return try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
        }

        suspend fun meta(): CfMeta? = withContext(Dispatchers.IO) {
            runCatching {
                val j = JSONObject(get("/meta"))
                fun s(o: JSONObject?, k: String) = o?.optString(k)?.ifEmpty { null }
                fun d(o: JSONObject?, k: String) = o?.optString(k)?.toDoubleOrNull()
                val colo = j.optJSONObject("colo")
                CfMeta(
                    clientIp = s(j, "clientIp"),
                    asn = j.optInt("asn").takeIf { it > 0 },
                    asOrganization = s(j, "asOrganization"),
                    city = s(j, "city"),
                    region = s(j, "region"),
                    country = s(j, "country"),
                    lat = d(j, "latitude"),
                    lon = d(j, "longitude"),
                    colo = s(colo, "iata") ?: s(j, "colo"),
                    coloCity = s(colo, "city"),
                    coloCountry = s(colo, "cca2"),
                    coloLat = d(colo, "lat"),
                    coloLon = d(colo, "lon"),
                )
            }.getOrNull()
        }

        /**
         * The site's packet-loss test: 1000 numbered UDP messages, in batches of ten every 10 ms, from one TURN allocation to
         * another through turn.cloudflare.com, then 3 s of silence after the last answer before counting the missing ones.
         */
        suspend fun packetLoss(step: CfStep.PacketLoss, onProgress: (CfPacketLoss) -> Unit): CfPacketLoss = withContext(Dispatchers.IO) {
            val creds = runCatching { JSONObject(get("/turn-creds")) }.getOrNull()
            val user = creds?.optString("username")?.ifEmpty { null }
            val pass = creds?.optString("credential")?.ifEmpty { null }
            if (creds == null || creds.has("error") || user == null || pass == null) throw IOException("unable to get turn server credentials")
            val server = TurnClient.resolve(creds.optString("server").ifEmpty { CfConfig.TURN_URI })
            runPacketLoss(step, TurnClient(server, user, pass), TurnClient(server, user, pass), onProgress)
        }

        suspend fun runPacketLoss(step: CfStep.PacketLoss, sender: TurnClient, receiver: TurnClient, onProgress: (CfPacketLoss) -> Unit): CfPacketLoss = coroutineScope {
            try {
                val deadline = System.currentTimeMillis() + step.connectionTimeoutMs
                val relayA = runInterruptible { sender.allocate(deadline) }
                val relayB = runInterruptible { receiver.allocate(deadline) }
                runInterruptible { sender.permit(relayB.address, deadline); receiver.permit(relayA.address, deadline) }
                val received = ConcurrentHashMap.newKeySet<Int>()
                val lastAnswer = AtomicLong(0)
                val rx = launch(Dispatchers.IO) {
                    while (isActive) {
                        val data = receiver.receive() ?: continue
                        val n = String(data).toIntOrNull() ?: continue
                        if (n in 1..step.packets && received.add(n)) lastAnswer.set(System.currentTimeMillis())
                    }
                }
                fun result(sent: Int) = CfPacketLoss(step.packets, sent, received.size, (1..sent).filter { it !in received })
                var sent = 0
                while (sent < step.packets) {
                    repeat(minOf(step.batchSize, step.packets - sent)) {
                        sent++
                        sender.send(relayB, sent.toString().toByteArray())
                    }
                    onProgress(result(sent))
                    delay(step.batchWaitMs)
                }
                val allSent = System.currentTimeMillis()
                while (received.size < sent && System.currentTimeMillis() - maxOf(allSent, lastAnswer.get()) < step.responsesWaitMs) {
                    delay(50)
                    onProgress(result(sent))
                }
                rx.cancelAndJoin()
                result(sent).also(onProgress)
            } finally {
                sender.close()
                receiver.close()
            }
        }
    }
}
