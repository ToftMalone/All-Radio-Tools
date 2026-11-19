package com.allnetworktools.data.sdr

import com.allnetworktools.data.sdr.Ism.Builder
import com.allnetworktools.data.sdr.Ism.Mod
import com.allnetworktools.data.sdr.Ism.Timing
import com.allnetworktools.data.sdr.Ism.hex
import com.allnetworktools.data.sdr.Ism.p
import com.allnetworktools.data.sdr.Ism.pat
import com.allnetworktools.data.sdr.Ism.xorBytes

/*
 * More ISM devices, ported from rtl_433's src/devices (GPL-2.0-or-later) like the ones in Ism.kt: each decoder names the
 * file it follows, keeps its checks, and leaves out the devices rtl_433 itself disables for false positives.
 */
internal object IsmMore {
    // ---------------------------------------------------------------- home: switches, remotes, alarms

    // newkaku.c, nexa.c, proove.c: KlikAanKlikUit / Chacon DIO / Home Easy / Nexa / Proove self-learning switches
    // (OOK PPM, a sync then 64 or 72 Manchester bits, inverted: 26-bit house code, group, on/off, unit, dim level)
    private fun kaku(bits: IsmBits, out: Out) {
        if (bits.syncs[0] != 1) return
        val len = bits.n[0]
        if (len != 64 && len != 72) return
        val raw = bits.copy()
        val b0 = raw.row(0)
        val dim = (b0[6] and 0x03) == 0x03
        if (dim) b0[6] = b0[6] and 0xfe // DIM to ON, so the pair decodes
        val d = IsmBits()
        val pos = Ism.manchester(raw, 0, 0, d, 80)
        d.invert()
        if (pos != 64 && pos != 72) return
        val b = d.row(0)
        val id = (b[0] shl 18) or (b[1] shl 10) or (b[2] shl 2) or (b[3] shr 6)
        val r = Builder("Chacon DIO / KlikAanKlikUit / Nexa", "Remote", "$id", "PROTOCOL")
        r.channel = (b[3] and 0x0f) + 1
        r.text("Command", when { dim -> "Dim ${b[4] shr 4}"; (b[3] shr 4) and 1 == 1 -> "On"; else -> "Off" })
        if ((b[3] shr 5) and 1 == 1) r.text("Group", "Group")
        out += r.build()
    }

    // somfy_rts.c: Somfy RTS shutters, awnings, gates (433.42 MHz, OOK PCM 604 µs, Manchester, obfuscated, nibble check)
    private val somfyControl = arrayOf("?", "My", "Up", "My + Up", "Down", "My + Down", "Up + Down", "My + Up + Down", "Prog", "Sun + Flag", "Flag", "?", "?", "?", "?", "?")

    private fun somfyRts(bits: IsmBits, out: Out) {
        val long = pat(0xf0, 0xf0, 0xf0, 0xf0, 0xf0, 0xff, 0x00)
        val rate = pat(0xf0, 0xf0, 0xf0, 0xf0, 0xf0, 0xfe, 0x00)
        val short = pat(0xf0, 0xf0, 0xff, 0x00)
        var row = -1
        var pos = 0
        for (r in 0 until bits.rows()) {
            val n = bits.n[r]
            if (n > 170) {
                pos = bits.search(r, 0, long, 49) + 49
                if (pos + 112 > n) pos = bits.search(r, 0, rate, 48) + 48
                if (pos + 112 <= n) { row = r; break }
            } else if (n > 130) {
                pos = bits.search(r, 0, short, 25) + 25
                if (pos + 112 <= n) { row = r; break }
            }
        }
        if (row < 0) return
        val d = IsmBits()
        Ism.manchester(bits, row, pos, d, 80)
        if (d.n[0] < 56) return
        val b = d.row(0).copyOf(7)
        for (i in 6 downTo 1) b[i] = b[i] xor b[i - 1]
        val x = xorBytes(b, 7)
        if ((x and 0xf) xor (x shr 4) != 0) return
        val address = (b[6] shl 16) or (b[5] shl 8) or b[4]
        if (address == 0 || address == 0xffffff) return
        val r = Builder("Somfy RTS", "Remote", hex(address, 6).uppercase(), "CHECKSUM")
        r.text("Control", somfyControl[(b[1] and 0xf0) shr 4])
        r.text("Counter", "#${(b[2] shl 8) or b[3]}")
        out += r.build()
    }

    // blyss.c: Blyss DC5-UK-WH doorbell (OOK PWM, 33-bit rows with one of two known codes)
    private fun blyss(bits: IsmBits, out: Out) {
        for (i in 0 until bits.rows()) {
            if (bits.n[i] != 33) continue
            val b = bits.row(i)
            val a = b[0] == 0xce && b[1] == 0x8e && b[2] == 0x2a && b[3] == 0x6c && b[4] == 0x80
            val c = b[0] == 0xe7 && b[1] == 0x37 && b[2] == 0x7a && b[3] == 0x2c && b[4] == 0x80
            if (!a && !c) continue
            val r = Builder("Blyss DC5-UK-WH doorbell", "Remote", hex(b[0], 2) + hex(b[1], 2) + hex(b[2], 2) + hex(b[3], 2), "PROTOCOL")
            r.text("Event", "Ring")
            out += r.build()
            return
        }
    }

    // silvercrest.c: Silvercrest (Lidl) remote control sockets (OOK PWM, 33 bits, button and its check nibble)
    private val silvercrestCheck = intArrayOf(2, 3, 0, 1, 4, 5, 7, 6, 0xC, 0xD, 0xF, 0xE, 8, 9, 0xB, 0xA)

    private fun silvercrest(bits: IsmBits, out: Out) {
        if (bits.rows() < 2 || bits.n[1] != 33) return
        val b = bits.row(1)
        if (b[0] != 0x7c || b[1] != 0x26) return
        val cmd = b[2] and 0xf
        if ((b[3] and 0xf) != silvercrestCheck[cmd]) return
        val r = Builder("Silvercrest remote (Lidl)", "Remote", "7c26", "CHECKSUM")
        r.text("Button", "$cmd")
        out += r.build()
    }

    // visonic_powercode.c: Visonic PowerCode alarm sensors (868.95 MHz in Europe, OOK PWM, 37 bits twice, LRC)
    private fun visonic(bits: IsmBits, out: Out) {
        val row = bits.findRepeatedRow(2, 37)
        if (row < 0 || bits.n[row] != 37) return
        val m = bits.extract(row, 1, 36)
        if (m[0] == 0 && m[1] == 0 && m[2] == 0 && m[3] == 0 && m[4] == 0) return
        val lrc = xorBytes(m, 5)
        if ((lrc shr 4) xor (lrc and 0xf) != 0) return
        val r = Builder("Visonic PowerCode", "Sensor", hex(m[0], 2) + hex(m[1], 2) + hex(m[2], 2), "CHECKSUM")
        r.battery = m[3] and 0x20 == 0
        val s = m[3]
        r.text("State", listOfNotNull(
            if (s and 0x40 != 0) "Alarm" else "Normal",
            if (s and 0x80 != 0) "Tamper" else null,
            if (s and 0x08 != 0) "Restore" else null,
            if (s and 0x04 != 0) "Supervision" else null,
        ).joinToString(", "))
        out += r.build()
    }

