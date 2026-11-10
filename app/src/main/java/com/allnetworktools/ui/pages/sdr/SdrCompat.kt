package com.allnetworktools.ui.pages.sdr

import com.allnetworktools.data.sdr.SdrDevice
import com.allnetworktools.model.Tool

/** What an SDR tool asks of the radio: a sample rate, and the bands it listens on (any one of them is enough). */
private class SdrNeed(val rate: Int, vararg mhz: Pair<Double, Double>) {
    val bands = mhz.map { (a, b) -> (a * 1e6).toLong()..(b * 1e6).toLong() }
}

private val NEEDS: Map<Tool, SdrNeed> = mapOf(
    Tool.Adsb to SdrNeed(2_000_000, 1089.0 to 1091.0),
    Tool.Sonde to SdrNeed(2_000_000, 400.0 to 406.0),
    Tool.Ais to SdrNeed(2_304_000, 161.9 to 162.1),
    Tool.Meteor to SdrNeed(2_304_000, 137.0 to 138.0),
    Tool.Aprs to SdrNeed(2_000_000, 144.7 to 144.9),
    Tool.Acars to SdrNeed(2_400_000, 130.0 to 132.0),
    Tool.Fpv to SdrNeed(20_000_000, 5650.0 to 5950.0),
    Tool.Tpms to SdrNeed(2_000_000, 314.0 to 316.0, 433.0 to 435.0),
    Tool.Pocsag to SdrNeed(2_000_000, 169.0 to 170.0, 439.0 to 467.0),
    Tool.Cubesat to SdrNeed(2_000_000, 144.0 to 146.0, 435.0 to 438.0),
    Tool.Lpwan to SdrNeed(2_000_000, 867.0 to 869.0),
    Tool.Meshtastic to SdrNeed(2_000_000, 433.0 to 435.0, 868.0 to 870.0),
    Tool.MeshCore to SdrNeed(2_000_000, 868.0 to 870.0),
    Tool.AntPlus to SdrNeed(4_000_000, 2455.0 to 2459.0),
    Tool.Zigbee to SdrNeed(4_000_000, 2405.0 to 2480.0),
    Tool.Sstv to SdrNeed(2_000_000, 144.0 to 146.0),
    Tool.Zwave to SdrNeed(2_000_000, 868.0 to 870.0),
    Tool.Sarsat to SdrNeed(2_000_000, 406.0 to 406.1),
    Tool.LoraAprs to SdrNeed(2_000_000, 433.0 to 435.0),
    Tool.Pmr to SdrNeed(2_000_000, 446.0 to 446.2),
    Tool.Dect to SdrNeed(4_608_000, 1880.0 to 1900.0),
    Tool.Vdl2 to SdrNeed(2_000_000, 136.0 to 137.0),
    Tool.Dsc to SdrNeed(2_000_000, 156.0 to 157.0),
    Tool.Wmbus to SdrNeed(2_000_000, 868.0 to 869.5),
    Tool.Flarm to SdrNeed(2_000_000, 868.0 to 869.0),
    Tool.Ism to SdrNeed(2_000_000, 314.0 to 316.0, 433.0 to 435.0, 867.0 to 869.0),
    Tool.Listen to SdrNeed(2_000_000),
    Tool.Spectrum to SdrNeed(2_000_000),
)

/** Whether [tool] runs on the radio plugged in; with no radio, every tool is shown. */
fun sdrCompatible(tool: Tool, device: SdrDevice?): Boolean {
    val d = device ?: return true
    val need = NEEDS[tool] ?: return true
    return d.kind.canRun(need.rate, need.bands, d.usb?.productName)
}
