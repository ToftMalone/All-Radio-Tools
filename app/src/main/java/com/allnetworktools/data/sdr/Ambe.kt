package com.allnetworktools.data.sdr

import kotlin.math.E
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * AMBE+2 at 3600 bit/s (2450 of speech, 1150 of error correction), the vocoder of DMR and dPMR: a 72-bit frame every
 * 20 ms gives 160 samples at 8 kHz. Ported from mbelib 1.3.0 (ambe3600x2450.c, mbelib.c, ecc.c; Copyright (C) 2010
 * mbelib Author, ISC licence): Golay (23,12) on the first two code words, the pseudo-random demodulation of the second,
 * then the fundamental, voicing, gains and spectral amplitudes, and multiband excitation synthesis.
 */
class AmbeDecoder(private val uvQuality: Int = 3) {
    private class Parms {
        var w0 = 0f
        var l = 0
        var vl = IntArray(57)
        var ml = FloatArray(57)
        var log2Ml = FloatArray(57)
        var phil = FloatArray(57)
        var psil = FloatArray(57)
        var gamma = 0f
        var repeat = 0

        fun copyTo(o: Parms) {
            o.w0 = w0; o.l = l; o.gamma = gamma; o.repeat = repeat
            vl.copyInto(o.vl); ml.copyInto(o.ml); log2Ml.copyInto(o.log2Ml); phil.copyInto(o.phil); psil.copyInto(o.psil)
            o.ml[0] = ml[0]
        }
    }

    private val cur = Parms()
    private val prev = Parms()
    private val prevEnh = Parms()
    private val rnd = Random(0x3600)
    private val fr = Array(4) { IntArray(24) }
    private val d = IntArray(49)

    /** What the last frame was, for the caller: errors corrected, and whether it was speech, silence or lost. */
    var lastErrors = 0
        private set

    init { initParms() }

    private fun initParms() {
        prev.w0 = 0.09378f; prev.l = 30; prev.gamma = 0f; prev.repeat = 0
        for (l in 0..56) { prev.ml[l] = 0f; prev.vl[l] = 0; prev.log2Ml[l] = 0f; prev.phil[l] = 0f; prev.psil[l] = (PI / 2).toFloat() }
        prev.copyTo(cur)
        prev.copyTo(prevEnh)
    }

    /** One 72-bit frame as sent (dibit order of DMR and dPMR) → 160 samples at 8 kHz, about ±1. */
    fun decode(bits: IntArray, from: Int, out: FloatArray, outAt: Int = 0) {
        for (r in fr) r.fill(0)
        for (j in 0 until 36) {
            fr[W[j]][X[j]] = bits[from + 2 * j] and 1
            fr[Y[j]][Z[j]] = bits[from + 2 * j + 1] and 1
        }
        val errs = eccC0()
        demodulate()
        val errs2 = errs + eccData()
        lastErrors = errs2
        processData(errs2, out, outAt)
    }

    // ---- error correction (ecc.c)

    private fun golay2312(inp: IntArray, out: IntArray): Int {
        var block = 0L
        for (i in 22 downTo 0) block = (block shl 1) + inp[i]
        var mask = 0x400000L
        var expected = 0
        for (i in 0 until 12) { if (block and mask != 0L) expected = expected xor AmbeTables.golayGenerator[i]; mask = mask shr 1 }
        val syndrome = expected xor (block and 0x7ff).toInt()
        var data = ((block shr 11).toInt()) xor AmbeTables.golayMatrix[syndrome]
        for (i in 22 downTo 11) { out[i] = (data and 2048) shr 11; data = data shl 1 }
        for (i in 10 downTo 0) out[i] = inp[i]
        var e = 0
        for (i in 22 downTo 11) if (out[i] != inp[i]) e++
        return e
    }

    private val gin = IntArray(24)
    private val gout = IntArray(24)

    private fun eccC0(): Int {
        for (j in 0 until 23) gin[j] = fr[0][j + 1]
        val e = golay2312(gin, gout)
        for (j in 0 until 23) fr[0][j + 1] = gout[j]
        return e
    }

    private fun demodulate() {
        var foo = 0
        for (i in 23 downTo 12) foo = (foo shl 1) or fr[0][i]
        val pr = IntArray(24)
        pr[0] = 16 * foo
        for (i in 1 until 24) pr[i] = ((173 * pr[i - 1]) + 13849) % 65536
        for (i in 1 until 24) pr[i] = pr[i] / 32768
        var k = 1
        for (j in 22 downTo 0) { fr[1][j] = fr[1][j] xor pr[k]; k++ }
    }

