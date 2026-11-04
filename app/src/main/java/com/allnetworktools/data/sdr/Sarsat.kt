package com.allnetworktools.data.sdr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * COSPAS-SARSAT 406 MHz distress beacons, first generation (specification C/S T.001, public), ported from OnAir
 * (core/src/sarsat, GPL-3.0). A burst is 24 sync bits (15 ones, then 9 frame-sync bits: normal or self-test) and a 112-bit
 * (short) or 144-bit (long) message. Bits are numbered from 1 as in the specification: bit n is at index n − 1. Bits 25
 * to 85 are protected by a BCH(82,61) check in 86 to 106; a long message adds bits 107 to 132 with a BCH(38,26) check.
 */
object Sarsat {
    const val SHORT_BITS = 112
    const val LONG_BITS = 144
    const val SYNC_BITS = 24
    const val BIT_RATE = 400.0
    private const val SYNC_NORMAL = 0x02F // 000101111
    private const val SYNC_TEST = 0x0D0   // 011010000
    private const val GEN1 = 0x26D9E3     // BCH(82,61)
    private const val GEN2 = 0x1539       // BCH(38,26)

    data class Beacon(
        val longMsg: Boolean,
        val userProto: Boolean,
        val selfTest: Boolean,
        val mid: Int,
        val country: String?,
        val protoCode: Int,
        val protoName: String,
        val beaconType: String,
        /** Bits 26 to 85 as 15 hex digits, the identity rescue centres use. */
        val hexId: String,
        val idText: String,
        val lat: Double?,
        val lon: Double?,
        /** The 4-second offsets of the long message were added. */
        val fine: Boolean,
        val precisionDeg: Double,
        val homing: Boolean,
        val bch2Present: Boolean,
        val bch2Ok: Boolean,
        val corrected: Int,
        val rawHex: String,
    )

    fun get(b: IntArray, first: Int, last: Int): Long {
        var v = 0L
        for (i in first..last) v = (v shl 1) or (if (i - 1 < b.size) (b[i - 1] and 1).toLong() else 0L)
        return v
    }

    fun put(b: IntArray, first: Int, last: Int, value: Long) {
        var v = value
        for (i in last downTo first) { b[i - 1] = (v and 1).toInt(); v = v shr 1 }
    }

    fun bchRemainder(bits: IntArray, first: Int, last: Int, gen: Int, degree: Int): Int {
        var rem = 0
        for (i in first..last) {
            rem = (rem shl 1) or (if (i - 1 < bits.size) bits[i - 1] and 1 else 0)
            if ((rem shr degree) and 1 != 0) rem = rem xor gen
        }
        return rem and ((1 shl degree) - 1)
    }

    /** Syndrome table of a shortened BCH code: the error positions of every pattern of up to [t] errors. */
    private class Bch(val gen: Int, val deg: Int, val n: Int, val t: Int) {
        private val tab = HashMap<Int, IntArray>()

        init {
            val single = IntArray(n) { j -> bchRemainder(IntArray(n).also { it[j] = 1 }, 1, n, gen, deg) }
            for (a in 0 until n) {
                tab[single[a]] = intArrayOf(a)
                if (t < 2) continue
                for (b in a + 1 until n) {
                    val s2 = single[a] xor single[b]
                    tab.putIfAbsent(s2, intArrayOf(a, b))
                    if (t < 3) continue
                    for (c in b + 1 until n) tab.putIfAbsent(s2 xor single[c], intArrayOf(a, b, c))
                }
            }
        }

        /** Bits repaired in the word bits[first .. first+n-1], or −1 when it cannot be repaired. */
        fun correct(bits: IntArray, first: Int): Int {
            val s = bchRemainder(bits, first, first + n - 1, gen, deg)
            if (s == 0) return 0
            val e = tab[s] ?: return -1
            for (p in e) bits[first - 1 + p] = bits[first - 1 + p] xor 1
            return e.size
        }
    }

    private val bch1 by lazy { Bch(GEN1, 21, 82, 3) }
    private val bch2 by lazy { Bch(GEN2, 12, 38, 2) }

    fun syncBits(selfTest: Boolean): IntArray = IntArray(SYNC_BITS) { 1 }.also { put(it, 16, 24, (if (selfTest) SYNC_TEST else SYNC_NORMAL).toLong()) }