    // x10_rf.c: X10 RF switches and remotes (OOK PPM, 32 bits: two bytes and their complements)
    private fun x10Rf(bits: IsmBits, out: Out) {
        if (bits.rows() < 2 || bits.n[1] != 32) return
        val b = bits.row(1)
        if ((b[0] xor b[1]) != 0xff || (b[2] xor b[3]) != 0xff) return
        val mask = intArrayOf(0x0B, 0x0B, 0x07, 0x07)
        val value = intArrayOf(0x00, 0x0B, 0x00, 0x07)
        for (i in 0..3) if (mask[i] and b[i] != value[i]) return
        val h = IntArray(4) { (b[0] shr (7 - it)) and 1 }
        val house = ((((h[0] xor h[1]).inv()) and 1) shl 3) or ((h[1].inv() and 1) shl 2) or (((h[1] xor h[2]) and 1) shl 1) or (h[3] and 1)
        var device = ((b[0] and 0x04) shl 1) or ((b[2] and 0x40) shr 4) or ((b[2] and 0x08) shr 2) or ((b[2] and 0x10) shr 4)
        device += 1
        val event = if (b[2] and 0x80 != 0) {
            device = 0
            when (b[2]) { 0x98 -> "Dim"; 0x88 -> "Bright"; 0x90 -> "All lights on"; 0x80 -> "All off"; else -> "?" }
        } else if (b[2] and 0x20 == 0) "On" else "Off"
        val r = Builder("X10 RF", "Remote", "${'A' + house}$device", "PARITY")
        r.text("Command", event)
        out += r.build()
    }

    // x10_sec.c: X10 security sensors and key fobs (OOK PPM, 41 bits, complements and an even parity)
    private fun x10Sec(bits: IsmBits, out: Out) {
        if (bits.rows() != 2 || bits.n[1] < 41) return
        val b = bits.row(1)
        if ((b[0] xor b[1]) != 0x0f || (b[2] xor b[3]) != 0xff) return
        var par = b[0] xor b[1] xor b[2] xor b[3] xor b[4] xor (b[5] and 0x80)
        par = (par shr 4) xor (par and 0xf); par = (par shr 2) xor (par and 0x3); par = (par shr 1) xor (par and 0x1)
        if (par != 0) return
        val event = when (b[2] and 0xfe) {
            0x00, 0x04, 0x40, 0x44 -> "Door/window open"
            0x80, 0x84, 0xc0, 0xc4 -> "Door/window closed"
            0x06 -> "Key fob arm"
            0x0c, 0x4c -> "Motion"
            0x8c, 0xcc -> "Motion ready"
            0x26, 0x88, 0x98 -> "Panic"
            0x42, 0x46 -> "Lights on"
            0xc2, 0xc6 -> "Lights off"
            0x82, 0x86 -> "Disarm"
            else -> "?"
        }
        val r = Builder("X10 Security", "Sensor", hex(b[0], 2) + hex(b[4], 2), "PARITY")
        r.battery = b[2] and 0x01 == 0
        r.text("Event", event)
        if (b[2] and 0x40 != 0 && (b[2] and 0xfe) in setOf(0x40, 0x44, 0xc0, 0xc4, 0x4c, 0xcc)) r.text("Tamper", "Tamper")
        out += r.build()
    }


    // ---------------------------------------------------------------- helpers

    private fun reverse8(x0: Int): Int {
        var x = ((x0 and 0xF0) shr 4) or ((x0 and 0x0F) shl 4)
        x = ((x and 0xCC) shr 2) or ((x and 0x33) shl 2)
        x = ((x and 0xAA) shr 1) or ((x and 0x55) shl 1)
        return x and 0xFF
    }

    private fun sumBytes(m: IntArray, n: Int): Int { var r = 0; for (i in 0 until n) r += m[i]; return r }

    /** bitbuffer_count_repeats on the first [bits] bits, then find_repeated_prefix. */
    private fun repeatedPrefix(b: IsmBits, minRepeats: Int, bits: Int): Int {
        fun same(i: Int, j: Int): Boolean { for (k in 0 until bits) if (b.bit(i, k) != b.bit(j, k)) return false; return true }
        for (i in 0 until b.rows()) {
            if (b.n[i] < bits) continue
            var c = 0
            for (j in 0 until b.rows()) if (b.n[j] >= bits && same(i, j)) c++
            if (c >= minRepeats) return i
        }
        return -1
    }

    private fun sameRow(b: IsmBits, i: Int, j: Int): Boolean {
        if (b.n[i] != b.n[j]) return false
        for (k in 0 until (b.n[i] + 7) / 8) if (b.byteAt(i, k) != b.byteAt(j, k)) return false
        return true
    }

    private fun fToC(f: Double) = (f - 32) / 1.8

    // ---------------------------------------------------------------- weather, Europe (OOK)

    // infactory.c: inFactory / nor-tec / FreeTec NC-3982 thermo-hygrometers (OOK PPM, 40 bits, CRC-4)
    private fun infactory(bits: IsmBits, out: Out) {
        val len = bits.n[0]
        if (len !in 40..42) return
        val b = bits.row(0)
        val ch = b[4] and 0x03
        if (ch == 0) return
        val msg = intArrayOf(b[0], (b[1] and 0x0F) or ((b[4] and 0x0F) shl 4), b[2], b[3])
        val crc = Ism.crc4(msg, 4, 0x13, 0) xor (b[4] shr 4)
        if (crc != b[1] shr 4) return
        val hum = (b[3] and 0x0F) * 10 + (b[4] shr 4)
        val tf = (((b[2] shl 4) or (b[3] shr 4)) - 900) * 0.1
        if (hum > 100 || tf < -40 || tf > 158) return
        val r = Builder("inFactory / nor-tec / FreeTec", "Weather", "${b[0]}", "CRC")
        r.channel = ch; r.battery = (b[1] shr 2) and 1 == 0
        r.add("Temperature", fToC(tf), "C"); r.add("Humidity", hum.toDouble(), "%", 0)
        out += r.build()
    }

    // esperanza_ews.c and s3318p.c: Esperanza EWS-103, Conrad S3318P, Hama / Sencor sensors (OOK PPM, 42-bit rows, CRC-4)
    private fun th42(b: IntArray, model: String, out: Out) {
        val crc = Ism.crc4(b, 4, 0x3, 0x0) xor (b[4] shr 4)
        if (crc != (b[4] and 0xf)) return
        val raw = ((b[2] and 0x0f) shl 8) or (b[2] and 0xf0) or (b[1] and 0x0f)
        val hum = ((b[3] and 0x0f) shl 4) or ((b[3] and 0xf0) shr 4)
        if (hum > 100) return
        val r = Builder(model, "Weather", "${b[0]}", "CRC")
        r.channel = ((b[1] and 0x30) shr 4) + 1; r.battery = b[4] and 0x40 == 0
        r.add("Temperature", fToC((raw - 900) * 0.1), "C")
        if (hum != 0) r.add("Humidity", hum.toDouble(), "%", 0)
        out += r.build()
    }