    private fun eccData(): Int {
        var p = 0
        for (j in 23 downTo 12) d[p++] = fr[0][j]
        for (j in 0 until 23) gin[j] = fr[1][j]
        val e = golay2312(gin, gout)
        for (j in 22 downTo 11) d[p++] = gout[j]
        for (j in 10 downTo 0) d[p++] = fr[2][j]
        for (j in 13 downTo 0) d[p++] = fr[3][j]
        return e
    }

    // ---- parameters (ambe3600x2450.c)

    /** 0 speech or silence, 2 erasure, 3 tone. */
    private fun decodeParms(): Int {
        val t = AmbeTables
        cur.repeat = prev.repeat
        val b0 = (d[0] shl 6) or (d[1] shl 5) or (d[2] shl 4) or (d[3] shl 3) or (d[37] shl 2) or (d[38] shl 1) or d[39]
        var silence = false
        val f0: Float
        val l: Int
        when (b0) {
            in 120..123 -> return 2
            124, 125 -> {
                silence = true
                cur.w0 = (2 * PI / 32).toFloat(); f0 = 1f / 32; l = 14; cur.l = 14
                for (i in 1..l) cur.vl[i] = 0
            }
            126, 127 -> return 3
            else -> { f0 = t.ambeW0table[b0]; cur.w0 = (f0 * 2 * PI).toFloat(); l = t.ambeLtable[b0]; cur.l = l }
        }
        val unvc = 0.2046f / sqrt(cur.w0)
        val b1 = (d[4] shl 4) or (d[5] shl 3) or (d[6] shl 2) or (d[7] shl 1) or d[35]
        for (i in 1..l) {
            val jl = (i.toFloat() * 16f * f0).toInt()
            if (!silence) cur.vl[i] = t.ambeVuv[b1 * 8 + jl]
        }
        val b2 = (d[8] shl 4) or (d[9] shl 3) or (d[10] shl 2) or (d[11] shl 1) or d[36]
        cur.gamma = t.ambeDg[b2] + 0.5f * prev.gamma
        val gm = FloatArray(9)
        val b3 = (d[12] shl 8) or (d[13] shl 7) or (d[14] shl 6) or (d[15] shl 5) or (d[16] shl 4) or (d[17] shl 3) or (d[18] shl 2) or (d[19] shl 1) or d[40]
        gm[2] = t.ambePRBA24[b3 * 3]; gm[3] = t.ambePRBA24[b3 * 3 + 1]; gm[4] = t.ambePRBA24[b3 * 3 + 2]
        val b4 = (d[20] shl 6) or (d[21] shl 5) or (d[22] shl 4) or (d[23] shl 3) or (d[41] shl 2) or (d[42] shl 1) or d[43]
        for (i in 0 until 4) gm[5 + i] = t.ambePRBA58[b4 * 4 + i]
        val ri = FloatArray(9)
        for (i in 1..8) {
            var sum = 0f
            for (m in 1..8) sum += (if (m == 1) 1 else 2) * gm[m] * cos(PI * (m - 1) * (i - 0.5) / 8).toFloat()
            ri[i] = sum
        }
        val cik = Array(5) { FloatArray(18) }
        val rconst = (1 / (2 * sqrt(2.0))).toFloat()
        for (i in 1..4) { cik[i][1] = 0.5f * (ri[2 * i - 1] + ri[2 * i]); cik[i][2] = rconst * (ri[2 * i - 1] - ri[2 * i]) }
        val b5 = (d[24] shl 4) or (d[25] shl 3) or (d[26] shl 2) or (d[27] shl 1) or d[44]
        val b6 = (d[28] shl 3) or (d[29] shl 2) or (d[30] shl 1) or d[45]
        val b7 = (d[31] shl 3) or (d[32] shl 2) or (d[33] shl 1) or d[46]
        val b8 = (d[34] shl 2) or (d[47] shl 1) or d[48]
        val ji = IntArray(5) { if (it == 0) 0 else t.ambeLmprbl[l * 4 + it - 1] }
        val hoc = arrayOf(t.ambeHOCb5 to b5, t.ambeHOCb6 to b6, t.ambeHOCb7 to b7, t.ambeHOCb8 to b8)
        for (i in 1..4) for (k in 3..ji[i]) cik[i][k] = if (k > 6) 0f else hoc[i - 1].first[hoc[i - 1].second * 4 + k - 3]
        val tl = FloatArray(57)
        var li = 1
        for (i in 1..4) {
            val n = ji[i]
            for (j in 1..n) {
                var sum = 0f
                for (k in 1..n) sum += (if (k == 1) 1 else 2) * cik[i][k] * cos(PI * (k - 1) * (j - 0.5) / n).toFloat()
                if (li < 57) tl[li] = sum
                li++
            }
        }
        if (cur.l > prev.l) for (i in prev.l + 1..cur.l) { prev.ml[i] = prev.ml[prev.l]; prev.log2Ml[i] = prev.log2Ml[prev.l] }
        prev.log2Ml[0] = prev.log2Ml[1]
        prev.ml[0] = prev.ml[1]
        val flokl = FloatArray(57)
        val intkl = IntArray(57)
        val deltal = FloatArray(57)
        var sum43 = 0f
        for (i in 1..cur.l) {
            flokl[i] = prev.l.toFloat() / cur.l * i
            intkl[i] = flokl[i].toInt()
            deltal[i] = flokl[i] - intkl[i]
            sum43 += (1 - deltal[i]) * prev.log2Ml[intkl[i]] + deltal[i] * prev.log2Ml[intkl[i] + 1]
        }
        sum43 *= 0.65f / cur.l
        var sum42 = 0f
        for (i in 1..cur.l) sum42 += tl[i]
        sum42 /= cur.l
        val bigGamma = cur.gamma - 0.5f * (ln(cur.l.toDouble()) / ln(2.0)).toFloat() - sum42
        for (i in 1..cur.l) {
            val c1 = 0.65f * (1 - deltal[i]) * prev.log2Ml[intkl[i]]
            val c2 = 0.65f * deltal[i] * prev.log2Ml[intkl[i] + 1]
            cur.log2Ml[i] = tl[i] + c1 + c2 - sum43 + bigGamma
            val m = exp(0.693 * cur.log2Ml[i]).toFloat()
            cur.ml[i] = if (cur.vl[i] == 1) m else unvc * m
        }
        return 0
    }

