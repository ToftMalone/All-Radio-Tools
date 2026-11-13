package com.allnetworktools.data.sdr

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * The microphone as an [AudioSource] at [rate]: a thread reads it into a ring, [next] takes from the ring and fills
 * with silence when the microphone is late, so the transmitter never waits. Speech is levelled. Ends at [close].
 */
class MicSource(override val rate: Int = 48_000) : AudioSource {
    private val ring = FloatArray(rate)
    @Volatile private var w = 0L
    private var r = 0L
    @Volatile private var open = true
    private val leveller = SpeechLeveller()
    private var record: AudioRecord? = null

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching { AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate / 5)) }.getOrNull()
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) { rec?.release(); return false }
        record = rec
        rec.startRecording()
        Thread({
            val pcm = ShortArray(rate / 50)
            while (open) {
                val n = rec.read(pcm, 0, pcm.size)
                if (n <= 0) continue
                for (i in 0 until n) { ring[(w % ring.size).toInt()] = leveller.level(pcm[i] / 32768f); w++ }
            }
        }, "mic-tx").start()
        return true
    }

    override fun next(out: FloatArray): Int {
        if (!open) return -1
        // Keep about 60 ms in hand; drop what is too old after a stall.
        if (w - r > ring.size / 2) r = w - rate / 16
        for (i in out.indices) {
            out[i] = if (r < w - rate / 50) ring[(r++ % ring.size).toInt()] else 0f
        }
        return out.size
    }

    fun close() {
        open = false
        runCatching { record?.stop(); record?.release() }
        record = null
    }
}