    private fun esperanza(bits: IsmBits, out: Out) {
        if (bits.rows() != 14 || bits.n[0] != 0 || bits.n[1] != 0) return
        var row = 2
        while (row < bits.rows() - 3) { if (!sameRow(bits, row, row + 2) || bits.n[row] != 42) return; row += 2 }
        th42(bits.extract(2, 2, 40), "Esperanza EWS", out)
    }

    private fun s3318p(bits: IsmBits, out: Out) {
        if (bits.n[0] == 0 && bits.rows() > 1 && bits.n[1] == 0) return
        val r = bits.findRepeatedRow(4, 42)
        if (r < 0 || bits.n[r] != 42) return
        val b = bits.extract(r, 2, 40)
        if (b[0] == 0 && b[1] == 0 && b[2] == 0 && b[3] == 0) return
        th42(b, "Conrad S3318P / Hama", out)
    }

    // alecto.c: Alecto V1 (WS3500, WS4500, Ventus W044, Balance RF-WS105): temperature, rain, wind (OOK PPM, 36-bit rows)
    private fun alectoSum(b: IntArray): Boolean {
        var c = 0
        for (i in 0 until 4) { val t = reverse8(b[i]); c += (t and 0xf) + ((t and 0xf0) shr 4) }
        c = if ((b[1] and 0x7f) == 0x6c) c + 0x7 else 0xf - c
        return reverse8((c and 0xf) shl 4) == (b[4] shr 4)
    }

    private fun alecto(bits: IsmBits, out: Out) {
        if (bits.rows() < 7 || bits.n[1] != 36) return
        val bb = { i: Int -> bits.row(i) }
        val b = bb(1)
        if (bb(1)[0] != bb(5)[0] || bb(2)[0] != bb(6)[0] || (bb(1)[4] and 0xf) != 0 || (bb(5)[4] and 0xf) != 0 || bb(5)[0] == 0 || bb(5)[1] == 0) return
        if (!alectoSum(bb(1)) || !alectoSum(bb(5))) return
        val type = (b[1] and 0x60) shr 5
        val rain = (b[1] and 0x0f) == 0x0c
        val id = "${reverse8(b[0])}"
        fun base(kind: String) = Builder("Alecto V1 $kind", "Weather", id, "CHECKSUM").also { it.channel = (b[0] and 0xc) shr 2; it.battery = (b[1] and 0x80) == 0 }
        if (type == 3 && !rain) {
            val skip = if ((b[1] and 0xe) == 0x8 && b[2] == 0) 0 else if ((b[1] and 0xe) == 0xe) 4 else -1
            if (skip < 0 || bits.rows() < 6 + skip) return
            val r = base("wind")
            r.add("Wind", reverse8(bb(1 + skip)[3]) * 0.2, "m/s")
            r.add("Gust", reverse8(bb(5 + skip)[3]) * 0.2, "m/s")
            r.add("Direction", ((reverse8(bb(5 + skip)[2]) shl 1) or (bb(5 + skip)[1] and 1)).toDouble(), "deg", 0)
            out += r.build()
        } else if (type == 3) {
            val r = base("rain")
            r.add("Rain", ((reverse8(b[3]) shl 8) or reverse8(b[2])) * 0.25, "mm")
            out += r.build()
        } else if ((2..5).all { bb(it)[0] == bb(it + 1)[0] } && (bb(3)[4] and 0xf) == 0 && (bb(5)[4] and 0xf) == 0) {
            val traw = ((reverse8(b[1]) and 0xf0) or (reverse8(b[2]) shl 8)).toShort().toInt()
            val h = reverse8(b[3])
            val hum = ((h and 0xF0) shr 4) * 10 + (h and 0x0F)
            if (hum > 100) return
            val r = base("thermo-hygro")
            r.add("Temperature", (traw shr 4) * 0.1, "C"); r.add("Humidity", hum.toDouble(), "%", 0)
            out += r.build()
        }
    }

    // ws2032.c: Fine Offset WS2032 / Sainlogic / Ventus weather stations (OOK PWM, 113-bit rows, inverted, sum and CRC-8)
    private fun ws2032(bits: IsmBits, out: Out) {
        val row = bits.findRepeatedRow(2, 112)
        if (row < 0) return
        val off = bits.search(row, 0, pat(0x0a), 8)
        if (off + 112 > bits.n[row]) return
        bits.invert()
        val b = bits.extract(row, off, 112)
        val sum = sumBytes(b, 12)
        if (sum == 0 || (sum and 0xff) != b[12] || Ism.crc8(b, 14, 0x31, 0x00) != 0) return
        val r = Builder("Fine Offset WS2032", "Weather", hex((b[1] shl 8) or b[2], 4).uppercase(), "CRC")
        r.battery = b[3] and 0x01 == 0
        val sign = if (b[4] and 0x08 != 0) -1 else 1
        r.add("Temperature", sign * (((b[4] and 0x07) shl 8) or b[5]) * 0.1, "C")
        r.add("Humidity", b[6].toDouble(), "%", 0)
        r.add("Wind", b[7] * 0.43 * 3.6, "km/h"); r.add("Gust", b[8] * 0.43 * 3.6, "km/h")
        r.add("Direction", (b[4] shr 4) * 22.5, "deg", 0)
        r.add("Rain tips", ((b[9] shl 16) or (b[10] shl 8) or b[11]).toDouble(), "", 0)
        out += r.build()
    }

    // rubicson_pool_48942.c: Rubicson 48942 pool thermometer (OOK PWM, 41 bits twice, inverted, CRC-8)
    private fun rubicsonPool(bits: IsmBits, out: Out) {
        val row = bits.findRepeatedRow(2, 41)
        if (row < 0 || bits.n[row] != 41) return
        bits.invert()
        val b = bits.row(row)
        if (b[3] and 0xF != 0 || b[5] != 0) return
        if (b[0] == 0 && b[2] == 0 && b[4] == 0) return
        if (Ism.crc8(b, 4, 0x31, 0x00) != b[4]) return
        val r = Builder("Rubicson 48942 pool", "Weather", "${((b[0] and 0x0F) shl 6) or ((b[1] and 0xFC) shr 2)}", "CRC")
        r.channel = (b[0] shr 4) + 1; r.battery = b[2] shr 7 == 0
        r.add("Temperature", ((((b[2] and 0x7F) shl 4) or (b[3] shr 4)) - 1024) * 0.1, "C")
        out += r.build()
    }

    // tfa_pool_thermometer.c: TFA 30.3160 pool thermometer (OOK PPM, 28 bits repeated 7 times, nibble sum)
    private fun tfaPool(bits: IsmBits, out: Out) {
        val row = bits.findRepeatedRow(7, 28)
        if (row < 0 || bits.n[row] != 28) return
        val b = bits.row(row)
        val sum = (b[0] and 0x0F) + (b[1] shr 4) + (b[1] and 0x0F) + (b[2] shr 4) + (b[2] and 0x0F) + (b[3] shr 4) - 1
        if ((b[0] and 0xF0) shr 4 != (sum and 0x0F)) return
        val raw = ((b[1] and 0x0F) shl 8) or b[2]
        val r = Builder("TFA 30.3160 pool", "Weather", "${((b[0] and 0x0F) shl 4) or ((b[1] and 0xF0) shr 4)}", "CHECKSUM")
        r.channel = (b[3] and 0xC0) shr 6; r.battery = (b[3] and 0x20) != 0
        r.add("Temperature", (if (raw > 2048) raw - 4096 else raw) * 0.1, "C")
        out += r.build()
    }

