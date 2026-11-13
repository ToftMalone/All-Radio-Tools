package com.allnetworktools.data.sdr

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.allnetworktools.util.DebugLog

/** The make of radio plugged in, with what the screens need to know before it is opened. */
enum class SdrKind(
    val label: String,
    /** Its own gain steps (null: the HackRF's LNA / VGA / amplifier lines are shown). */
    val gainSteps: List<Int>?,
    /** Unit shown next to a gain step ("dB", or nothing for an index). */
    val gainUnit: String,
    val biasTee: Boolean,
) {
    HackRf("HackRF", null, "dB", true),
    RtlSdr("RTL-SDR", R82xx.GAINS.map { it / 10 }.distinct(), "dB", true),
    /** libairspy's sensitivity presets, 0 (lowest) to 21 (highest). */
    Airspy("Airspy", (0..21).toList(), "", true),
    Pluto("PlutoSDR", (0..73 step 3).toList() + 73, "dB", false);

    /** Lowest and highest frequency it tunes; the RTL-SDR Blog V4 up-converts the HF band. */
    fun minHz(product: String?): Long = when (this) {
        HackRf -> 1_000_000L
        RtlSdr -> if (product?.startsWith("Blog V4") == true) 500_000L else 24_000_000L
        Airspy -> 24_000_000L
        Pluto -> 70_000_000L
    }

    fun maxHz(): Long = when (this) {
        HackRf, Pluto -> 6_000_000_000L
        RtlSdr -> 1_766_000_000L
        Airspy -> 1_750_000_000L
    }

    /** Whether the driver can deliver [rate] samples per second (directly or by resampling a rate the phone can take). */
    fun canRate(rate: Int, product: String?): Boolean = when (this) {
        HackRf -> rate <= 20_000_000
        RtlSdr -> rate in 225_001..300_000 || rate in 900_001..3_200_000
        Airspy -> {
            val native = if (product?.contains("mini", ignoreCase = true) == true) listOf(3_000_000, 6_000_000) else listOf(2_500_000, 10_000_000)
            rate in native || native.any { it in rate..3_000_000 && IqResampler.possible(it, rate) }
        }
        Pluto -> (1..10).map { rate * it }.firstOrNull { it >= 2_100_000 }?.let { it <= 5_000_000 && IqResampler.possible(it, rate) } == true
    }

    /** Whether a tool needing [rate] on one of [bands] can run on this radio. */
    fun canRun(rate: Int, bands: List<LongRange>, product: String?): Boolean =
        canRate(rate, product) && (bands.isEmpty() || bands.any { b -> SdrSettings.tunable(this, product).let { b.first >= it.first && b.last <= it.last } })

    /** The spectrum widths, in MHz, this radio can show without resampling a wide stream on the phone. */
    fun spectrumSpans(product: String?): List<Int> = when (this) {
        HackRf -> listOf(2, 5, 10, 20)
        RtlSdr -> listOf(1, 2, 3)
        Airspy -> if (product?.contains("mini", ignoreCase = true) == true) listOf(2, 3, 6) else listOf(2, 10)
        Pluto -> listOf(2, 3, 4, 5)
    }
}

/**
 * Settings that belong to the radio rather than to a tool: its own gain (for radios that are not a HackRF) and the
 * antenna power (bias tee). The radio in use is kept so a change applies at once.
 */
object SdrSettings {
    /** The make plugged in now, set by [SdrRepository] as it finds radios. */
    var kind by mutableStateOf<SdrKind?>(null)
        internal set
    var product by mutableStateOf<String?>(null)
        internal set

    /** Automatic gain (the tools' own loop) or the value picked in the radio's own steps. */
    var nativeAuto by mutableStateOf(true)
    var nativeGain by mutableIntStateOf(30)

    /** Power on the antenna port, for an active antenna or a preamplifier. Off at every start of the app. */
    var biasTee by mutableStateOf(false)