    private fun processData(errs2: Int, out: FloatArray, at: Int) {
        val bad = decodeParms()
        when {
            bad == 2 || bad == 3 -> cur.repeat = 0
            errs2 > 3 -> { prev.copyTo(cur); cur.ml[0] = 0f; cur.repeat++ }
            else -> cur.repeat = 0
        }
        if (bad == 0 && cur.repeat <= 3) {
            cur.copyTo(prev)
            enhance()
            synthesize(out, at)
            cur.copyTo(prevEnh)
        } else {
            for (n in 0 until 160) out[at + n] = 0f
            initParms()
        }
    }

    // ---- synthesis (mbelib.c)

    private fun enhance() {
        var rm0 = 0f
        var rm1 = 0f
        for (l in 1..cur.l) { val m2 = cur.ml[l] * cur.ml[l]; rm0 += m2; rm1 += m2 * cos(cur.w0 * l) }
        val r2m0 = rm0 * rm0
        val r2m1 = rm1 * rm1
        for (l in 1..cur.l) {
            if (cur.ml[l] == 0f) continue
            val wl = sqrt(cur.ml[l]) * ((0.96f * PI.toFloat() * ((r2m0 + r2m1) - 2 * rm0 * rm1 * cos(cur.w0 * l))) / (cur.w0 * rm0 * (r2m0 - r2m1))).pow(0.25f)
            when {
                8 * l <= cur.l -> {}
                wl > 1.2f -> cur.ml[l] *= 1.2f
                wl < 0.5f -> cur.ml[l] *= 0.5f
                else -> cur.ml[l] *= wl
            }
        }
        var sum = 0f
        for (l in 1..cur.l) sum += cur.ml[l] * cur.ml[l]
        val g = if (sum == 0f) 1f else sqrt(rm0 / sum)
        for (l in 1..cur.l) cur.ml[l] *= g
    }

    private fun randPhase() = rnd.nextFloat() * (2 * PI).toFloat() - PI.toFloat()