    // tfa_drop_30.3233.c: TFA Dostmann 30.3233.01 Drop rain gauge (OOK PWM, 66 bits, inverted, LFSR digest)
    private fun tfaDrop(bits: IsmBits, out: Out) {
        bits.invert()
        val row = bits.findRepeatedRow(2, 66)
        if (row < 0 || bits.n[row] > 82) return
        val b = bits.row(row)
        if ((b[0] and 0xf0) != 0x30) return
        if (b[7] != Ism.lfsrDigest8Reflect(b, 7, 0x31, 0xf4)) return
        val counter = (((b[6] shl 8) or b[4]) + 10) and 0xffff
        val r = Builder("TFA 30.3233 Drop", "Weather", hex(((b[0] and 0x0f) shl 16) or (b[1] shl 8) or b[2], 5), "CHECKSUM")
        r.battery = b[3] and 0x80 == 0
        r.add("Rain", counter * 0.254, "mm")
        out += r.build()
    }

    // emos_e6016.c: EMOS E6016 / E6018 weather station with DCF77 clock (OOK PWM, 120-bit rows, inverted, sum)
    private fun emosE6016(bits: IsmBits, out: Out) {
        val row = repeatedPrefix(bits, 3, 112)
        if (row < 0 || bits.n[row] != 120) return
        val b = bits.row(row)
        if (b[0] != 0x55 || b[1] != 0x5a || b[2] != 0x7c) return
        bits.invert()
        if ((sumBytes(b, 13) and 0xff) != b[13]) return
        val e6016 = ((b[4] shr 6) and 0x3) == 2
        val r = Builder(if (e6016) "EMOS E6016" else "EMOS E6018", "Weather", "${b[3]}", "CHECKSUM")
        r.channel = ((b[8] shr 4) and 0x3) + 1; r.battery = (b[12] shr 2) and 1 == 1
        val traw = (((b[8] and 0x0f) shl 12) or (b[9] shl 4)).toShort().toInt()
        r.add("Temperature", (traw shr 4) * 0.1, "C"); r.add("Humidity", b[10].toDouble(), "%", 0)
        if (e6016) { r.add("Wind", b[11] * 0.295, "m/s"); r.add("Direction", ((b[12] and 0xf0) shr 4) * 22.5, "deg", 0) }
        out += r.build()
    }

    // emos_e6016_rain.c: EMOS E6016 rain gauge (OOK PWM, 72 bits, inverted, sum)
    private fun emosRain(bits: IsmBits, out: Out) {
        val row = bits.findRepeatedRow(3, 72)
        if (row < 0 || bits.n[row] !in 72..73) return
        val b = bits.row(row)
        if (b[0] != 0x55 || b[1] != 0x5a || b[2] != 0x75) return
        bits.invert()
        if ((sumBytes(b, 8) and 0xff) != b[8]) return
        val r = Builder("EMOS E6016 rain", "Weather", "${b[3]}", "CHECKSUM")
        r.battery = (b[4] shr 6) != 0
        r.add("Rain", (((b[6] and 0x0f) shl 8) or b[7]) * 0.7, "mm")
        out += r.build()
    }

    // cotech_36_7959.c: Cotech 36-7959, SwitchDoc FT020T, Sainlogic weather stations (OOK Manchester, 112 bits, CRC-8)
    private fun cotech(bits: IsmBits, out: Out) {
        if (bits.rows() > 2) return
        if (bits.n[0] < 112 && (bits.rows() < 2 || bits.n[1] < 112)) return
        var b: IntArray? = null
        for (i in 0 until bits.rows()) {
            val pos = bits.search(i, 0, pat(0x01, 0x40), 12) + 12
            if (pos + 112 > bits.n[i]) continue
            b = bits.extract(i, pos, 112); break
        }
        val m = b ?: return
        if (Ism.crc8(m, 14, 0x31, 0xc0) != 0) return
        val r = Builder("Cotech 36-7959 / SwitchDoc FT020T", "Weather", "${((m[0] and 0x0f) shl 4) or (m[1] shr 4)}", "CRC")
        r.battery = (m[1] and 0x08) == 0
        r.add("Temperature", fToC(((((m[7] and 0x0f) shl 8) or m[8]) - 400) * 0.1), "C")
        r.add("Humidity", m[9].toDouble(), "%", 0)
        r.add("Wind", ((((m[1] and 0x01) shl 8) or m[2])) * 0.1, "m/s")
        r.add("Gust", (((((m[1] and 0x02) shr 1) shl 8) or m[3])) * 0.1, "m/s")
        r.add("Direction", (((((m[1] and 0x04) shr 2) shl 8) or m[4])).toDouble(), "deg", 0)
        r.add("Rain", (((m[5] and 0x0f) shl 8) or m[6]) * 0.1, "mm")
        if (m[12] <= 150) { r.add("Light", ((m[10] shl 8) or m[11] or ((m[7] and 0x80) shl 9)).toDouble(), "lux", 0); r.add("UV", m[12] * 0.1, "") }
        out += r.build()
    }

    // lacrosse_ws7000.c: LaCrosse / ELV WS7000 and WS2500 sensors (OOK PWM, nibbles separated by a 1 bit, XOR and sum)
    private fun ws7000(bits: IsmBits, out: Out) {
        val start = bits.search(0, 0, pat(0x01), 8) + 8
        if (start >= bits.n[0]) return
        val max = minOf(14 * 5, bits.n[0] - start)
        val b = IntArray(14)
        var len = 0
        var pos = start
        var left = max
        while (left >= 5 && len < 14) {
            if (bits.bit(0, pos + 4) != 1) break
            var v = 0
            for (k in 0 until 4) v = (v shl 1) or bits.bit(0, pos + k)
            b[len++] = Ism.reflect4(v) and 0xf
            pos += 5; left -= 5
        }
        if (len < 7) return
        val type = b[0]
        val addr = b[1] and 0x7
        if (type > 5) return
        val size = intArrayOf(3, 6, 3, 6, 10, 7)[type]
        if (len < size) return
        if (Ism.xorBytes(b, len - 1) != 0) return
        if ((Ism.addBytes(b, len - 1) + 5) and 0xf != b[len - 1]) return
        val name = arrayOf("WS7000-27 thermo", "WS7000-22 thermo-hygro", "WS7000-16 rain", "WS7000-15 wind", "WS7000-20 weather", "WS2500-19 light")[type]
        val r = Builder("LaCrosse / ELV $name", "Weather", "${(type shl 4) or addr}", "CHECKSUM")
        r.channel = addr
        val sign = if (b[1] and 0x8 != 0) -1 else 1
        val t = (b[4] * 10 + b[3] + b[2] * 0.1) * sign
        when (type) {
            0 -> r.add("Temperature", t, "C")
            1 -> { r.add("Temperature", t, "C"); r.add("Humidity", (b[7] * 10 + b[6] + b[5] * 0.1), "%", 0) }
            2 -> r.add("Rain", ((b[4] shl 8) or (b[3] shl 4) or b[2]) * 0.3, "mm")
            3 -> { r.add("Wind", b[4] * 10 + b[3] + b[2] * 0.1, "km/h"); r.add("Direction", ((b[7] shr 2) * 100 + b[6] * 10 + b[5]).toDouble(), "deg", 0) }
            4 -> { r.add("Temperature", t, "C"); r.add("Humidity", (b[7] * 10 + b[6] + b[5] * 0.1), "%", 0); r.add("Pressure", (b[10] * 100 + b[9] * 10 + b[8] + 200).toDouble(), "hPa", 0) }
            5 -> { var lux = (b[4] * 100 + b[3] * 10 + b[2]).toDouble(); repeat(b[5]) { lux *= 10 }; r.add("Light", lux, "lux", 0) }
        }
        out += r.build()
    }