    /** Maritime identification digits: the country that registered the beacon. */
    private val mids: List<Triple<Int, Int, String>> = (
        "201 Albania;202 Andorra;203 Austria;204 Portugal (Azores);205 Belgium;206 Belarus;207 Bulgaria;208 Vatican;209-210 Cyprus;211 Germany;212 Cyprus;" +
            "213 Georgia;214 Moldova;215 Malta;216 Armenia;218 Germany;219-220 Denmark;224-225 Spain;226-228 France;229 Malta;230 Finland;231 Faroe Islands;" +
            "232-235 United Kingdom;236 Gibraltar;237 Greece;238 Croatia;239-241 Greece;242 Morocco;243 Hungary;244-246 Netherlands;247 Italy;248-249 Malta;" +
            "250 Ireland;251 Iceland;252 Liechtenstein;253 Luxembourg;254 Monaco;255 Portugal (Madeira);256 Malta;257-259 Norway;261 Poland;262 Montenegro;" +
            "263 Portugal;264 Romania;265-266 Sweden;267 Slovakia;268 San Marino;269 Switzerland;270 Czechia;271 Turkey;272 Ukraine;273 Russia;" +
            "274 North Macedonia;275 Latvia;276 Estonia;277 Lithuania;278 Slovenia;279 Serbia;301 Anguilla;303 USA (Alaska);304-305 Antigua and Barbuda;" +
            "306 Netherlands Antilles;307 Aruba;308-309 Bahamas;310 Bermuda;311 Bahamas;312 Belize;314 Barbados;316 Canada;319 Cayman Islands;321 Costa Rica;" +
            "323 Cuba;325 Dominica;327 Dominican Republic;329 Guadeloupe;330 Grenada;331 Greenland;332 Guatemala;334 Honduras;336 Haiti;338 USA;339 Jamaica;" +
            "341 Saint Kitts and Nevis;343 Saint Lucia;345 Mexico;347 Martinique;348 Montserrat;350 Nicaragua;351-354 Panama;358 Puerto Rico;" +
            "361 Saint Pierre and Miquelon;362 Trinidad and Tobago;364 Turks and Caicos;366-369 USA;370-373 Panama;375-377 Saint Vincent;" +
            "378 British Virgin Islands;379 US Virgin Islands;401 Afghanistan;403 Saudi Arabia;405 Bangladesh;408 Bahrain;410 Bhutan;412-414 China;416 Taiwan;" +
            "417 Sri Lanka;419 India;422 Iran;423 Azerbaijan;425 Iraq;428 Israel;431-432 Japan;434 Turkmenistan;436 Kazakhstan;437 Uzbekistan;438 Jordan;" +
            "440-441 South Korea;443 Palestine;445 North Korea;447 Kuwait;450 Lebanon;451 Kyrgyzstan;453 Macao;455 Maldives;457 Mongolia;459 Nepal;461 Oman;" +
            "463 Pakistan;466 Qatar;468 Syria;470-471 United Arab Emirates;472 Tajikistan;473-475 Yemen;477 Hong Kong;478 Bosnia and Herzegovina;503 Australia;" +
            "506 Myanmar;508 Brunei;510 Micronesia;511 Palau;512 New Zealand;514-515 Cambodia;516 Christmas Island;518 Cook Islands;520 Fiji;523 Cocos Islands;" +
            "525 Indonesia;529 Kiribati;531 Laos;533 Malaysia;536 Northern Mariana Islands;538 Marshall Islands;540 New Caledonia;542 Niue;544 Nauru;" +
            "546 French Polynesia;548 Philippines;553 Papua New Guinea;555 Pitcairn;557 Solomon Islands;559 American Samoa;561 Samoa;563-566 Singapore;" +
            "567 Thailand;570 Tonga;572 Tuvalu;574 Vietnam;576-577 Vanuatu;578 Wallis and Futuna;601 South Africa;603 Angola;605 Algeria;609 Burundi;" +
            "610 Benin;611 Botswana;612 Central African Republic;613 Cameroon;615 Congo;616 Comoros;617 Cabo Verde;619 Ivory Coast;620 Comoros;621 Djibouti;" +
            "622 Egypt;624 Ethiopia;625 Eritrea;626 Gabon;627 Ghana;629 Gambia;630 Guinea-Bissau;631 Equatorial Guinea;632 Guinea;633 Burkina Faso;634 Kenya;" +
            "636-637 Liberia;638 South Sudan;642 Libya;644 Lesotho;645 Mauritius;647 Madagascar;649 Mali;650 Mozambique;654 Mauritania;655 Malawi;656 Niger;" +
            "657 Nigeria;659 Namibia;660 Reunion;661 Rwanda;662 Sudan;663 Senegal;664 Seychelles;665 Saint Helena;666 Somalia;667 Sierra Leone;" +
            "668 Sao Tome and Principe;669 Eswatini;670 Chad;671 Togo;672 Tunisia;674-675 Tanzania;676 DR Congo;677 Tanzania;678 Zambia;679 Zimbabwe;" +
            "701 Argentina;710 Brazil;720 Bolivia;725 Chile;730 Colombia;735 Ecuador;740 Falkland Islands;745 French Guiana;750 Guyana;755 Paraguay;760 Peru;" +
            "765 Suriname;770 Uruguay;775 Venezuela"
        ).split(';').map { e ->
            val r = e.substringBefore(' ')
            val a = r.substringBefore('-').toInt()
            Triple(a, if ('-' in r) r.substringAfter('-').toInt() else a, e.substringAfter(' '))
        }