    private fun synthesize(out: FloatArray, at: Int) {
        val ws = AmbeTables.ws
        val p = prevEnh
        val n0 = 160
        val uvThreshold = (2700 * PI / 4000).toFloat()
        val uvSine = (1.3591409 * E).toFloat()
        val uvRand = 2f
        val q = uvQuality
        val logq = if (q == 1) (1 / E).toFloat() else (ln(q.toDouble()) / q).toFloat()
        val uvStep = 1f / q
        val uvOffset = uvStep * (q - 1) / 2
        var numUv = 0
        for (l in 1..cur.l) if (cur.vl[l] == 0) numUv++
        val cw0 = cur.w0
        val pw0 = p.w0
        val s = FloatArray(n0)
        val maxl: Int
        if (cur.l > p.l) { maxl = cur.l; for (l in p.l + 1..maxl) { p.ml[l] = 0f; p.vl[l] = 1 } }
        else { maxl = p.l; for (l in cur.l + 1..maxl) { cur.ml[l] = 0f; cur.vl[l] = 1 } }
        for (l in 1..56) {
            cur.psil[l] = p.psil[l] + (pw0 + cw0) * (l * n0) / 2f
            cur.phil[l] = if (l <= cur.l / 4) cur.psil[l] else cur.psil[l] + numUv * randPhase() / cur.l
        }
        val rphase = FloatArray(64)
        val rphase2 = FloatArray(64)
        // The unvoiced multisine mix, with noise above 2.7 kHz.
        fun mix(w0: Float, wl: Float, n: Int, ph: FloatArray): Float {
            var c = 0f
            val w0l = w0 * wl
            for (i in 0 until q) {
                c += cos(w0 * n * (wl + i * uvStep - uvOffset) + ph[i])
                if (w0l > uvThreshold) c += (w0l - uvThreshold) * uvRand * rnd.nextFloat()
            }
            return c
        }
        for (l in 1..maxl) {
            val cw0l = cw0 * l
            val pw0l = pw0 * l
            val lf = l.toFloat()
            when {
                cur.vl[l] == 0 && p.vl[l] == 1 -> {
                    for (i in 0 until q) rphase[i] = randPhase()
                    for (n in 0 until n0) {
                        val c1 = ws[n + n0] * p.ml[l] * cos(pw0l * n + p.phil[l])
                        val c3 = mix(cw0, lf, n, rphase)
                        s[n] += c1 + c3 * uvSine * ws[n] * cur.ml[l] * logq
                    }
                }
                cur.vl[l] == 1 && p.vl[l] == 0 -> {
                    for (i in 0 until q) rphase[i] = randPhase()
                    for (n in 0 until n0) {
                        val c1 = ws[n] * cur.ml[l] * cos(cw0l * (n - n0) + cur.phil[l])
                        val c3 = mix(pw0, lf, n, rphase)
                        s[n] += c1 + c3 * uvSine * ws[n + n0] * p.ml[l] * logq
                    }
                }
                cur.vl[l] == 1 || p.vl[l] == 1 -> {
                    for (n in 0 until n0) {
                        s[n] += ws[n + n0] * p.ml[l] * cos(pw0l * n + p.phil[l]) + ws[n] * cur.ml[l] * cos(cw0l * (n - n0) + cur.phil[l])
                    }
                }
                else -> {
                    for (i in 0 until q) { rphase[i] = randPhase(); rphase2[i] = randPhase() }
                    for (n in 0 until n0) {
                        val c3 = mix(pw0, lf, n, rphase)
                        val c4 = mix(cw0, lf, n, rphase2)
                        s[n] += c3 * uvSine * ws[n + n0] * p.ml[l] * logq + c4 * uvSine * ws[n] * cur.ml[l] * logq
                    }
                }
            }
        }
        // mbelib scales by 7 into 16 bits; here to about ±1.
        for (n in 0 until n0) out[at + n] = (s[n] * 7f / 32768f).coerceIn(-1f, 1f)
    }

    companion object {
        // Where each dibit of a 72-bit frame goes in the four code words (bit 1, then bit 0).
        private val W = intArrayOf(0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 2, 0, 2, 0, 2, 0, 2, 0, 2, 0, 2, 0, 2)
        private val X = intArrayOf(23, 10, 22, 9, 21, 8, 20, 7, 19, 6, 18, 5, 17, 4, 16, 3, 15, 2, 14, 1, 13, 0, 12, 10, 11, 9, 10, 8, 9, 7, 8, 6, 7, 5, 6, 4)
        private val Y = intArrayOf(0, 2, 0, 2, 0, 2, 0, 2, 0, 3, 0, 3, 1, 3, 1, 3, 1, 3, 1, 3, 1, 3, 1, 3, 1, 3, 1, 3, 1, 3, 1, 3, 1, 3, 1, 3)
        private val Z = intArrayOf(5, 3, 4, 2, 3, 1, 2, 0, 1, 13, 0, 12, 22, 11, 21, 10, 20, 9, 19, 8, 18, 7, 17, 6, 16, 5, 15, 4, 14, 3, 13, 2, 12, 1, 11, 0)
    }
}