    // ---------------------------------------------------------------- FSK sensors (433 / 868 MHz)

    private fun crc16lsb(m: IntArray, n: Int, poly: Int, init: Int): Int {
        var r = init
        for (i in 0 until n) { r = r xor m[i]; repeat(8) { r = if (r and 1 != 0) (r shr 1) xor poly else r shr 1 } }
        return r and 0xffff
    }

    private fun lfsrDigest8Reverse(m: IntArray, n: Int, gen: Int, key0: Int): Int {
        var sum = 0
        var key = key0
        for (k in n - 1 downTo 0) for (i in 7 downTo 0) {
            if ((m[k] shr i) and 1 != 0) sum = sum xor key
            key = if (key and 1 != 0) (key shr 1) xor gen else key shr 1
        }
        return sum and 0xff
    }

    /** The bytes after [preamble] in row 0, or null when it is not there with [bytes] after it. */
    private fun after(bits: IsmBits, preamble: IntArray, plen: Int, bytes: Int, row: Int = 0): IntArray? {
        val at = bits.search(row, 0, preamble, plen)
        if (at >= bits.n[row] || at + plen + bytes * 8 > bits.n[row]) return null
        return bits.extract(row, at + plen, bytes * 8)
    }

    // inkbird_ith20r.c: Inkbird ITH-20R / IBS-P01R thermo-hygrometers (FSK PCM 100 µs, CRC-16)
    private fun inkbird(bits: IsmBits, out: Out) {
        if (bits.rows() != 1 || bits.n[0] < 187) return
        val m = after(bits, pat(0xaa, 0xaa, 0xaa, 0x2d, 0xd4), 40, 19) ?: return
        if ((m[17] shl 8 or m[16]) != crc16lsb(m, 16, 0xA001, 0x86F4)) return
        val r = Builder("Inkbird ITH-20R / IBS-P01R", "Weather", "${(m[9] shl 8) or m[8]}", "CRC")
        r.channel = m[4]
        r.add("Temperature", ((m[11] shl 8) or m[10]).toShort() * 0.1, "C")
        val t2 = ((m[13] shl 8) or m[12]).toShort()
        if (t2.toInt() != 0) r.add("Probe", t2 * 0.1, "C")
        r.add("Humidity", ((m[15] shl 8) or m[14]) * 0.1, "%", 0)
        r.add("Battery", m[7].toDouble(), "%", 0)
        out += r.build()
    }

    // thermopro_tx7b.c: ThermoPro TX-7B sensor for the TP260B / TP280B stations (FSK PCM 108 µs, LFSR digest)
    private fun tx7b(bits: IsmBits, out: Out) {
        if (bits.rows() > 1 || bits.n[0] > 260) return
        val at = bits.search(0, 0, pat(0x55, 0x2d, 0xd4), 24)
        if (at >= bits.n[0] || bits.n[0] - at < 96) return
        val b = bits.extract(0, at + 24, 72)
        if (lfsrDigest8Reverse(b, 8, 0x98, 0x25) != b[8]) return
        val r = Builder("ThermoPro TX-7B", "Weather", hex(b[0], 2), "CHECKSUM")
        r.channel = ((b[1] and 0x30) shr 4) + 1; r.battery = b[1] shr 7 == 0
        r.add("Temperature", (((b[2] shl 4) or ((b[3] and 0xF0) shr 4)) - 400) * 0.1, "C")
        r.add("Humidity", b[4].toDouble(), "%", 0)
        out += r.build()
    }

    // thermopro_tp28b.c: ThermoPro TP28b two-probe BBQ thermometer (FSK PCM 105 µs, sum)
    private fun tp28b(bits: IsmBits, out: Out) {
        if (bits.rows() > 1 || bits.n[0] !in 240..451) return
        val b = after(bits, pat(0xd2, 0xaa, 0x2d, 0xd4), 32, 18) ?: return
        if ((sumBytes(b, 16) and 0xff) != b[16]) return
        fun bcd(lo: Int, hi: Int) = ((hi and 0xF0) shr 4) * 100.0 + (hi and 0x0F) * 10 + ((lo and 0xF0) shr 4) + (lo and 0x0F) * 0.1
        val r = Builder("ThermoPro TP28b", "Weather", hex((b[14] shl 8) or b[15], 4), "CHECKSUM")
        r.add("Probe 1", bcd(b[0], b[1]), "C"); r.add("Probe 2", bcd(b[6], b[7]), "C")
        out += r.build()
    }

    // tfa_marbella.c: TFA 30.3238 Marbella pool thermometer (FSK PCM 105 µs, LFSR digest)
    private fun marbella(bits: IsmBits, out: Out) {
        val at = bits.search(0, 0, pat(0xaa, 0x2d, 0xd4), 24)
        if (at + 88 > bits.n[0]) return
        val m = bits.extract(0, at, 88)
        if (m[9] != 0xAA) return
        if (Ism.lfsrDigest8Reflect(m.copyOfRange(3, 10), 7, 0x31, 0x31) != m[10]) return
        val r = Builder("TFA 30.3238 Marbella pool", "Weather", hex((m[3] shl 16) or (m[4] shl 8) or m[5], 6), "CRC")
        r.battery = (m[6] shr 7) and 1 == 0
        r.add("Temperature", (((m[7] shl 4) or (m[8] shr 4)) - 400) * 0.1, "C")
        out += r.build()
    }

    // bresser_leakage.c: Bresser water leakage sensor 7009975 (FSK PCM 124 µs, CRC-16)
    private fun bresserLeak(bits: IsmBits, out: Out) {
        if (bits.rows() != 1 || bits.n[0] !in 160..440) return
        val m = after(bits, pat(0xaa, 0xaa, 0x2d, 0xd4), 32, 18) ?: return
        if (((m[0] shl 8) or m[1]) != Ism.crc16(m, 5, 0x1021, 0x0000, 2)) return
        val chan = m[6] and 0x7
        val alarm = (m[7] and 0x80) shr 7
        if (m[6] shr 4 != 5 || alarm == (m[7] and 0x40) shr 6 || chan == 0) return
        val r = Builder("Bresser water leakage", "Sensor", hex((m[2] shl 24) or (m[3] shl 16) or (m[4] shl 8) or m[5], 8), "CRC")
        r.channel = chan; r.battery = (m[7] and 0x30) != 0
        r.text("State", if (alarm == 1) "Leak!" else "Dry")
        out += r.build()
    }