    fun country(mid: Int): String? = mids.firstOrNull { mid in it.first..it.second }?.third

    private val baudot = mapOf(
        'A' to 0x38, 'B' to 0x33, 'C' to 0x2E, 'D' to 0x32, 'E' to 0x30, 'F' to 0x36, 'G' to 0x2B, 'H' to 0x25, 'I' to 0x2C, 'J' to 0x3A,
        'K' to 0x3E, 'L' to 0x29, 'M' to 0x27, 'N' to 0x26, 'O' to 0x23, 'P' to 0x2D, 'Q' to 0x3D, 'R' to 0x2A, 'S' to 0x34, 'T' to 0x21,
        'U' to 0x3C, 'V' to 0x2F, 'W' to 0x39, 'X' to 0x37, 'Y' to 0x35, 'Z' to 0x31, '0' to 0x0D, '1' to 0x1D, '2' to 0x19, '3' to 0x10,
        '4' to 0x0A, '5' to 0x01, '6' to 0x15, '7' to 0x1C, '8' to 0x0C, '9' to 0x03, ' ' to 0x24, '-' to 0x18, '/' to 0x17,
    )
    private val baudotRev = baudot.entries.associate { it.value to it.key }

    fun baudotUnpack(v: Long, n: Int) = buildString { for (i in n - 1 downTo 0) append(baudotRev[((v shr (6 * i)) and 0x3F).toInt()] ?: '?') }

    fun baudotPack(s: String, n: Int): Long {
        var v = 0L
        for (i in 0 until n) v = (v shl 6) or (baudot[s.getOrElse(i) { ' ' }.uppercaseChar()] ?: 0x24).toLong()
        return v
    }

    private fun standardLocation(code: Int) = code in listOf(2, 3, 4, 5, 6, 7, 12, 14)

    /** Name of the protocol and the kind of beacon it is for. */
    fun protocol(user: Boolean, code: Int): Pair<String, String> = if (user) when (code) {
        0 -> "Orbitography" to "Test"
        1 -> "Aviation user" to "ELT"
        2 -> "Maritime user" to "EPIRB"
        3 -> "Serial user" to "?"
        4 -> "National user" to "?"
        6 -> "Radio call sign user" to "EPIRB"
        7 -> "Test user" to "Test"
        else -> "User protocol $code" to "?"
    } else when (code) {
        2 -> "EPIRB, MMSI and location" to "EPIRB"
        3 -> "ELT, aircraft address and location" to "ELT"
        4 -> "ELT, serial and location" to "ELT"
        5 -> "ELT, aircraft operator and location" to "ELT"
        6 -> "EPIRB, serial and location" to "EPIRB"
        7 -> "PLB, serial and location" to "PLB"
        8 -> "ELT, national location" to "ELT"
        9 -> "ELT-DT location" to "ELT-DT"
        10 -> "EPIRB, national location" to "EPIRB"
        11 -> "PLB, national location" to "PLB"
        12 -> "Ship security (SSAS)" to "Ship security"
        14 -> "Location test" to "Test"
        15 -> "National location test" to "Test"
        else -> "Location protocol $code" to "?"
    }

    fun hex(bits: IntArray, first: Int, last: Int): String = buildString {
        var i = first
        while (i <= last) {
            var v = 0
            for (k in 0 until 4) v = (v shl 1) or (if (i + k <= last && i + k - 1 < bits.size) bits[i + k - 1] and 1 else 0)
            append("0123456789ABCDEF"[v])
            i += 4
        }
    }

