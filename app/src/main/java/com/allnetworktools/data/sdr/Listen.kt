package com.allnetworktools.data.sdr

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** How a signal is turned into sound. */
enum class ListenMode(val label: String, val stepHz: Long, val bandwidthHz: Int) {
    /** Broadcast FM: ±75 kHz of deviation, 200 kHz wide, mono. */
    Wfm("WFM", 100_000, 200_000),
    /** Narrow FM (walkie-talkies, marine, radio amateurs): ±5 kHz of deviation. */
    Nfm("NFM", 12_500, 16_000),
    /** Amplitude modulation (aviation, shortwave broadcasts, CB). */
    Am("AM", 25_000, 10_000),
    /** Upper sideband (radio amateurs above 10 MHz). */
    Usb("USB", 500, 2_400),
    /** Lower sideband (radio amateurs below 10 MHz). */
    Lsb("LSB", 500, 2_400),
    /**
     * Digital voice, DMR and dPMR (4FSK at 4800 and 2400 symbols/s): the AMBE+2 voice decoded and played. A call its
     * header marks as encrypted stays silent; receive only.
     */
    Dv("DMR/dPMR", 6_250, 12_500);

    companion object {
        /** Amateur bands where voice is single sideband: lower below 10 MHz, upper above. */
        private val SsbBands = listOf(
            1_810_000L..2_000_000L, 3_500_000L..4_000_000L, 7_000_000L..7_300_000L, 10_100_000L..10_150_000L,
            14_000_000L..14_350_000L, 18_068_000L..18_168_000L, 21_000_000L..21_450_000L, 24_890_000L..24_990_000L,
            28_000_000L..29_700_000L, 50_000_000L..54_000_000L,
        )

        /** The mode a frequency is usually heard in. */
        fun guess(hz: Long): ListenMode = when {
            SsbBands.any { hz in it } -> if (hz < 10_000_000L) Lsb else Usb
            hz < 30_000_000L -> Am
            hz in 87_500_000L..108_000_000L -> Wfm
            hz in 108_000_000L..137_000_000L -> Am
            else -> Nfm
        }
    }
}

/**
 * Takes a window of 2 MHz of I/Q (signed 8-bit pairs), picks the channel [offsetHz] away from its centre, and turns it into
 * sound at [AUDIO_RATE]: a numerically controlled oscillator brings the channel to zero, two filter-and-decimate stages
 * narrow it, and the demodulator of [mode] makes the audio. Everything on one thread.
 */
class ListenReceiver(private val sampleRate: Int = 2_000_000, private val onAudio: (FloatArray, Int) -> Unit) {
    @Volatile var mode: ListenMode = ListenMode.Wfm
    @Volatile var offsetHz: Double = 0.0
    /** The IF power under which the sound is muted, in dB; null for no squelch. */
    @Volatile var squelchDb: Double? = null
    /**
     * Extra loudness after the demodulator, in dB (0 to 24), for a signal still too quiet at full volume; a soft limiter
     * rounds off the peaks instead of clipping them.
     */
    @Volatile var boostDb = 0f
    /**
     * Notches, as offsets from the middle of the I/Q stream (like [offsetHz]): each takes a narrow band (about 400 Hz)
     * out of the channel before it is demodulated, for a carrier or a whistle sitting on the wanted signal.
     */
    @Volatile var notchesHz: DoubleArray = DoubleArray(0)
    /** Power of the selected channel, in dB relative to full scale. */
    @Volatile var levelDb = -120.0
        private set

    // Numerically controlled oscillator: a phasor turned by one step per sample.
    private var phRe = 1.0
    private var phIm = 0.0
    private var lastStep = Double.NaN
    private var stRe = 1.0
    private var stIm = 0.0

    // Stage 1: 2 MS/s down to 250 kS/s, about ±100 kHz wide.
    private val d1 = sampleRate / 250_000
    private val h1 = lowPass(96, 100_000.0 / sampleRate)
    private val hist1Re = FloatArray(2 * h1.size)
    private val hist1Im = FloatArray(2 * h1.size)
    private var pos1 = 0
    private var phase1 = 0

    // Stage 2: 250 kS/s down to 50 kS/s, narrowed to the mode's channel.
    private var h2 = lowPass(64, 6_000.0 / 250_000)
    private var h2Mode: ListenMode? = null
    private val hist2Re = FloatArray(2 * 96)
    private val hist2Im = FloatArray(2 * 96)
    private var pos2 = 0
    private var phase2 = 0