    // bresser_lightning.c: Bresser lightning sensor 7009976 (FSK PCM 124 µs, LFSR digest)
    private fun bresserLightning(bits: IsmBits, out: Out) {
        if (bits.rows() != 1 || bits.n[0] !in 112..440) return
        val m = after(bits, pat(0xaa, 0xaa, 0x2d, 0xd4), 32, 10) ?: return
        val type = m[6] shr 4
        val chan = m[6] and 0x07
        val low = (m[5] and 0x08) shr 3
        for (i in 0 until 10) m[i] = m[i] xor 0xaa
        val chk = (m[0] shl 8) or m[1]
        if ((chk xor Ism.lfsrDigest16(m, 8, 0x8810, 0xabf9, 2)) != 0x899e) return
        if (type != 9 || chan != 0) return
        val r = Builder("Bresser lightning sensor", "Weather", hex((m[2] shl 8) or m[3], 4), "CRC")
        r.battery = low == 0
        r.add("Storm distance", m[7].toDouble(), "km", 0)
        r.add("Strikes", ((m[4] shr 4) * 100 + (m[4] and 0xf) * 10 + (m[5] shr 4)).toDouble(), "", 0)
        out += r.build()
    }

    // fineoffset_wh45.c / wh46.c: Ecowitt WH45 / WH46 air quality (FSK PCM 58 µs, CRC-8 and sum)
    private fun wh4546(bits: IsmBits, out: Out) {
        val at = bits.search(0, 0, pat(0xaa, 0x2d, 0xd4), 24) + 24
        if (at + 15 * 8 > bits.n[0]) return
        val fam = bits.extract(0, at, 8)[0]
        val size = when (fam) { 0x45 -> 15; 0x46 -> 21; else -> return }
        if (at + size * 8 > bits.n[0]) return
        if (fam == 0x45 && bits.n[0] !in 170..240) return
        val b = bits.extract(0, at, size * 8)
        if (Ism.crc8(b, size - 2, 0x31, 0x00) != b[size - 2] || (sumBytes(b, size - 1) and 0xff) != b[size - 1]) return
        val r = Builder(if (fam == 0x45) "Ecowitt WH45" else "Ecowitt WH46", "Weather", hex((b[1] shl 16) or (b[2] shl 8) or b[3], 6), "CRC")
        val bars = ((b[7] and 0x40) shr 4) or ((b[9] and 0xC0) shr 6)
        r.battery = bars > 1
        r.add("Temperature", ((((b[4] and 0x7) shl 8) or b[5]) - 400) * 0.1, "C")
        r.add("Humidity", b[6].toDouble(), "%", 0)
        if (fam == 0x46) r.add("PM1", ((b[13] shl 8) or b[14]) * 0.1, "µg/m³")
        r.add("PM2.5", ((((b[7] and 0x3f) shl 8) or b[8])) * 0.1, "µg/m³")
        if (fam == 0x46) r.add("PM4", ((b[15] shl 8) or b[16]) * 0.1, "µg/m³")
        r.add("PM10", ((((b[9] and 0x3f) shl 8) or b[10])) * 0.1, "µg/m³")
        r.add("CO2", ((b[11] shl 8) or b[12]).toDouble(), "ppm", 0)
        out += r.build()
    }

    // fineoffset_wh43.c: Ecowitt WH41 / WH43 PM2.5 sensor (FSK PCM 58 µs, CRC-8 and sum)
    private fun wh43(bits: IsmBits, out: Out) {
        val b = after(bits, pat(0xAA, 0x2D, 0xD4), 24, 10) ?: return
        if (b[0] != 0x43) return
        if (Ism.crc8(b, 8, 0x31, 0x00) != b[8] || (sumBytes(b, 9) and 0xff) != b[9]) return
        val r = Builder("Ecowitt WH41 / WH43", "Weather", hex((b[1] shl 16) or (b[2] shl 8) or b[3], 6), "CRC")
        r.battery = (((b[4] and 0x40) shr 4) or ((b[6] and 0xC0) shr 6)) > 1
        r.add("PM2.5", ((((b[4] and 0x3F) shl 8) or b[5]) / 10).toDouble(), "µg/m³", 0)
        r.add("PM10", ((((b[6] and 0x3F) shl 8) or b[7]) / 10).toDouble(), "µg/m³", 0)
        out += r.build()
    }

    // fineoffset_wh55.c: Ecowitt WH55 water leak sensor (FSK PCM 60 µs, CRC-8)
    private fun wh55(bits: IsmBits, out: Out) {
        if (bits.rows() != 1) return
        val pos = bits.search(0, 0, pat(0xAA, 0x2D, 0xD4, 0x55), 32) + 24
        if (pos + 72 > bits.n[0]) return
        val b = bits.extract(0, pos, 96)
        if (Ism.crc8(b, 9, 0x31, 0x00) != 0) return
        val r = Builder("Ecowitt WH55 leak", "Sensor", hex((b[2] shl 8) or b[3], 4).uppercase(), "CRC")
        r.channel = (b[1] shr 4) + 1; r.battery = b[4] > 1
        r.text("State", if ((b[7] shr 6) and 1 == 1) "Leak!" else "Dry")
        out += r.build()
    }

    // fineoffset_wn34.c: Ecowitt WN34 / WN34D / WN38 temperature probes (FSK PCM 58 µs, CRC-8 and sum)
    private fun wn34(bits: IsmBits, out: Out) {
        val b = after(bits, pat(0xAA, 0x2D, 0xD4), 24, 9) ?: return
        if (b[0] != 0x34 && b[0] != 0x38) return
        if (Ism.crc8(b, 7, 0x31, 0x00) != b[7] || (sumBytes(b, 8) and 0xff) != b[8]) return
        val raw = ((((b[4] and 0x0F) shl 12) or (b[5] shl 4)).toShort().toInt()) shr 4
        val sub = (b[4] and 0xF0) shr 4
        val name = if (b[0] == 0x38) "Ecowitt WN38" else if (sub == 4) "Ecowitt WN34D" else "Ecowitt WN34"
        val r = Builder(name, "Weather", hex((b[1] shl 16) or (b[2] shl 8) or b[3], 6), "CRC")
        r.battery = (b[6] and 0x7f) * 20 > 1200
        r.add("Temperature", if (sub == 4) raw * 0.1 else raw * 0.1 - 40, "C")
        out += r.build()
    }