    /** Repairs (up to 3 and 2 bit errors) and reads [bits] (sync included, 112 or 144); null when the first check fails. */
    fun decode(bitsIn: IntArray): Beacon? {
        if (bitsIn.size < SHORT_BITS) return null
        val bits = bitsIn.copyOf()
        val c1 = bch1.correct(bits, 25)
        if (c1 < 0) return null
        var corrected = c1
        val longMsg = bits[24] != 0
        if (longMsg && bits.size < LONG_BITS) return null
        val selfTest = get(bits, 16, 24) == SYNC_TEST.toLong()
        val user = bits[25] != 0
        val mid = get(bits, 27, 36).toInt()
        val id = bits.copyOf(85)
        val loc = get(bits, 37, 40).toInt()
        // The 15 hex ID: bits 26 to 85 with a standard location protocol's position at its default (C/S T.001 3.3).
        if (!user && standardLocation(loc)) { put(id, 65, 85, 0); put(id, 66, 74, 511); put(id, 76, 85, 1023) }
        else if (!user && loc == 9) { put(id, 67, 85, 0); put(id, 68, 75, 255); put(id, 77, 85, 511) }
        val hexId = hex(id, 26, 85)
        val homing = bits[110] != 0
        var bch2Ok = false
        if (longMsg) {
            val c2 = bch2.correct(bits, 107)
            bch2Ok = c2 >= 0
            if (c2 > 0) corrected += c2
        }
        val raw = hex(bits, 1, if (longMsg) LONG_BITS else SHORT_BITS)
        val country = country(mid)
        if (user) {
            val code = get(bits, 37, 39).toInt()
            val (name, type0) = protocol(true, code)
            var type = type0
            val text = when (code) {
                1 -> "aircraft marking " + baudotUnpack(get(bits, 40, 81), 7).trim()
                2 -> "call sign / MMSI digits " + baudotUnpack(get(bits, 40, 75), 6).trim() + ", beacon " + baudotUnpack(get(bits, 76, 81), 1)
                3 -> {
                    val ty = get(bits, 40, 42).toInt()
                    type = when (ty) { 0, 1, 3 -> "ELT"; 2, 4 -> "EPIRB"; 6 -> "PLB"; else -> "?" }
                    val kind = when (ty) { 0 -> "ELT"; 1 -> "ELT (aircraft operator)"; 3 -> "ELT (24-bit address)"; 2 -> "float-free EPIRB"; 4 -> "EPIRB (not float-free)"; 6 -> "PLB"; else -> "type unknown" }
                    "$kind, serial field ${hex(bits, 44, 73)}"
                }
                else -> "identification field " + hex(bits, 40, 85)
            }
            return Beacon(longMsg, true, selfTest, mid, country, code, name, type, hexId, text, null, null, false, 0.0, homing, longMsg, bch2Ok, corrected, raw)
        }
        val (name, type) = protocol(false, loc)
        var lat: Double? = null
        var lon: Double? = null
        var fine = false
        var prec = 0.0
        var text = when (loc) {
            2 -> "MMSI %03d%06d, beacon %d".format(mid, get(bits, 41, 60), get(bits, 61, 64))
            4, 6, 7 -> "certificate %d, serial %d".format(get(bits, 41, 50), get(bits, 51, 64))
            5 -> {
                val v = get(bits, 41, 55)
                val op = (2 downTo 0).map { k -> val ch = ((v shr (5 * k)) and 31).toInt(); if (ch in 1..26) 'A' + ch - 1 else '?' }.joinToString("")
                "operator $op, serial %d".format(get(bits, 56, 64))
            }
            3 -> "aircraft address %06X".format(get(bits, 41, 64))
            9 -> if (get(bits, 41, 42) == 0L) "aircraft address %06X".format(get(bits, 43, 66)) else "identification not available"
            else -> "identification field " + hex(bits, 41, 64)
        }
        if (loc == 9) {
            val latU = get(bits, 68, 75).toInt()
            val lonU = get(bits, 77, 85).toInt()
            if (latU <= 180 && lonU <= 360) {
                lat = (if (bits[66] != 0) -1.0 else 1.0) * latU * 0.5
                lon = (if (bits[75] != 0) -1.0 else 1.0) * lonU * 0.5
                prec = 0.25
                if (longMsg && bch2Ok) {
                    val fr = get(bits, 113, 114).toInt()
                    if (fr > 0) {
                        val dl = get(bits, 116, 119) / 60.0 + get(bits, 120, 123) * 4.0 / 3600.0
                        val dn = get(bits, 125, 128) / 60.0 + get(bits, 129, 132) * 4.0 / 3600.0
                        lat += if (bits[114] != 0) dl else -dl
                        lon += if (bits[123] != 0) dn else -dn
                        fine = true; prec = 2.0 / 3600.0
                    }
                    val act = listOf("manual", "automatic (G-switch)", "automatic (external)", "spare")[get(bits, 107, 108).toInt()]
                    text += ", $act activation"
                }
            }
        } else if (standardLocation(loc)) {
            val latU = get(bits, 66, 74).toInt()
            val lonU = get(bits, 76, 85).toInt()
            if (latU <= 360 && lonU <= 720) {
                lat = (if (bits[64] != 0) -1.0 else 1.0) * latU * 0.25
                lon = (if (bits[74] != 0) -1.0 else 1.0) * lonU * 0.25
                prec = 0.125
                if (longMsg && bch2Ok) {
                    val lm = get(bits, 114, 117).toInt(); val ls = get(bits, 118, 122).toInt()
                    val om = get(bits, 124, 127).toInt(); val os = get(bits, 128, 132).toInt()
                    if (lm <= 14 && ls <= 14 && om <= 14 && os <= 14) {
                        val dl = lm / 60.0 + ls * 4.0 / 3600.0
                        val dn = om / 60.0 + os * 4.0 / 3600.0
                        lat += if (bits[112] != 0) dl else -dl
                        lon += if (bits[122] != 0) dn else -dn
                        fine = true; prec = 2.0 / 3600.0
                    }
                }
            }
        }
        return Beacon(longMsg, false, selfTest, mid, country, loc, name, type, hexId, text, lat, lon, fine, prec, homing, longMsg, bch2Ok, corrected, raw)
    }