    // Broadcast FM keeps the discriminator at 250 kS/s, then filters and decimates its audio.
    private val hAudio = lowPass(64, 16_000.0 / 250_000)
    private val histA = FloatArray(2 * 64)
    private var posA = 0
    private var phaseA = 0
    private var prevRe = 0f
    private var prevIm = 0f
    private var deEmph = 0f

    // SSB: the 50 kS/s channel goes through a band-pass made from a low-pass shifted to the middle of the sideband.
    private val hSsb = lowPass(SSB_TAPS, 1_200.0 / 50_000)
    private var ssbMode: ListenMode? = null
    private var ssbRe = FloatArray(SSB_TAPS)
    private var ssbIm = FloatArray(SSB_TAPS)
    private val histSRe = FloatArray(2 * SSB_TAPS)
    private val histSIm = FloatArray(2 * SSB_TAPS)
    private var posS = 0

    // Notch state: one pole-zero pair per notch, at 250 kS/s, set again when a notch or the tuning moves.
    private var notchKey: DoubleArray? = null
    private var notchOff = Double.NaN
    private var nRe = DoubleArray(0)
    private var nIm = DoubleArray(0)
    private var nxRe = DoubleArray(0)
    private var nxIm = DoubleArray(0)
    private var nyRe = DoubleArray(0)
    private var nyIm = DoubleArray(0)

    private fun setNotches(list: DoubleArray, offset: Double) {
        notchKey = list
        notchOff = offset
        val inChannel = list.map { it - offset }.filter { kotlin.math.abs(it) < 120_000 }
        nRe = DoubleArray(inChannel.size) { cos(2 * PI * inChannel[it] / 250_000) }
        nIm = DoubleArray(inChannel.size) { sin(2 * PI * inChannel[it] / 250_000) }
        nxRe = DoubleArray(inChannel.size); nxIm = DoubleArray(inChannel.size)
        nyRe = DoubleArray(inChannel.size); nyIm = DoubleArray(inChannel.size)
    }

    /**
     * The notches on one 250 kS/s sample: y = x - e^jw x[-1] + r e^jw y[-1], a zero on the unit circle at the notch and
     * a pole just inside it, so only a few hundred hertz around it are taken away.
     */
    private fun notch(re: Float, im: Float, out: FloatArray) {
        var xr = re.toDouble()
        var xi = im.toDouble()
        for (k in nRe.indices) {
            val cr = nRe[k]
            val ci = nIm[k]
            val pr = nxRe[k] * cr - nxIm[k] * ci
            val pi = nxRe[k] * ci + nxIm[k] * cr
            val qr = nyRe[k] * cr - nyIm[k] * ci
            val qi = nyRe[k] * ci + nyIm[k] * cr
            val yr = xr - pr + NOTCH_R * qr
            val yi = xi - pi + NOTCH_R * qi
            nxRe[k] = xr; nxIm[k] = xi
            nyRe[k] = yr; nyIm[k] = yi
            xr = yr; xi = yi
        }
        out[0] = xr.toFloat(); out[1] = xi.toFloat()
    }
    private val notched = FloatArray(2)

    // Audio post-processing state.
    private var amAvg = 0f
    private var agc = 1f
    private var hpIn = 0f
    private var hpOut = 0f
    private var power = 0.0
    private var powerN = 0
    private val out = FloatArray(4096)
    private var outN = 0

    // Digital voice: the DMR and dPMR decoders of the PMR446 tool, fed with this receiver's discriminator; their 8 kHz
    // voice is brought to the 50 kS/s audio and queued, then played in step with the samples coming in.
    private val digital = PmrChannel(0L, 0.0, false) { e -> if (e is PmrEvent.Voice) queueVoice(e.pcm) else dvEvent?.invoke(e) }
    /** What the digital decoders heard besides voice (calls, identities), for the screen. */
    @Volatile var dvEvent: ((PmrEvent) -> Unit)? = null
    private val dvBuf = FloatArray(AUDIO_RATE)
    private var dvWrite = 0
    private var dvRead = 0
    private var dvQueued = 0
    private val dvGain = 1.6f

    private var dvDropped = 0
    private var dvDropLogMs = 0L

    private fun queueVoice(pcm: FloatArray) {
        // 8 kHz to 50 kHz: linear interpolation, 6.25 output samples per input one.
        val n = pcm.size * AUDIO_RATE / 8_000
        for (j in 0 until n) {
            if (dvQueued >= dvBuf.size) {
                // Voice comes faster than it is played: said in the debug log (issue #26), at most every 2 s.
                dvDropped += n - j
                val now = System.currentTimeMillis()
                if (now - dvDropLogMs > 2_000) { dvDropLogMs = now; com.allnetworktools.util.DebugLog.w("DMR", "Listen: voice queue full, $dvDropped samples dropped"); dvDropped = 0 }
                break
            }
            val x = j * 8_000.0 / AUDIO_RATE
            val i = x.toInt()
            val f = (x - i).toFloat()
            val a = pcm[i]
            val b = pcm[minOf(i + 1, pcm.size - 1)]
            dvBuf[dvWrite] = a + (b - a) * f
            dvWrite = (dvWrite + 1) % dvBuf.size
            dvQueued++
        }
    }

