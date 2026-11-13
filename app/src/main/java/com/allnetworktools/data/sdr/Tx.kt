package com.allnetworktools.data.sdr

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.allnetworktools.R
import com.allnetworktools.util.S

/** Why a transmission is made: on an amateur band (licence needed) or on a licence-free short-range band (ISM / SRD). */
enum class TxUse { Amateur, Ism }

/** What is about to be transmitted: the frequencies the signal occupies, and on what grounds. */
data class Emission(val lowHz: Long, val highHz: Long, val use: TxUse) {
    companion object {
        /** A signal [lowOff] below to [highOff] above [hz]. */
        fun around(hz: Long, lowOff: Int, highOff: Int, use: TxUse) = Emission(hz - lowOff, hz + highOff, use)
    }
}

/**
 * The operator's licence and the transmit settings, kept on the phone. Transmitting on the amateur bands needs a call
 * sign and the box ticked; the licence-free bands do not.
 */
object TxSettings {
    var callsign by mutableStateOf("")
        private set
    var licensed by mutableStateOf(false)
        private set
    /** HackRF transmit gain, 0 to 47 dB; low by default. */
    var txGain by mutableIntStateOf(20)
        private set
    var txAmp by mutableStateOf(false)
        private set

    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        val p = context.getSharedPreferences("tx", Context.MODE_PRIVATE)
        prefs = p
        callsign = p.getString("callsign", "") ?: ""
        licensed = p.getBoolean("licensed", false)
        txGain = p.getInt("gain", 20)
        txAmp = p.getBoolean("amp", false)
    }

    fun updateCallsign(v: String) { callsign = v.uppercase().filter { it.isLetterOrDigit() || it == '/' || it == '-' }.take(12); prefs?.edit()?.putString("callsign", callsign)?.apply() }
    fun updateLicensed(v: Boolean) { licensed = v; prefs?.edit()?.putBoolean("licensed", v)?.apply() }
    fun updateGain(v: Int) { txGain = v.coerceIn(0, 47); prefs?.edit()?.putInt("gain", txGain)?.apply() }
    fun updateAmp(v: Boolean) { txAmp = v; prefs?.edit()?.putBoolean("amp", v)?.apply() }

    /** The call sign without SSID or portable suffix, when it looks like one (a prefix, a digit, a suffix). */
    val validCallsign: Boolean get() = TxPolicy.isCallsign(callsign)

    /** Ready to transmit on the amateur bands. */
    val amateurReady: Boolean get() = licensed && validCallsign

    /** A value the phone keeps for a tool (the Meshtastic node number…), made once. */
    fun intOnce(key: String, make: () -> Int): Int {
        val p = prefs ?: return make()
        if (p.contains(key)) return p.getInt(key, 0)
        return make().also { p.edit().putInt(key, it).apply() }
    }

    fun string(key: String, default: String): String = prefs?.getString(key, default) ?: default
    fun putString(key: String, v: String) { prefs?.edit()?.putString(key, v)?.apply() }

    internal fun setForTest(call: String, lic: Boolean) { callsign = call; licensed = lic }
}

/**
 * What may be transmitted: amateur bands with a licence and a call sign, licence-free short-range bands for the
 * low-power mesh radios, nothing else. The bands are those of the three ITU regions together; the local band plan and
 * power limits stay the operator's responsibility.
 */
object TxPolicy {
    /** Amateur allocations, all regions together. */
    val AmateurBands: List<LongRange> = listOf(
        1_810_000L..2_000_000L, 3_500_000L..4_000_000L, 5_351_500L..5_366_500L, 7_000_000L..7_300_000L,
        10_100_000L..10_150_000L, 14_000_000L..14_350_000L, 18_068_000L..18_168_000L, 21_000_000L..21_450_000L,
        24_890_000L..24_990_000L, 28_000_000L..29_700_000L, 50_000_000L..54_000_000L, 70_000_000L..70_500_000L,
        144_000_000L..148_000_000L, 222_000_000L..225_000_000L, 430_000_000L..440_000_000L, 902_000_000L..928_000_000L,
        1_240_000_000L..1_300_000_000L, 2_300_000_000L..2_450_000_000L, 3_400_000_000L..3_475_000_000L,
        5_650_000_000L..5_925_000_000L,
    )

    /** Licence-free short-range bands the mesh radios use (Europe 433 and 868 MHz, Americas 915 MHz). */
    val IsmBands: List<LongRange> = listOf(
        433_050_000L..434_790_000L, 863_000_000L..870_000_000L, 902_000_000L..928_000_000L,
    )

    /** What [startTx][SdrRadio.startTx] checks against; set by the tool just before transmitting. */
    @Volatile var declared: Emission? = null

    private val CALL = Regex("^([A-Z0-9]{1,4}/)?[A-Z0-9]{0,2}[A-Z][0-9][A-Z0-9]{0,3}[A-Z](/[A-Z0-9]{1,4})?(-[0-9]{1,2})?$")
    private val CALL_DIGIT_FIRST = Regex("^([A-Z0-9]{1,4}/)?[0-9][A-Z][0-9][A-Z0-9]{0,3}[A-Z](/[A-Z0-9]{1,4})?(-[0-9]{1,2})?$")

    fun isCallsign(c: String): Boolean = c.length in 3..12 && (CALL.matches(c) || CALL_DIGIT_FIRST.matches(c))

    fun inBands(e: Emission, bands: List<LongRange>) = bands.any { e.lowHz >= it.first && e.highHz <= it.last }

    /**
     * Null when [e] may go out, else why not, in the user's language. [ignoreLicence] leaves out the call sign and the
     * licence box, which the licence dialog asks for when the user transmits.
     */
    fun refuse(e: Emission, ignoreLicence: Boolean = false): String? = when {
        SdrSettings.converterHz != 0L -> S(R.string.tx_no_converter)
        !ignoreLicence && e.use == TxUse.Amateur && !TxSettings.validCallsign -> S(R.string.tx_need_callsign)
        !ignoreLicence && e.use == TxUse.Amateur && !TxSettings.licensed -> S(R.string.tx_need_licence)
        e.use == TxUse.Amateur && !inBands(e, AmateurBands) -> S(R.string.tx_out_of_band)
        e.use == TxUse.Ism && !inBands(e, IsmBands) -> S(R.string.tx_out_of_band)
        else -> null
    }
}