    // fineoffset_ws80.c / ws90.c: Ecowitt WS80 and WS90 ultrasonic all-in-one stations (FSK PCM 58 µs, CRC-8 and sum)
    private fun ws8090(bits: IsmBits, out: Out) {
        if (bits.n[0] !in 168..500) return
        val ws90 = bits.search(0, 0, pat(0xaa, 0xaa, 0x2d, 0xd4), 32).let { at -> at + 32 + 32 * 8 <= bits.n[0] && bits.extract(0, at + 32, 8)[0] == 0x90 }
        val b = if (ws90) after(bits, pat(0xaa, 0xaa, 0x2d, 0xd4), 32, 32) ?: return
        else { if (bits.n[0] > 240) return; after(bits, pat(0xaa, 0x2d, 0xd4), 24, 18) ?: return }
        val size = if (ws90) 32 else 18
        if (b[0] != (if (ws90) 0x90 else 0x80)) return
        if (Ism.crc8(b, size - 1, 0x31, 0x00) != 0 || (sumBytes(b, size - 1) and 0xff) != b[size - 1]) return
        val r = Builder(if (ws90) "Ecowitt WS90" else "Ecowitt WS80", "Weather", hex((b[1] shl 16) or (b[2] shl 8) or b[3], 6), "CRC")
        r.battery = b[6] * 20 > 1500
        val traw = ((b[7] and 0x03) shl 8) or b[8]
        if (traw != 0x3ff) r.add("Temperature", (traw - 400) * 0.1, "C")
        if (b[9] != 0xff) r.add("Humidity", b[9].toDouble(), "%", 0)
        val avg = ((b[7] and 0x10) shl 4) or b[10]
        val dir = ((b[7] and 0x20) shl 3) or b[11]
        val max = ((b[7] and 0x40) shl 2) or b[12]
        if (avg != 0x1ff) r.add("Wind", avg * 0.1, "m/s")
        if (max != 0x1ff) r.add("Gust", max * 0.1, "m/s")
        if (dir != 0x1ff) r.add("Direction", dir.toDouble(), "deg", 0)
        if (b[13] != 0xff) r.add("UV", b[13] * 0.1, "")
        val light = (b[4] shl 8) or b[5]
        if (light != 0xffff) r.add("Light", light * 10.0, "lux", 0)
        if (ws90) {
            val pressure = (b[14] shl 8) or b[15]
            if (pressure != 0x3fff) r.add("Pressure", pressure.toDouble(), "hPa", 0)
            r.add("Rain", ((b[19] shl 8) or b[20]) * 0.1, "mm")
        }
        out += r.build()
    }

    // lacrosse_tx31u.c: LaCrosse TX31U-IT, The Weather Channel WS-1910TWC-IT (FSK PCM 116 µs, CRC-8, BCD measurements)
    private fun tx31u(bits: IsmBits, out: Out) {
        if (bits.rows() > 1) return
        val at = bits.search(0, 0, pat(0xaa, 0xaa, 0x2d, 0xd4), 32)
        if (at >= bits.n[0]) return
        val len = (bits.n[0] - at) / 8
        if (len !in 9..20) return
        val m = bits.extract(0, at, len * 8)
        val count = m[5] and 0x7
        val expected = 6 + count * 2 + 1
        if (len < expected || Ism.crc8(m.copyOfRange(4, 6 + count * 2), 2 + count * 2, 0x31, 0x00) != m[expected - 1]) return
        val r = Builder("LaCrosse TX31U-IT", "Weather", "${((m[4] and 0xf) shl 2) or ((m[5] shr 6) and 3)}", "CRC")
        r.battery = (m[5] shr 3) and 1 == 0
        val ext = (m[5] shr 4) and 1 == 0
        for (k in 0 until count) {
            val type = m[6 + 2 * k] shr 4
            val n1 = m[6 + 2 * k] and 0xf; val n2 = m[7 + 2 * k] shr 4; val n3 = m[7 + 2 * k] and 0xf
            when (type) {
                0 -> r.add("Temperature", 10.0 * n1 + n2 + 0.1 * n3 - 40, "C")
                1 -> r.add("Humidity", (100 * n1 + 10 * n2 + n3).toDouble(), "%", 0)
                3 -> if (ext) { r.add("Wind", ((n2 shl 4) + n3) * 0.36, "km/h"); r.add("Direction", n1 * 22.5, "deg", 0) }
                4 -> if (ext && n1 and 1 == 0) r.add("Gust", ((n2 shl 4) + n3) * 0.36, "km/h")
            }
        }
        out += r.build()
    }

    // lacrosse_tx34.c: LaCrosse TX34-IT rain gauge (FSK PCM 58 µs, CRC-8)
    private fun tx34(bits: IsmBits, out: Out) {
        for (row in 0 until bits.rows()) {
            val pos = bits.search(row, 0, pat(0xa2, 0xdd, 0x40), 20) + 20
            if (pos + 40 > bits.n[row]) continue
            val b = bits.extract(row, pos, 40)
            if (Ism.crc8(b, 4, 0x31, 0x00) != b[4] || (b[0] and 0xF0) shr 4 != 5) continue
            val r = Builder("LaCrosse TX34-IT rain", "Weather", "${((b[0] and 0x0F) shl 2) or (b[1] shr 6)}", "CRC")
            r.battery = (b[1] and 0x10) == 0
            r.add("Rain", ((b[2] shl 8) or b[3]) * 0.222, "mm")
            out += r.build()
        }
    }


    // ---------------------------------------------------------------- North America (915 MHz meters, 433 MHz sensors)

    private fun meterType(t: Int) = when (t and 0x0f) { 4, 5, 7, 8 -> "Electric"; 0, 1, 2, 9, 12 -> "Gas"; 3, 11, 13 -> "Water"; else -> "Meter" }

    // scmplus.c: Itron ERT Standard Consumption Message Plus (OOK Manchester 30 µs, 128 bits, CRC-16)
    private fun scmPlus(bits: IsmBits, out: Out) {
        if (bits.n[0] < 128) return
        val at = bits.search(0, 0, pat(0x16, 0xA3, 0x1E), 24)
        if (at >= bits.n[0] || bits.n[0] - at < 128) return
        val b = bits.extract(0, at, 128)
        if (((b[14] shl 8) or b[15]) != Ism.crc16(b, 12, 0x1021, 0x0971, 2)) return
        val id = ((b[4].toLong() shl 24) or (b[5].toLong() shl 16) or (b[6].toLong() shl 8) or b[7].toLong())
        val r = Builder("Itron ERT SCM+ (${meterType(b[3])})", "Meter", "$id", "CRC")
        r.add("Consumption", ((b[8].toLong() shl 24) or (b[9].toLong() shl 16) or (b[10].toLong() shl 8) or b[11].toLong()).toDouble(), "", 0)
        out += r.build()
    }

    // ert_idm.c: Itron ERT Interval Data Message (OOK Manchester 30 µs, 720 bits, CRC-16)
    private fun ertIdm(bits: IsmBits, out: Out) {
        if (bits.n[0] < 720) return
        val at = bits.search(0, 0, pat(0x16, 0xA3, 0x1C), 24)
        if (at >= bits.n[0] || bits.n[0] - at < 720) return
        val b = bits.extract(0, at, 720)
        if (((b[88] shl 8) or b[89]) != Ism.crc16(b, 86, 0x1021, 0xD895, 2)) return
        val id = ((b[7].toLong() shl 24) or (b[8].toLong() shl 16) or (b[9].toLong() shl 8) or b[10].toLong())
        val r = Builder("Itron ERT IDM (${meterType(b[6])})", "Meter", "$id", "CRC")
        r.add("Consumption", ((b[27].toLong() shl 24) or (b[28].toLong() shl 16) or (b[29].toLong() shl 8) or b[30].toLong()).toDouble(), "", 0)
        out += r.build()
    }

