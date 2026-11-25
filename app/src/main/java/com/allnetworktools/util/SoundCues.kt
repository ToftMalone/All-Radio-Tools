package com.allnetworktools.util

import android.media.AudioManager
import android.media.ToneGenerator

/** The short tones a tool can play, when the user switched them on (Settings, off by default). */
enum class Cue(val tone: Int, val ms: Int) {
    /** A signal came up: a sonde, an aircraft channel, a station over the squelch. */
    Found(ToneGenerator.TONE_PROP_BEEP2, 250),
    /** A decoder locked on (a satellite's frame marker). */
    Lock(ToneGenerator.TONE_PROP_ACK, 300),
    /** It lost it, or a signal went quiet. */
    Lost(ToneGenerator.TONE_PROP_NACK, 300),
    /** A satellite pass is over. */
    PassEnded(ToneGenerator.TONE_PROP_PROMPT, 400),
}

/**
 * Sound cues: off unless the setting is on, quiet (notification stream, low volume) and never more than one per kind
 * every [MIN_GAP_MS], so a flickering lock does not turn into a buzz.
 */
object SoundCues {
    @Volatile var enabled = false
    private const val MIN_GAP_MS = 3_000L
    private val last = LongArray(Cue.entries.size)
    private var gen: ToneGenerator? = null

    /** Whether [cue] would sound now; also marks it as played. Split from [play] for the tests. */
    @Synchronized
    internal fun allow(cue: Cue, nowMs: Long): Boolean {
        if (!enabled) return false
        if (nowMs - last[cue.ordinal] < MIN_GAP_MS) return false
        last[cue.ordinal] = nowMs
        return true
    }

    @Synchronized
    internal fun resetForTest() { last.fill(0); enabled = false }

    fun play(cue: Cue) {
        if (!allow(cue, System.currentTimeMillis())) return
        runCatching {
            val g = synchronized(this) { gen ?: ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60).also { gen = it } }
            g.startTone(cue.tone, cue.ms)
        }.onFailure { DebugLog.w("Cues", "Tone failed: ${it.message}") }
    }
}
