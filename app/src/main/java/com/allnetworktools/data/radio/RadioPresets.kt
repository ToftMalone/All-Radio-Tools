package com.allnetworktools.data.radio

import kotlin.math.roundToLong
import com.allnetworktools.R
import com.allnetworktools.util.S

/** One frequency of a preset list; the transmit side is derived from [shiftHz] and the "listen only" choice. */
data class PresetChannel(
    val name: String,
    val rxHz: Long,
    /** Transmit minus receive, 0 for simplex. */
    val shiftHz: Long = 0,
    val wide: Boolean = true,
    val tone: Tone = Tone.None,
    /** Downlinks and the like: never transmit, whatever the user chose. */
    val listenOnly: Boolean = false,
)

/** A ready-made list of memories: licence-free bands, marine VHF, amateur calling channels and repeater outputs. */
data class RadioPreset(
    val id: String,
    val title: String,
    val subtitle: String,
    /** Marine channels and the like: the app never lets the radio transmit on them. */
    val alwaysListenOnly: Boolean,
    /** Shown under the list; the rules that apply to transmitting there. */
    val note: String,
    val channels: List<PresetChannel>,
) {
    /** The memories, starting at [firstSlot]; transmit is blocked when [listenOnly] (or the preset demands it). */
    fun toChannels(firstSlots: List<Int>, listenOnly: Boolean, power: RadioPower = RadioPower.Low): List<RadioChannel> {
        val block = listenOnly || alwaysListenOnly
        return channels.zip(firstSlots).map { (c, slot) ->
            RadioChannel(
                slot = slot, rxHz = c.rxHz, txHz = if (block || c.listenOnly) null else c.rxHz + c.shiftHz, name = c.name,
                rxTone = Tone.None, txTone = c.tone, power = power, wide = c.wide, scan = true,
            )
        }
    }
}

object RadioPresets {
    private fun mhz(v: Double) = (v * 1_000_000).roundToLong()

    private val pmr = RadioPreset(
        "pmr446", "PMR446", S(R.string.n_16_channels_446_00625_to_446), alwaysListenOnly = false,
        note = S(R.string.transmitting_on_pmr446_needs_a_type),
        channels = (0 until 16).map { PresetChannel("PMR ${it + 1}", mhz(446.00625) + it * 12_500L, wide = false) },
    )

    private val lpd = RadioPreset(
        "lpd433", "LPD433", S(R.string.n_69_channels_433_075_to_434), alwaysListenOnly = false,
        note = S(R.string.licence_free_band_at_10_mw),
        channels = (0 until 69).map { PresetChannel("LPD ${it + 1}", mhz(433.075) + it * 25_000L) },
    )

    private val marine = RadioPreset(
        "marine", S(R.string.marine_vhf), S(R.string.simplex_channels_156_3_to_156), alwaysListenOnly = true,
        note = S(R.string.listen_only_transmitting_on_marine_bands),
        channels = listOf(
            6 to 156.300, 8 to 156.400, 9 to 156.450, 10 to 156.500, 11 to 156.550, 12 to 156.600, 13 to 156.650, 14 to 156.700,
            15 to 156.750, 16 to 156.800, 17 to 156.850, 67 to 156.375, 68 to 156.425, 69 to 156.475, 71 to 156.575, 72 to 156.625,
            73 to 156.675, 74 to 156.725, 77 to 156.875,
        ).sortedBy { it.second }.map { (ch, f) -> PresetChannel("MAR $ch", mhz(f)) },
    )

    private val relays2m = RadioPreset(
        "relais2m", S(R.string.n_2_m_amateur_repeaters), S(R.string.rv48_to_rv63_outputs_145_600), alwaysListenOnly = false,
        note = S(R.string.transmitting_needs_an_amateur_radio_licence),
        channels = (0 until 16).map { PresetChannel("RV${48 + it}", mhz(145.600) + it * 12_500L, shiftHz = -600_000L, wide = false) },
    )

    private val amateur = RadioPreset(
        "radioamateur", S(R.string.amateur_radio_calling_and_specials), S(R.string.n_2_m_and_70_cm_calling), alwaysListenOnly = false,
        note = S(R.string.transmitting_needs_an_amateur_radio_licence_2),
        channels = listOf(
            PresetChannel("APPEL2M", mhz(145.500), wide = false),
            PresetChannel("APPEL70", mhz(433.500), wide = false),
            PresetChannel("APRS", mhz(144.800), wide = false),
            PresetChannel("ISS VOX", mhz(145.800), wide = false, listenOnly = true),
            PresetChannel("ISS PKT", mhz(145.825), wide = false, listenOnly = true),
        ),
    )

    val all = listOf(pmr, lpd, marine, relays2m, amateur)
}