    // acurite_01185m.c: Acurite 01185M grill / meat thermometer (OOK PWM, 56 bits, inverted and reflected, sum)
    private fun acurite01185m(bits: IsmBits, out: Out) {
        bits.invert()
        for (row in 0 until bits.rows()) {
            if (bits.n[row] != 56) continue
            val b = IntArray(7) { reverse8(bits.row(row)[it]) }
            val sum = sumBytes(b, 6)
            if ((sum and 0xff) != b[6]) continue
            if (sum == 0) return
            val r = Builder("Acurite 01185M grill", "Weather", "${b[0]}", "CHECKSUM")
            r.channel = b[1] and 0x0f; r.battery = b[1] shr 7 == 0
            val t1 = (b[2] shl 8) or b[3]
            val t2 = (b[4] shl 8) or b[5]
            if (t1 in 201..6999) r.add("Meat", fToC((t1 - 900) * 0.1), "C")
            if (t2 in 201..6999) r.add("Ambient", fToC((t2 - 900) * 0.1), "C")
            out += r.build()
            return
        }
    }

    // lacrosse_breezepro.c: LaCrosse BreezePro LTV-WSDTH04 wind and temperature (915 MHz, FSK PCM 107 µs, CRC-8)
    private fun breezePro(bits: IsmBits, out: Out) {
        if (bits.n[0] < 264) return
        val b = after(bits, pat(0xd2, 0xaa, 0x2d, 0xd4), 32, 11) ?: return
        if (Ism.crc8(b, 11, 0x31, 0x00) != 0) return
        val t = (((b[4] shl 4) or ((b[5] and 0xf0) shr 4)) - 400) * 0.1
        val hum = ((b[5] and 0x0f) shl 8) or b[6]
        val speed = ((b[7] shl 4) or ((b[8] and 0xf0) shr 4)) * 0.1
        val dir = ((b[8] and 0x0f) shl 8) or b[9]
        if (hum > 100 || t < -40 || t > 70 || dir > 360 || speed > 200) return
        val r = Builder("LaCrosse BreezePro", "Weather", hex((b[0] shl 16) or (b[1] shl 8) or b[2], 6), "CRC")
        r.add("Temperature", t, "C"); r.add("Humidity", hum.toDouble(), "%", 0)
        r.add("Wind", speed, "km/h"); r.add("Direction", dir.toDouble(), "deg", 0)
        out += r.build()
    }

    // ---------------------------------------------------------------- the table

    val protos: List<Ism.Proto> = listOf(
        p("Chacon DIO / KlikAanKlikUit / Home Easy / Nexa / Proove", Mod.OokPpm, Timing(280f, 1300f, 3200f, sync = 2650f, tol = 250f), ::kaku),
        p("Somfy RTS (433.42 MHz)", Mod.OokPcm, Timing(604f, 604f, 10000f, 3000f, tol = 120f), ::somfyRts),
        p("Blyss doorbell", Mod.OokPwm, Timing(500f, 1500f, 8000f, 2500f), ::blyss),
        p("Silvercrest remote (Lidl)", Mod.OokPwm, Timing(264f, 744f, 12000f, 5000f), ::silvercrest),
        p("Visonic PowerCode (868.95 MHz)", Mod.OokPwm, Timing(400f, 800f, 5000f, 900f), ::visonic),
        p("X10 RF", Mod.OokPpm, Timing(562f, 1687f, 6000f, 2200f), ::x10Rf),
        p("X10 Security", Mod.OokPpm, Timing(562f, 1687f, 6000f, 2200f), ::x10Sec),
        p("inFactory / nor-tec / FreeTec NC-3982", Mod.OokPpm, Timing(2000f, 4000f, 5000f, sync = 500f, tol = 750f), ::infactory),
        p("Esperanza EWS-103", Mod.OokPpm, Timing(2000f, 4000f, 9400f, 4400f), ::esperanza),
        p("Conrad S3318P / Hama / Sencor", Mod.OokPpm, Timing(1900f, 3800f, 9400f, 4400f), ::s3318p),
        p("Alecto V1 / Ventus W044 / Balance RF-WS105", Mod.OokPpm, Timing(2000f, 4000f, 10000f, 7000f), ::alecto),
        p("Fine Offset WS2032 / Sainlogic", Mod.OokPwm, Timing(500f, 1000f, 4000f, 750f), ::ws2032),
        p("Rubicson 48942 pool", Mod.OokPwm, Timing(280f, 480f, 6000f, 5000f, 730f), ::rubicsonPool),
        p("TFA 30.3160 pool", Mod.OokPpm, Timing(2000f, 4600f, 10000f, 7800f), ::tfaPool),
        p("TFA 30.3233 Drop rain gauge", Mod.OokPwm, Timing(255f, 510f, 2500f, 1300f, 750f), ::tfaDrop),
        p("EMOS E6016 / E6018", Mod.OokPwm, Timing(280f, 796f, 850f, 3000f, 1836f), ::emosE6016),
        p("EMOS E6016 rain gauge", Mod.OokPwm, Timing(300f, 800f, 2500f, 1000f), ::emosRain),
        p("Cotech 36-7959 / SwitchDoc FT020T", Mod.OokMcZero, Timing(500f, 0f, 1200f, 1200f), ::cotech),
        p("LaCrosse / ELV WS7000, WS2500", Mod.OokPwm, Timing(400f, 800f, 1100f), ::ws7000),
        p("Inkbird ITH-20R / IBS-P01R", Mod.FskPcm, Timing(100f, 100f, 4000f), ::inkbird),
        p("ThermoPro TX-7B", Mod.FskPcm, Timing(108f, 108f, 1500f), ::tx7b),
        p("ThermoPro TP28b", Mod.FskPcm, Timing(105f, 105f, 5500f), ::tp28b),
        p("TFA 30.3238 Marbella pool", Mod.FskPcm, Timing(105f, 105f, 2000f), ::marbella),
        p("Bresser water leakage", Mod.FskPcm, Timing(124f, 124f, 25000f), ::bresserLeak),
        p("Bresser lightning sensor", Mod.FskPcm, Timing(124f, 124f, 25000f), ::bresserLightning),
        p("Ecowitt WH45 / WH46 air quality", Mod.FskPcm, Timing(58f, 58f, 2500f), ::wh4546),
        p("Ecowitt WH41 / WH43 PM2.5", Mod.FskPcm, Timing(58f, 58f, 2500f), ::wh43),
        p("Ecowitt WH55 leak", Mod.FskPcm, Timing(60f, 60f, 2500f), ::wh55),
        p("Ecowitt WN34 / WN38 probes", Mod.FskPcm, Timing(58f, 58f, 2500f), ::wn34),
        p("Ecowitt WS80 / WS90", Mod.FskPcm, Timing(58f, 58f, 3000f), ::ws8090),
        p("LaCrosse TX31U-IT", Mod.FskPcm, Timing(116f, 116f, 20000f), ::tx31u),
        p("LaCrosse TX34-IT rain", Mod.FskPcm, Timing(58f, 58f, 4000f), ::tx34),
        p("Itron ERT SCM+", Mod.OokMcZero, Timing(30f, 0f, 64f), ::scmPlus),
        p("Itron ERT IDM", Mod.OokMcZero, Timing(30f, 0f, 20000f), ::ertIdm),
        p("Acurite 01185M grill", Mod.OokPwm, Timing(840f, 2070f, 6000f, 3000f, 6600f), ::acurite01185m),
        p("LaCrosse BreezePro", Mod.FskPcm, Timing(107f, 107f, 5900f), ::breezePro),
    )
}