    /** A whole message for the tests: sync, data and both checks; standard location protocols only. */
    fun encode(longMsg: Boolean, mid: Int, protoCode: Int, ident: Long, lat: Double?, lon: Double?, selfTest: Boolean = false, homing: Boolean = false): IntArray {
        val b = IntArray(if (longMsg) LONG_BITS else SHORT_BITS)
        syncBits(selfTest).copyInto(b)
        b[24] = if (longMsg) 1 else 0
        put(b, 27, 36, mid.toLong())
        put(b, 37, 40, protoCode.toLong())
        put(b, 41, 64, ident)
        put(b, 65, 85, 0); put(b, 66, 74, 511); put(b, 76, 85, 1023)
        if (lat != null && lon != null) {
            val cu = (abs(lat) / 0.25).roundToInt(); val ou = (abs(lon) / 0.25).roundToInt()
            val cl = (if (lat < 0) -1.0 else 1.0) * cu * 0.25; val co = (if (lon < 0) -1.0 else 1.0) * ou * 0.25
            b[64] = if (lat < 0) 1 else 0; put(b, 66, 74, cu.toLong())
            b[74] = if (lon < 0) 1 else 0; put(b, 76, 85, ou.toLong())
            if (longMsg) {
                val dl = lat - cl; val dn = lon - co
                val tl = (abs(dl) * 3600.0 / 4.0).roundToLong(); val tn = (abs(dn) * 3600.0 / 4.0).roundToLong()
                b[112] = if (dl >= 0) 1 else 0; put(b, 114, 117, tl / 15); put(b, 118, 122, tl % 15)
                b[122] = if (dn >= 0) 1 else 0; put(b, 124, 127, tn / 15); put(b, 128, 132, tn % 15)
            }
        } else if (longMsg) put(b, 113, 132, 0xFFFFF)
        b[110] = if (homing) 1 else 0
        run { val w = b.copyOf(85).copyOf(106); put(b, 86, 106, bchRemainder(w, 25, 106, GEN1, 21).toLong()) }
        if (longMsg) { val w = b.copyOf(132).copyOf(144); put(b, 133, 144, bchRemainder(w, 107, 144, GEN2, 12).toLong()) }
        return b
    }

    // ---- demodulator ------------------------------------------------------------------------------------

    /** The rate bursts are demodulated at. */
    const val FS = 32_000.0

    private class Cand(val matches: Int, val conf: Double, val t0: Double, val r: Double, val inv: Boolean, val test: Boolean)

