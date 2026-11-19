package com.allnetworktools.ui.pages.sdr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.allnetworktools.R
import com.allnetworktools.model.Tool
import com.allnetworktools.ui.components.AntFilterChip
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.HostInputField
import com.allnetworktools.util.S
import java.util.Locale
import kotlin.math.roundToLong

/**
 * A tool's main channel moved by the user: another region's frequency, or a signal found on the spectrum. The tool's
 * other channels (AIS's second one, FLARM's pair, Z-Wave's groups…) move by as much, so they keep their spacing.
 * [nominalHz] is the channel the tool is built for.
 */
class FreeFrequency(val nominalHz: Long, val minMhz: Double = 1.0, val maxMhz: Double = 6000.0) {
    /** What an unusable frequency is told, with this tool's range. */
    val invalidText: Int get() = if (minMhz >= 100.0) R.string.invalid_frequency_100_to_1000_mhz else R.string.invalid_frequency_1_to_6000_mhz

    var on by mutableStateOf(false)
    var text by mutableStateOf(mhz(nominalHz))

    fun hz(): Long? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in minMhz..maxMhz }?.let { (it * 1e6).roundToLong() }

    /** False only when on with a frequency that cannot be used. */
    val valid: Boolean get() = !on || hz() != null

    /** How far the tool's channels move: 0 unless on with a usable frequency. */
    val shiftHz: Long get() = if (on) hz()?.let { it - nominalHz } ?: 0L else 0L

    /** The main channel as it will be heard. */
    val effectiveHz: Long get() = nominalHz + shiftHz

    fun set(hz: Long) {
        text = mhz(hz)
        on = true
    }

    /** A frequency from the spectrum: the tool's own channels are kept when they already hear it ([covered]). */
    fun offer(hz: Long, covered: Boolean) = if (covered) reset() else set(hz)

    fun reset() {
        on = false
        text = mhz(nominalHz)
    }

    companion object {
        fun mhz(hz: Long): String = "%.4f".format(Locale.US, hz / 1e6).trimEnd('0').let { if (it.endsWith('.')) it + "0" else it }
    }
}

/**
 * The frequency the spectrum's signal analysis found, for the tool it opens: taken once, by that tool, when its page
 * shows. Only a signal off the tool's usual channels is passed on, since the tool already listens there.
 */
object SdrHandoff {
    /** Whether [hz] lies within [halfWidthHz] of one of [centres]. */
    fun near(hz: Long, halfWidthHz: Long, vararg centres: Long) = centres.any { kotlin.math.abs(hz - it) <= halfWidthHz }

    private class Offer(val tool: Tool, val hz: Long, val atMs: Long)
    @Volatile private var pending: Offer? = null

    fun offer(tool: Tool, hz: Long) { pending = Offer(tool, hz, System.currentTimeMillis()) }

    /** The frequency offered to [tool], if it is fresh: a tool opened much later (its page was blocked) starts as usual. */
    fun take(tool: Tool): Long? {
        val p = pending ?: return null
        if (p.tool != tool) return null
        pending = null
        return p.hz.takeIf { System.currentTimeMillis() - p.atMs < FRESH_MS }
    }

    private const val FRESH_MS = 10_000L
}

/** The free frequency setting, for a tool's settings tab. Changes take effect at the next start. */
@Composable
fun FreeFrequencySettings(f: FreeFrequency, running: Boolean, onChange: () -> Unit = {}) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AntFilterChip(S(R.string.sdr_free_frequency), f.on, {
            if (f.on) f.reset() else f.on = true
            onChange()
        })
        if (f.on) {
            if (!running) HostInputField(f.text, { f.text = it; onChange() }, S(R.string.frequency_mhz), Sym.Antenna, keyboardType = KeyboardType.Decimal)
            val hz = f.hz()
            Text(
                if (hz != null) S(R.string.sdr_free_note, FreeFrequency.mhz(hz).replace('.', ','), FreeFrequency.mhz(f.nominalHz).replace('.', ','))
                else S(f.invalidText),
                style = rf(12, 16), color = if (hz != null) cs.onSurfaceVariant else cs.error,
            )
        }
    }
}

/** Applies, once, the frequency the spectrum's analysis passed to [tool]. */
@Composable
fun TakeHandoff(tool: Tool, apply: (Long) -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) { SdrHandoff.take(tool)?.let(apply) }
}
