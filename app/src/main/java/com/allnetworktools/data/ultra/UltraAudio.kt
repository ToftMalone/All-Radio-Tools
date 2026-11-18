package com.allnetworktools.data.ultra

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder

/**
 * The microphone as raw as the phone gives it. Ultrasound needs the "unprocessed" source where the phone has one (no
 * noise suppression or filter cutting the top), else voice recognition, the least processed of the others; and the
 * highest sample rate the phone accepts, which sets how high it can hear.
 */
class UltraMic(private val context: Context) {
    var rate = 0; private set
    /** Which source Android gave: "unprocessed", "voice recognition" or "microphone". */
    var sourceName = ""; private set
    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    private fun sources(): List<Pair<Int, String>> {
        val am = context.getSystemService(AudioManager::class.java)
        val raw = am?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        return listOfNotNull(
            if (raw) MediaRecorder.AudioSource.UNPROCESSED to "unprocessed" else null,
            MediaRecorder.AudioSource.VOICE_RECOGNITION to "voice recognition",
            MediaRecorder.AudioSource.MIC to "microphone",
        )
    }

    /** Starts reading; [onSamples] gets blocks of samples between -1 and 1 on the reading thread. */
    @SuppressLint("MissingPermission")
    fun start(rates: List<Int>, onSamples: (FloatArray, Int) -> Unit): Boolean {
        if (running) return true
        for ((src, name) in sources()) for (r in rates) {
            val min = AudioRecord.getMinBufferSize(r, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            val rec = runCatching { AudioRecord(src, r, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, r / 5)) }.getOrNull() ?: continue
            if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); continue }
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) { rec.release(); continue }
            record = rec; rate = r; sourceName = name; running = true
            thread = Thread({
                val pcm = ShortArray(r / 50)
                val f = FloatArray(pcm.size)
                try {
                    while (running) {
                        val n = rec.read(pcm, 0, pcm.size)
                        if (n < 0) break
                        for (i in 0 until n) f[i] = pcm[i] / 32768f
                        if (n > 0) onSamples(f, n)
                    }
                } catch (_: Exception) {
                } finally {
                    runCatching { rec.stop() }
                    runCatching { rec.release() }
                }
            }, "ultra-mic").apply { start() }
            return true
        }
        return false
    }

    fun stop() {
        running = false
        runCatching { thread?.join(500) }
        thread = null
        record = null
    }
}

/** The chirp, played again and again from the loudspeaker, for the sonar. */
class ChirpPlayer(rate: Int, chirp: FloatArray) {
    private val track: AudioTrack? = runCatching {
        // A little silence after the chirp, so the player ends cleanly.
        val pcm = ShortArray(chirp.size + rate / 100) { i -> if (i < chirp.size) (chirp[i] * 0.9f * 32767).toInt().toShort() else 0 }
        AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build().also { it.write(pcm, 0, pcm.size) }
    }.getOrNull()

    val ok: Boolean get() = track?.state == AudioTrack.STATE_INITIALIZED

    fun ping() {
        val t = track ?: return
        runCatching {
            if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop()
            t.reloadStaticData()
            t.play()
        }
    }

    fun release() { runCatching { track?.stop(); track?.release() } }
}
