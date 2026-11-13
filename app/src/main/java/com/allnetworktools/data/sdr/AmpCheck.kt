package com.allnetworktools.data.sdr

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.log10
import kotlin.math.max

/**
 * The HackRF's +14 dB amplifier, as found working or not this session. A dead amplifier (a common failure after a strong
 * signal or a transmit mishap) cuts the receive path: with it on, almost nothing comes through.
 */
object AmpHealth {
    /** Found dead this session: the tools leave it off and say why. */
    var dead by mutableStateOf(false)
        internal set

    /** The user wants to try it again (a new radio, or a doubt). */
    fun reset() { dead = false }

    internal fun markDead() { dead = true }
}

/**
 * Watches the amplifier from the samples. At the same LNA and VGA gains a working amplifier raises the level (up to
 * 14 dB, less when the converter's own noise dominates); a dead one drops it far below. So the level with the
 * amplifier on is compared with the level with it off, at the same gains:
 *
 * - the comparison uses what the user did (switching it), and when the amplifier is on and the level is close to
 *   nothing, a short probe: the amplifier off for one measuring window, then back on;
 * - a comparison where the level is [DEAD_DB] or more lower with the amplifier on is one strike; [STRIKES] strikes
 *   (several tries, each at least [PROBE_GAP_S] apart) and it is declared dead; a comparison where it helps clears them.
 */
class AmpCheck {
    /** What the caller must do with the amplifier now. */
    enum class Action { None, ProbeOff, Restore, Dead }

    private var key: Triple<Boolean, Int, Int>? = null
    private var since = 0.0
    private var sumSq = 0.0
    private var sumI = 0.0
    private var sumQ = 0.0
    private var n = 0L
    private var windowStart = 0.0
    /** Latest level per (amp, lna, vga), with when it was measured. */
    private val levels = HashMap<Triple<Boolean, Int, Int>, Pair<Double, Double>>()
    private var probing = false
    private var lastProbe = -1e9
    var strikes = 0
        private set

    /** Interleaved 8-bit I/Q, with the gains the radio has now. */
    fun feed(buf: ByteArray, len: Int, nowS: Double, ampOn: Boolean, lna: Int, vga: Int): Action {
        if (AmpHealth.dead) return Action.None
        val k = Triple(ampOn, lna, vga)
        if (k != key) { key = k; since = nowS; clear(nowS) }
        // What arrives right after a change still carries the old setting.
        if (nowS - since < SETTLE_S) { clear(nowS); return Action.None }
        var i = 0
        while (i + 1 < len) {
            val a = buf[i].toInt(); val b = buf[i + 1].toInt()
            sumI += a; sumQ += b; sumSq += a * a + b * b; n++
            i += 6
        }
        if (nowS - windowStart < WINDOW_S || n == 0L) return Action.None
        val mi = sumI / n; val mq = sumQ / n
        val rms = 10 * log10(max(sumSq / n - mi * mi - mq * mq, 1e-6) / (128.0 * 128.0))
        clear(nowS)
        levels[k] = rms to nowS

        val other = levels[Triple(!ampOn, lna, vga)]?.takeIf { nowS - it.second < 60 }
        if (other != null) {
            val on = if (ampOn) rms else other.first
            val off = if (ampOn) other.first else rms
            if (on - off <= -DEAD_DB) {
                strikes++
                // Forget this pair so the next strike needs a new try.
                levels.remove(Triple(true, lna, vga)); levels.remove(Triple(false, lna, vga))
                if (strikes >= STRIKES) { AmpHealth.markDead(); probing = false; return Action.Dead }
            } else if (on - off >= HEALTHY_DB) strikes = 0
        }
        if (probing && !ampOn) { probing = false; return Action.Restore }
        // The amplifier is on and almost nothing comes through: is it the amplifier?
        if (ampOn && !probing && rms < QUIET_DBFS && nowS - lastProbe >= PROBE_GAP_S) {
            probing = true
            lastProbe = nowS
            return Action.ProbeOff
        }
        return Action.None
    }

    private fun clear(nowS: Double) { sumSq = 0.0; sumI = 0.0; sumQ = 0.0; n = 0; windowStart = nowS }

    companion object {
        const val SETTLE_S = 0.15
        const val WINDOW_S = 0.25
        /** A level this low with the amplifier on (DC offset taken away) is "almost nothing". */
        const val QUIET_DBFS = -35.0
        const val DEAD_DB = 6.0
        const val HEALTHY_DB = 2.0
        const val STRIKES = 3
        const val PROBE_GAP_S = 15.0
    }
}