    /** Prefix sums of the burst rotated to zero frequency: a half-bit sum is a difference of two of them. */
    private class Slicer(val pr: DoubleArray, val pi: DoubleArray, val n: Int) {
        fun soft(b0: Double, spb: Double): Double? {
            val b1 = b0 + spb
            if (b0 < 0 || b1 > n) return null
            val i0 = b0.roundToInt(); val im = (b0 + spb * 0.5).roundToInt(); val i1 = minOf(n, b1.roundToInt())
            val ar = pr[im] - pr[i0]; val ai = pi[im] - pi[i0]
            val br = pr[i1] - pr[im]; val bi = pi[i1] - pi[im]
            val m = hypot(ar, ai) * hypot(br, bi)
            if (m <= 1e-12) return 0.0
            // Manchester phase modulation of ±1.1 rad: the carrier turns by ±2.2 rad between the two halves of a bit.
            return (ai * br - ar * bi) / m
        }

        fun cosHalves(b0: Double, spb: Double): Double? {
            if (b0 < 0) return null
            val i0 = b0.roundToInt(); val im = (b0 + spb * 0.5).roundToInt(); val i1 = (b0 + spb).roundToInt()
            if (i1 > n) return null
            val ar = pr[im] - pr[i0]; val ai = pi[im] - pi[i0]
            val br = pr[i1] - pr[im]; val bi = pi[i1] - pi[im]
            val m = hypot(ar, ai) * hypot(br, bi)
            return if (m > 1e-12) (ar * br + ai * bi) / m else null
        }

        fun bits(t0: Double, spb: Double, count: Int): DoubleArray {
            val out = ArrayList<Double>(count)
            for (k in 0 until count) out += soft(t0 + k * spb, spb) ?: break
            return out.toDoubleArray()
        }
    }

    private val syncRef = arrayOf(syncBits(false), syncBits(true))

    private fun score(s: Slicer, t0: Double, r: Double): Cand? {
        val spb = FS / BIT_RATE * (1 + r)
        val d = s.bits(t0, spb, SYNC_BITS)
        if (d.size < SYNC_BITS) return null
        var best: Cand? = null
        for (test in 0..1) for (inv in 0..1) {
            var m = 0
            var conf = 0.0
            for (k in 0 until SYNC_BITS) {
                val want = if ((syncRef[test][k] xor inv) != 0) 1 else -1
                if ((d[k] > 0) == (want > 0)) m++
                conf += want * d[k]
            }
            val b = best
            if (b == null || m > b.matches || (m == b.matches && conf > b.conf)) best = Cand(m, conf, t0, r, inv != 0, test != 0)
        }
        return best
    }

    private fun tryDecode(s: Slicer, c: Cand): Beacon? {
        val spb = FS / BIT_RATE * (1 + c.r)
        val d = s.bits(c.t0, spb, LONG_BITS)
        if (d.size < SHORT_BITS) return null
        val b = IntArray(d.size) { if ((d[it] > 0) != c.inv) 1 else 0 }
        // A real burst starts with 160 ms of bare carrier: the two halves of the 20 bit periods before the sync point the same way.
        var cs = 0.0
        var cnt = 0
        for (k in 1..20) { s.cosHalves(c.t0 - k * spb, spb)?.let { cs += it; cnt++ } }
        if (cnt < 16 || cs / cnt < 0.35) return null
        val ref = syncRef[if (c.test) 1 else 0]
        var matches = 0
        for (k in 0 until SYNC_BITS) if (b[k] == ref[k]) matches++
        if (matches < 23) return null
        val o = decode(b) ?: return null
        // BCH-1 repairs any word within 3 bits of a codeword and the search tries thousands of alignments: a full repair needs a
        // perfect sync word, a country that exists and, for a long message, an intact second check.
        if (o.country == null) return null
        if (o.corrected >= 3 && matches < SYNC_BITS) return null
        if (o.corrected >= 2 && o.longMsg && !o.bch2Ok) return null
        return o.copy(selfTest = c.test)
    }