    private fun lowPass(taps: Int, cutoff: Double): FloatArray {
        val raw = DoubleArray(taps) { i ->
            val m = i - (taps - 1) / 2.0
            val sinc = if (m == 0.0) 2 * cutoff else sin(2 * PI * cutoff * m) / (PI * m)
            sinc * (0.42 - 0.5 * cos(2 * PI * i / (taps - 1)) + 0.08 * cos(4 * PI * i / (taps - 1)))
        }
        val sum = raw.sum()
        return FloatArray(taps) { (raw[it] / sum).toFloat() }
    }

    fun feed(buf: ByteArray, len: Int) {
        val m = mode
        if (h2Mode != m) retuneStage2(m)
        val nl = notchesHz
        if (nl !== notchKey || offsetHz != notchOff) setNotches(nl, offsetHz)
        val step = -2 * PI * offsetHz / sampleRate
        if (step != lastStep) { lastStep = step; stRe = cos(step); stIm = sin(step) }
        var i = 0
        var norm = 0
        while (i + 1 < len) {
            val re = buf[i] / 128f
            val im = buf[i + 1] / 128f
            i += 2
            // Mix the channel to zero.
            val mr = (re * phRe - im * phIm).toFloat()
            val mi = (re * phIm + im * phRe).toFloat()
            val nr = phRe * stRe - phIm * stIm
            phIm = phRe * stIm + phIm * stRe
            phRe = nr
            if (++norm == 1024) { norm = 0; val g = 1.0 / hypot(phRe, phIm); phRe *= g; phIm *= g }
            hist1Re[pos1] = mr; hist1Re[pos1 + h1.size] = mr
            hist1Im[pos1] = mi; hist1Im[pos1 + h1.size] = mi
            if (++pos1 == h1.size) pos1 = 0
            if (++phase1 == d1) {
                phase1 = 0
                var ar = 0f
                var ai = 0f
                for (k in h1.indices) { ar += h1[k] * hist1Re[pos1 + k]; ai += h1[k] * hist1Im[pos1 + k] }
                power += ar * ar + ai * ai
                powerN++
                if (nRe.isEmpty()) stage2(ar, ai, m) else { notch(ar, ai, notched); stage2(notched[0], notched[1], m) }
            }
        }
        if (outN > 0) { onAudio(out, outN); outN = 0 }
        if (powerN >= 2500) {
            levelDb = 10 * log10((power / powerN).coerceAtLeast(1e-12))
            power = 0.0; powerN = 0
        }
    }

    private fun retuneStage2(m: ListenMode) {
        h2Mode = m
        h2 = lowPass(64, (if (m == ListenMode.Wfm) 100_000 else m.bandwidthHz.coerceAtLeast(3_200) / 2 + 1_500).toDouble() / 250_000)
        pos2 = 0; phase2 = 0
        hist2Re.fill(0f); hist2Im.fill(0f)
        histA.fill(0f); posA = 0; phaseA = 0
        amAvg = 0f; agc = 1f; deEmph = 0f
        // Voice queued in DMR/dPMR mode is not for another mode (nor for a later return to it).
        dvQueued = 0; dvRead = 0; dvWrite = 0
        if (m == ListenMode.Usb || m == ListenMode.Lsb) {
            // A low-pass moved to the middle of the sideband (1.5 kHz above or below the carrier).
            val w = 2 * PI * 1_500.0 / 50_000 * (if (m == ListenMode.Usb) 1 else -1)
            ssbRe = FloatArray(SSB_TAPS) { (hSsb[it] * cos(w * it)).toFloat() }
            ssbIm = FloatArray(SSB_TAPS) { (hSsb[it] * sin(w * it)).toFloat() }
            histSRe.fill(0f); histSIm.fill(0f); posS = 0
            ssbMode = m
        }
    }

    private fun emit(x: Float) {
        val sq = squelchDb
        val b = boostDb
        out[outN++] = if (sq != null && levelDb < sq) 0f else if (b <= 0f) x else softLimit(x * dbGain(b))
        if (outN == out.size) { onAudio(out, outN); outN = 0 }
    }