    /**
     * What an up- or down-converter in front of the radio adds to the frequency, in Hz: +125 MHz for a Ham It Up,
     * the converter's LO taken away for a downconverter. The tools keep showing the real frequency; the radio is tuned
     * this much higher. 0 without a converter, at every start of the app.
     */
    var converterHz by mutableLongStateOf(0L)

    /** The real frequencies the radio reaches through the converter. */
    fun tunable(kind: SdrKind, product: String?): LongRange =
        (kind.minHz(product) - converterHz).coerceAtLeast(0L)..(kind.maxHz() - converterHz).coerceAtLeast(0L)

    @Volatile internal var active: SdrRadio? = null

    /** Applies [biasTee] to the radio in use, if any. Call off the main thread. */
    fun applyBiasTee() {
        val r = active ?: return
        val ok = runCatching { r.setAntennaPower(biasTee) }.getOrDefault(false)
        DebugLog.i("SDR", "Bias tee ${if (biasTee) "on" else "off"} on ${r.caps.name}: $ok")
    }
}

/**
 * Wraps the radio a tool opens: for a radio with its own gain scale, a picked value replaces the HackRF-scale gains the
 * tools send; the bias tee is applied as soon as the radio is open.
 */
internal class ManagedRadio(private val inner: SdrRadio, private val kind: SdrKind) : SdrRadio by inner {
    private fun native(): Boolean? =
        if (kind.gainSteps != null && !SdrSettings.nativeAuto) inner.setNativeGain(SdrSettings.nativeGain) else null

    override fun setLnaGain(db: Int): Boolean = native() ?: inner.setLnaGain(db)
    override fun setVgaGain(db: Int): Boolean = native() ?: inner.setVgaGain(db)
    override fun setAmp(on: Boolean): Boolean = native() ?: inner.setAmp(on)

    override fun setSampleRate(hz: Int, filterHz: Int): Boolean =
        inner.setSampleRate(hz, filterHz).also { DebugLog.i("SDR", "${inner.caps.name}: sample rate $hz -> $it") }

    /** The real frequency last asked for, which a transmission is checked against. */
    @Volatile private var lastHz = 0L

    /** Never past the licence and the band, whichever tool asks: see [TxPolicy]. */
    override fun startTx(fill: (ByteArray) -> Unit, onError: (String) -> Unit): Boolean {
        val e = TxPolicy.declared
        val why = when {
            e == null -> "no emission declared"
            // The signal must lie in what the radio is tuned to, so the declaration is the real one.
            kotlin.math.abs((e.lowHz + e.highHz) / 2 - lastHz) > 1_200_000L -> "emission away from the tuned frequency"
            else -> TxPolicy.refuse(e)
        }
        if (why != null) { DebugLog.w("SDR", "TX refused (LO $lastHz Hz, $e): $why"); onError(why); return false }
        DebugLog.i("SDR", "${inner.caps.name}: transmitting $e")
        return inner.startTx(fill, onError)
    }

    override fun stopStreaming() {
        DebugLog.i("SDR", "${inner.caps.name}: idle")
        inner.stopStreaming()
    }

    override fun setFrequency(hz: Long): Boolean {
        lastHz = hz
        val tuned = hz + SdrSettings.converterHz
        return inner.setFrequency(tuned).also { if (!it) DebugLog.w("SDR", "${inner.caps.name}: frequency $tuned refused") }
    }

    override fun startRx(onSamples: (ByteArray, Int) -> Unit, onError: (String) -> Unit) {
        DebugLog.i("SDR", "${inner.caps.name}: start receiving")
        inner.startRx(onSamples) { msg -> DebugLog.e("SDR", "${inner.caps.name}: $msg"); onError(msg) }
    }

    override fun close() {
        if (SdrSettings.active === this) SdrSettings.active = null
        DebugLog.i("SDR", "${inner.caps.name}: closed")
        inner.close()
    }
}