    /** A burst at [FS] with its carrier at [carrierHz]: the message when a sync pattern and the first check pass. */
    fun demodBurst(re: FloatArray, im: FloatArray, n: Int, carrierHz: Double): Beacon? {
        if (n < (FS * 0.4).toInt()) return null
        val pr = DoubleArray(n + 1)
        val pi = DoubleArray(n + 1)
        var lr = 1.0; var li = 0.0
        val w = -2 * PI * carrierHz / FS
        val cr = cos(w); val ci = sin(w)
        for (i in 0 until n) {
            pr[i + 1] = pr[i] + re[i] * lr - im[i] * li
            pi[i + 1] = pi[i] + re[i] * li + im[i] * lr
            val nr = lr * cr - li * ci; li = lr * ci + li * cr; lr = nr
            if (i and 1023 == 1023) { val g = 1 / hypot(lr, li); lr *= g; li *= g }
        }
        val s = Slicer(pr, pi, n)
        val spb0 = FS / BIT_RATE
        val cands = ArrayList<Cand>()
        for (r in doubleArrayOf(-0.01, -0.0075, -0.005, -0.0025, 0.0, 0.0025, 0.005, 0.0075, 0.01)) {
            val spb = spb0 * (1 + r)
            var t0 = 0.0
            while (t0 + SYNC_BITS * spb < n) {
                score(s, t0, r)?.takeIf { it.matches >= 22 }?.let { cands += it }
                t0 += 4
            }
        }
        if (cands.isEmpty()) return null
        cands.sortWith(compareByDescending<Cand> { it.matches }.thenByDescending { it.conf })
        var best: Beacon? = null
        var bestCorr = 99
        var bestConf = -1.0
        for (c0 in cands.take(10)) {
            for (dt in -4..4) for (dr in doubleArrayOf(-0.0025, 0.0, 0.0025)) {
                val c = score(s, c0.t0 + dt, c0.r + dr) ?: continue
                if (c.matches < 23) continue
                val o = tryDecode(s, c) ?: continue
                if (o.corrected < bestCorr || (o.corrected == bestCorr && c.conf > bestConf)) { bestCorr = o.corrected; bestConf = c.conf; best = o }
            }
        }
        return best
    }
}

/** A decoded burst, with what the receiver measured. */
class SarsatBurst(val beacon: Sarsat.Beacon, val carrierHz: Double, val snrDb: Double)

/**
 * The burst finder: the stream is mixed and averaged down to 128 kHz and cut into 32 ms blocks; a Hann-windowed FFT of
 * each block is searched for one line that stands out from the median and was not there a moment before (the 160 ms
 * unmodulated preamble; DC spikes and steady carriers do not count). A burst runs from the first block with a line to
 * six blocks without one, then is mixed to zero, averaged to 32 kHz and demodulated. ±46 kHz around the centre is
 * searched, the band 406.0 to 406.1 MHz from a centre of 406.05 MHz.
 */
class SarsatReceiver(private val inputRate: Double, private val onBurst: (SarsatBurst) -> Unit) {
    private val det = 128_000.0
    private val ring = 262_144
    private val block = 4096
    private val rr = FloatArray(ring)
    private val ri = FloatArray(ring)
    private var n32 = 0L
    private val win = FloatArray(block) { (0.5 - 0.5 * cos(2 * PI * it / block)).toFloat() }
    private val fft = Fft(block)
    private val fr = FloatArray(block)
    private val fi = FloatArray(block)
    private val p = FloatArray(block)
    private val bg = FloatArray(block)
    private var warm = 0
    private var accR = 0.0
    private var accI = 0.0
    private var accN = 0
    private var nIn = 0L
    private var k32 = 0L
    private val binW = inputRate / det
    private var binEnd = binW

    private var active = false
    private var miss = 0
    private var dets = 0
    private var firstStart = 0L
    private var lastDetEnd = 0L
    private val linePow = ArrayList<Float>()
    private val peaks = ArrayList<Double>()
    private var peakSnr = -200.0

    /** Bursts that look like beacons, decoded or not, and those decoded. */
    @Volatile var bursts = 0; private set
    @Volatile var failed = 0; private set

    fun feed(re: FloatArray, im: FloatArray, n: Int) {
        for (i in 0 until n) {
            accR += re[i]; accI += im[i]; accN++
            nIn++
            if (nIn >= binEnd) {
                val inv = 1.0 / accN
                sample((accR * inv).toFloat(), (accI * inv).toFloat())
                accR = 0.0; accI = 0.0; accN = 0
                k32++
                binEnd = (k32 + 1) * binW
            }
        }
    }

    private fun sample(re: Float, im: Float) {
        val k = (n32 and (ring - 1).toLong()).toInt()
        rr[k] = re; ri[k] = im
        n32++
        if (n32 % block == 0L) block()
    }

