package com.allnetworktools.ui.pages.nfc

import android.content.Context
import android.nfc.NdefMessage
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.allnetworktools.data.nfc.Apdu
import com.allnetworktools.data.nfc.Dump
import com.allnetworktools.data.nfc.Emv
import com.allnetworktools.data.nfc.EmvCard
import com.allnetworktools.data.nfc.NfcRepository
import com.allnetworktools.data.nfc.TagIo
import com.allnetworktools.data.nfc.TagScan
import com.allnetworktools.data.nfc.WriteResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What the open NFC page does with the next tag. */
enum class NfcMode { Read, Write, Copy, Erase, Lock, Bank, Dump, Antenna }

/** Live figures of the antenna test while a tag is held. */
data class AntennaStats(
    val present: Boolean = false,
    val contacts: Int = 0,
    val heldMs: Long = 0,
    val ok: Int = 0,
    val tries: Int = 0,
    /** Exchanges per second over the last second, and the best seen. */
    val rate: Int = 0,
    val best: Int = 0,
    /** Share of exchanges that worked over the last second. */
    val quality: Float = 0f,
    val drops: Int = 0,
)

/**
 * The NFC pages' state, kept while the app runs; nothing is written to disk. Tags arrive on the NFC thread, where they
 * are read or written; results go to the page on the main thread.
 */
class NfcController(private val context: Context, private val repo: NfcRepository, private val scope: CoroutineScope) {
    var mode by mutableStateOf<NfcMode?>(null)
        private set

    /** Tags read this session, newest first. */
    var history by mutableStateOf<List<TagScan>>(emptyList())
        private set
    var last by mutableStateOf<TagScan?>(null)
        private set
    /** A tag is being read or written right now. */
    var working by mutableStateOf(false)
        private set

    /** The message the writer puts on the next tags, and how many it wrote. */
    var pending by mutableStateOf<NdefMessage?>(null)
    var written by mutableStateOf(0)
        private set
    var writeResult by mutableStateOf<WriteResult?>(null)
        private set
    var resultAt by mutableStateOf(0L)
        private set

    /** The tag being copied, read first. */
    var copySource by mutableStateOf<TagScan?>(null)

    /** Lock is armed only after the user confirmed, for one tag. */
    var lockArmed by mutableStateOf(false)

    var card by mutableStateOf<EmvCard?>(null)
        private set
    var cardFailed by mutableStateOf(false)
        private set

    var dump by mutableStateOf<Dump?>(null)
        private set

    var antenna by mutableStateOf(AntennaStats())
        private set

    /** The page [m] is open: tags go to it. */
    fun open(m: NfcMode) {
        mode = m
        repo.listen(::onTag)
    }

    fun close(m: NfcMode) {
        if (mode != m) return
        mode = null
        lockArmed = false
        repo.listen(null)
    }

    fun clearHistory() { history = emptyList(); last = null }
    fun resetCopy() { copySource = null; writeResult = null }
    fun resetWrites() { written = 0; writeResult = null }

    private fun post(block: () -> Unit) { scope.launch { block() } }

    private fun buzz() {
        runCatching { context.getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)) }
    }

    private fun result(r: WriteResult) {
        val now = System.currentTimeMillis()
        post { writeResult = r; resultAt = now; if (r == WriteResult.Ok) written++ }
    }

    private fun onTag(tag: Tag) {
        val m = mode ?: return
        buzz()
        post { working = true }
        try {
            when (m) {
                NfcMode.Read -> {
                    val s = TagIo.scan(tag, System.currentTimeMillis())
                    post { last = s; history = (listOf(s) + history.filterNot { it.uid.contentEquals(s.uid) && s.uid.isNotEmpty() }).take(50) }
                }
                NfcMode.Write -> pending?.let { result(TagIo.write(tag, it)) }
                NfcMode.Copy -> {
                    val src = copySource
                    if (src == null) {
                        val s = TagIo.scan(tag, System.currentTimeMillis())
                        post { copySource = s.takeIf { it.message != null }; writeResult = null; if (s.message == null) { resultAt = System.currentTimeMillis(); writeResult = WriteResult.NotNdef } }
                    } else {
                        // The source itself held again: nothing to do.
                        if (src.uid.isNotEmpty() && src.uid.contentEquals(tag.id)) return
                        result(TagIo.write(tag, src.message!!))
                    }
                }
                NfcMode.Erase -> result(TagIo.erase(tag))
                NfcMode.Lock -> if (lockArmed) {
                    val r = TagIo.lock(tag)
                    post { lockArmed = false }
                    result(r)
                }
                NfcMode.Bank -> {
                    val iso = IsoDep.get(tag)
                    val c = iso?.let { d ->
                        runCatching {
                            d.connect()
                            d.timeout = 3000
                            try {
                                Emv.read(Apdu { d.transceive(it) }, SimpleDateFormat("yyMMdd", Locale.US).format(Date()))
                            } finally {
                                runCatching { d.close() }
                            }
                        }.getOrNull()
                    }
                    post { card = c; cardFailed = c == null }
                }
                NfcMode.Dump -> {
                    val s = TagIo.scan(tag, System.currentTimeMillis())
                    val d = TagIo.dump(tag, s)
                    post { dump = d; last = s }
                }
                NfcMode.Antenna -> antennaLoop(tag)
            }
        } finally {
            post { working = false }
        }
    }

    /**
     * While the tag stays, exchanges with it back to back; the rate and the share that work show how well it couples
     * with the antenna at this spot. The tag is gone after half a second without an answer.
     */
    private fun antennaLoop(tag: Tag) {
        val tech = TagIo.pingTech(tag)
        val start = SystemClock.elapsedRealtime()
        var s = antenna.copy(present = true, contacts = antenna.contacts + 1, ok = 0, tries = 0, rate = 0, quality = 0f)
        post { antenna = s }
        var windowStart = start
        var winOk = 0
        var winTries = 0
        var lastOk = start
        var wasFailing = false
        while (mode == NfcMode.Antenna) {
            val good = TagIo.ping(tag, tech)
            val now = SystemClock.elapsedRealtime()
            winTries++
            if (good) { winOk++; lastOk = now }
            if (!good && !wasFailing) s = s.copy(drops = s.drops + 1)
            wasFailing = !good
            s = s.copy(ok = s.ok + if (good) 1 else 0, tries = s.tries + 1, heldMs = now - start)
            if (now - windowStart >= 250) {
                val perS = (winOk * 1000 / (now - windowStart)).toInt()
                s = s.copy(rate = perS, best = maxOf(s.best, perS), quality = winOk.toFloat() / winTries)
                val snap = s
                post { antenna = snap }
                windowStart = now; winOk = 0; winTries = 0
            }
            if (now - lastOk > 500) break
            if (!good) Thread.sleep(20)
        }
        runCatching { tech?.close() }
        val end = s.copy(present = false, rate = 0, quality = 0f, drops = maxOf(0, s.drops - 1))
        post { antenna = end }
        buzz()
    }
}