    private fun stage2(re: Float, im: Float, m: ListenMode) {
        if (m == ListenMode.Wfm) {
            // Discriminator at 250 kS/s: the phase step is the instantaneous frequency.
            val dr = re * prevRe + im * prevIm
            val di = im * prevRe - re * prevIm
            prevRe = re; prevIm = im
            val f = atan2(di, dr) * (250_000f / (2 * PI.toFloat() * 75_000f))
            // 50 µs de-emphasis (Europe).
            deEmph += (1f - exp(-1f / (50e-6f * 250_000f))) * (f - deEmph)
            histA[posA] = deEmph; histA[posA + hAudio.size] = deEmph
            if (++posA == hAudio.size) posA = 0
            if (++phaseA == 5) {
                phaseA = 0
                var a = 0f
                for (k in hAudio.indices) a += hAudio[k] * histA[posA + k]
                emit((a * 0.8f).coerceIn(-1f, 1f))
            }
            return
        }
        hist2Re[pos2] = re; hist2Re[pos2 + h2.size] = re
        hist2Im[pos2] = im; hist2Im[pos2 + h2.size] = im
        if (++pos2 == h2.size) pos2 = 0
        if (++phase2 < 5) return
        phase2 = 0
        var ar = 0f
        var ai = 0f
        for (k in h2.indices) { ar += h2[k] * hist2Re[pos2 + k]; ai += h2[k] * hist2Im[pos2 + k] }
        when (m) {
            ListenMode.Nfm -> {
                val dr = ar * prevRe + ai * prevIm
                val di = ai * prevRe - ar * prevIm
                prevRe = ar; prevIm = ai
                // ±5 kHz of deviation to full scale; a 300 Hz high-pass takes the sub-audible tones out.
                val f = atan2(di, dr) * (50_000f / (2 * PI.toFloat() * 5_000f))
                hpOut = 0.96f * (hpOut + f - hpIn); hpIn = f
                emit((hpOut * 0.8f).coerceIn(-1f, 1f))
            }
            ListenMode.Dv -> {
                val dr = ar * prevRe + ai * prevIm
                val di = ai * prevRe - ar * prevIm
                prevRe = ar; prevIm = ai
                // The discriminator in Hz goes to the DMR and dPMR decoders, which hand back voice (see [voice]).
                digital.pushFm(atan2(di, dr) * (50_000f / (2 * PI.toFloat())))
                // Between voice frames, silence: digital signalling played as FM is only a buzz.
                if (dvQueued <= 0) emit(0f) else { emit(dvBuf[dvRead] * dvGain); dvRead = (dvRead + 1) % dvBuf.size; dvQueued-- }
            }
            ListenMode.Am -> {
                val env = sqrt(ar * ar + ai * ai)
                amAvg += 0.002f * (env - amAvg)
                // The carrier level sets the gain: a constant loudness whatever the signal strength.
                emit(((env - amAvg) / amAvg.coerceAtLeast(1e-4f) * 0.7f).coerceIn(-1f, 1f))
            }
            else -> {
                histSRe[posS] = ar; histSRe[posS + SSB_TAPS] = ar
                histSIm[posS] = ai; histSIm[posS + SSB_TAPS] = ai
                if (++posS == SSB_TAPS) posS = 0
                // Real part of the filtered analytic signal: the sideband, not shifted.
                var y = 0f
                for (k in 0 until SSB_TAPS) y += ssbRe[k] * histSRe[posS + SSB_TAPS - 1 - k] - ssbIm[k] * histSIm[posS + SSB_TAPS - 1 - k]
                // Fast attack, slow release: the loudest voices do not clip and the quiet ones come up.
                if (kotlin.math.abs(y * agc) > 0.6f) agc *= 0.9f else agc *= 1.00003f
                agc = agc.coerceIn(1f, 50_000f)
                emit((y * agc).coerceIn(-1f, 1f))
            }
        }
    }

    companion object {
        /** Pole radius of the notches: about 400 Hz wide at 250 kS/s. */
        private const val NOTCH_R = 0.99

        fun dbGain(db: Float): Float = Math.pow(10.0, db / 20.0).toFloat()

        /** Linear up to 0.7, then a tanh knee towards 1: loud peaks are rounded, never clipped hard. */
        fun softLimit(x: Float): Float {
            val a = kotlin.math.abs(x)
            if (a <= 0.7f) return x
            val y = 0.7f + 0.3f * kotlin.math.tanh((a - 0.7f) / 0.3f)
            return if (x < 0) -y else y
        }

        const val SAMPLE_RATE = 2_000_000
        const val AUDIO_RATE = 50_000
        private const val SSB_TAPS = 255
    }
}