    private fun block() {
        val base = n32 - block
        var pw = 0.0
        for (i in 0 until block) {
            val k = ((base + i) and (ring - 1).toLong()).toInt()
            pw += rr[k].toDouble() * rr[k] + ri[k].toDouble() * ri[k]
            fr[i] = rr[k] * win[i]; fi[i] = ri[k] * win[i]
        }
        fft.forward(fr, fi)
        for (k in 0 until block) p[k] = fr[k] * fr[k] + fi[k] * fi[k]
        val kMax = (46_000.0 / det * block).toInt()
        fun allowed(k: Int) = k <= kMax || k >= block - kMax
        val sel = ArrayList<Float>(block)
        for (k in 0 until block) if (allowed(k)) sel += p[k]
        sel.sort()
        val med = maxOf(sel[sel.size / 2], 1e-20f)
        var pk = 0
        var pmax = 0f
        for (k in 0 until block) if (allowed(k) && p[k] > pmax && p[k] > 6f * bg[k]) { pmax = p[k]; pk = k }
        val found = warm >= 12 && pmax >= 30f * med
        if (found) {
            val a = (pk + block - 1) % block
            val c = (pk + 1) % block
            val la = ln(p[a] + 1e-30); val lb = ln(p[pk] + 1e-30); val lc = ln(p[c] + 1e-30)
            val den = la - 2 * lb + lc
            val delta = if (abs(den) > 1e-9) 0.5 * (la - lc) / den else 0.0
            val bin = (if (pk < block / 2) pk else pk - block) + delta.coerceIn(-1.0, 1.0)
            val f = bin * det / block
            val nm = med / 0.6931f
            val snr = 10 * log10(maxOf(1e-3f, (p[a] + p[pk] + p[c] - 3 * nm) / (1.5f * nm)).toDouble())
            if (!active) { active = true; firstStart = base; miss = 0; dets = 0; peakSnr = -200.0; peaks.clear(); linePow.clear() }
            miss = 0; dets++; lastDetEnd = n32; linePow += pmax
            peaks += f
            peakSnr = maxOf(peakSnr, snr)
        } else if (active) miss++
        if (active && (miss >= 6 || n32 - firstStart > (0.9 * det).toLong())) finish()
        if (!active && !found) {
            for (k in 0 until block) bg[k] = if (warm == 0) minOf(p[k], 4 * med) else bg[k] * 0.98f + minOf(p[k], 4 * med) * 0.02f
            if (warm < 1000) warm++
        }
    }

    private fun finish() {
        active = false
        if (dets >= 4 && peakSnr >= 10.0) {
            // A beacon holds one steady line for 0.3 s or more, and the data after its preamble lowers that line by about 7 dB.
            var plausible = lastDetEnd > firstStart && (lastDetEnd - firstStart) >= 0.30 * det && linePow.size >= 8
            if (plausible) plausible = peaks.max() - peaks.min() <= 600.0
            if (plausible) {
                val pre = linePow.subList(0, 5).sorted()
                val post = linePow.subList(5, linePow.size).sorted()
                plausible = post[post.size / 2] <= 0.7f * pre[2]
            }
            val start = if (firstStart >= 8192) firstStart - 8192 else 0
            val from = if (n32 > ring - 4096 && start < n32 - (ring - 4096)) n32 - (ring - 4096) else start
            val carrier = peaks.take(4).sorted().let { it[it.size / 2] }
            // Mixed to zero and averaged 4 to 1 down to the 32 kHz the demodulator works at.
            val len = ((n32 - from) / 4).toInt()
            val yr = FloatArray(len)
            val yi = FloatArray(len)
            val w = -2 * PI * carrier / det
            var ph = 0.0
            var ar = 0f; var ai = 0f
            var j = 0
            for (i in 0 until (len * 4)) {
                val k = ((from + i) and (ring - 1).toLong()).toInt()
                val c = cos(ph).toFloat(); val s = sin(ph).toFloat()
                ar += rr[k] * c - ri[k] * s; ai += rr[k] * s + ri[k] * c
                ph += w
                if (i and 3 == 3) { yr[j] = ar * 0.25f; yi[j] = ai * 0.25f; j++; ar = 0f; ai = 0f }
            }
            val b = Sarsat.demodBurst(yr, yi, len, 0.0)
            if (b != null || plausible) bursts++
            if (b != null) onBurst(SarsatBurst(b, carrier, peakSnr)) else if (plausible) failed++
        }
        peaks.clear(); linePow.clear(); dets = 0; peakSnr = -200.0
    }

    companion object {
        /** The centre the receiver expects its input on: the middle of the 406.0 to 406.1 MHz band. */
        const val CENTRE_HZ = 406_050_000L
    }
}
