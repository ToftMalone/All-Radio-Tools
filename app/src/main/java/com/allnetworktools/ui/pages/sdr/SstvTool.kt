package com.allnetworktools.ui.pages.sdr

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.R
import com.allnetworktools.data.sdr.SdrRepository
import com.allnetworktools.data.sdr.SstvDecoder
import com.allnetworktools.data.sdr.SstvImage
import com.allnetworktools.data.sdr.SstvReceiver
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.AntFilterChip
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.pages.TopBarAction
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.HeroCard
import com.allnetworktools.ui.tools.HeroChip
import com.allnetworktools.ui.tools.HostInputField
import com.allnetworktools.ui.tools.StartButton
import com.allnetworktools.ui.tools.ToolButton
import com.allnetworktools.ui.tools.ToolButtons
import com.allnetworktools.ui.tools.BtnKind
import com.allnetworktools.ui.tools.ToolEmpty
import com.allnetworktools.util.Export
import com.allnetworktools.util.S
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineScope

/** A picture ready to show: its decoder data and the bitmap made from it. */
class SstvPicture(val image: SstvImage, val bitmap: ImageBitmap, private val raw: Bitmap) {
    fun png(): ByteArray = ByteArrayOutputStream().also { raw.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
}

private fun toPicture(i: SstvImage): SstvPicture {
    val px = IntArray(i.rgb.size) { k -> if (k / i.width < i.lines) i.rgb[k] or (0xFF shl 24) else 0xFF202124.toInt() }
    val b = Bitmap.createBitmap(px, i.width, i.height, Bitmap.Config.ARGB_8888)
    return SstvPicture(i, b.asImageBitmap(), b)
}

/** SSTV pictures over narrowband FM: the ISS on 145.800 MHz, the 2 m SSTV frequency, or any frequency typed in. */
class SstvController(repo: SdrRepository, scope: CoroutineScope, onAcquire: () -> Unit = {}) :
    SdrRxController(repo, scope, onAcquire, SdrGain(listOf(16, 24, 32, 40), 32, listOf(20, 30, 40), 30, amp = true), "sdr-sstv") {
    var frequencyMhz by mutableStateOf("145.800")
    var current by mutableStateOf<SstvPicture?>(null)
        private set
    var history by mutableStateOf<List<SstvPicture>>(emptyList())
        private set
    var receiving by mutableStateOf<String?>(null)
        private set
    var levelDb by mutableStateOf(-120.0)
        private set

    fun parsedHz(): Long? = frequencyMhz.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in 30.0..1000.0 }?.let { (it * 1e6).roundToLong() }

    override val sampleRate = SstvReceiver.SAMPLE_RATE
    override fun tuneHz() = (parsedHz() ?: 145_800_000L) - sampleRate / 4

    override fun newSink(): SdrSink {
        val done = ArrayList<SstvImage>()
        val decoder = SstvDecoder { synchronized(done) { done += it } }
        val rx = SstvReceiver(decoder)
        return object : SdrSink {
            override fun feed(buf: ByteArray, len: Int) = rx.feed(buf, len)
            override fun tick(nowMs: Long) {
                val finished = synchronized(done) { done.toList().also { done.clear() } }.map(::toPicture)
                val progress = decoder.progress()?.let(::toPicture)
                val mode = decoder.receiving
                val lvl = rx.levelDb
                post {
                    if (finished.isNotEmpty()) { history = (finished.reversed() + history).take(12); current = finished.last() }
                    if (progress != null) current = progress
                    receiving = mode
                    levelDb = lvl
                }
            }
        }
    }

    fun select(p: SstvPicture) { current = p }

    // ---- sending a picture ----

    /** Where and how a picture goes out: the SSTV calling frequencies. */
    class SendChannel(val label: String, val hz: Long, val mode: com.allnetworktools.data.sdr.TxMode)

    val sendChannels = listOf(
        SendChannel("2 m FM · 144.500", 144_500_000L, com.allnetworktools.data.sdr.TxMode.Fm),
        SendChannel("20 m · 14.230 USB", 14_230_000L, com.allnetworktools.data.sdr.TxMode.Usb),
        SendChannel("40 m · 7.171 LSB", 7_171_000L, com.allnetworktools.data.sdr.TxMode.Lsb),
        SendChannel("80 m · 3.735 LSB", 3_735_000L, com.allnetworktools.data.sdr.TxMode.Lsb),
    )
    var sendChannel by mutableStateOf(sendChannels[0])
    var sendMode by mutableStateOf("Robot 36")
    /** The picture chosen, as picked (any size). */
    var photo by mutableStateOf<android.graphics.Bitmap?>(null)

    fun sendEmission(ch: SendChannel = sendChannel): com.allnetworktools.data.sdr.Emission {
        val (below, above) = when (ch.mode) {
            com.allnetworktools.data.sdr.TxMode.Fm -> 6_000 to 6_000
            com.allnetworktools.data.sdr.TxMode.Usb -> 0 to 3_000
            com.allnetworktools.data.sdr.TxMode.Lsb -> 3_000 to 0
            com.allnetworktools.data.sdr.TxMode.Am -> 3_000 to 3_000
        }
        return com.allnetworktools.data.sdr.Emission.around(ch.hz, below, above, com.allnetworktools.data.sdr.TxUse.Amateur)
    }

    /** Sends the chosen photo, with the call sign written on it, in [sendMode] on [sendChannel] (reception pauses). */
    fun sendPicture(device: com.allnetworktools.data.sdr.SdrDevice?) {
        val bmp = photo ?: return
        val m = com.allnetworktools.data.sdr.Sstv.modes.first { it.name == sendMode }
        val pixels = sstvPixels(bmp, m.width, m.height, com.allnetworktools.data.sdr.TxSettings.callsign)
        val ch = sendChannel
        val audio = com.allnetworktools.data.sdr.SstvEncoder(m, pixels, AUDIO_RATE)
        val stream = com.allnetworktools.data.sdr.TxStream(com.allnetworktools.data.sdr.AudioModulator(audio, ch.mode, 3_000.0, voice = false), TX_RATE, TX_SHIFT.toDouble())
        tx.send(device, this, sendEmission(ch), TX_RATE, ch.hz - TX_SHIFT, stream)
    }

    private companion object {
        const val AUDIO_RATE = 48_000
        const val TX_RATE = 2_400_000
        const val TX_SHIFT = 300_000L
    }
}

/** [src] scaled to cover [w]×[h], centre-cropped, with [label] (the call sign) written in the top left corner, as 0xRRGGBB. */
internal fun sstvPixels(src: android.graphics.Bitmap, w: Int, h: Int, label: String): IntArray {
    val scale = maxOf(w.toFloat() / src.width, h.toFloat() / src.height)
    val out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(out)
    val dst = android.graphics.RectF((w - src.width * scale) / 2, (h - src.height * scale) / 2, (w + src.width * scale) / 2, (h + src.height * scale) / 2)
    canvas.drawBitmap(src, null, dst, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
    if (label.isNotBlank()) {
        val size = h / 9f
        val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = size; typeface = android.graphics.Typeface.DEFAULT_BOLD; color = android.graphics.Color.WHITE }
        val back = android.graphics.Paint().apply { color = android.graphics.Color.argb(170, 0, 0, 0) }
        val tw = text.measureText(label)
        canvas.drawRect(0f, 0f, tw + size * 0.8f, size * 1.4f, back)
        canvas.drawText(label, size * 0.4f, size * 1.05f, text)
    }
    val px = IntArray(w * h)
    out.getPixels(px, 0, w, 0, 0, w, h)
    for (i in px.indices) px[i] = px[i] and 0xFFFFFF
    out.recycle()
    return px
}

@Composable
fun SstvTool(vm: MainViewModel) {
    val c = vm.tools.sstv
    val actions = LocalActions.current
    val device by vm.sdrDevice.collectAsStateWithLifecycle()
    c.current?.let { p ->
        TopBarAction(Sym.Download) {
            val png = p.png()
            actions.saveFile("sstv-${p.image.mode.replace(' ', '_')}-${Export.stamp(p.image.startMs)}.png", "image/png") { png }
        }
    }
    PageColumn {
        HeroCard {
            val p = c.current
            Text(c.receiving ?: p?.image?.mode ?: S(R.string.sstv_waiting), style = rf(24, 30, 600))
            Text(
                when {
                    c.receiving != null && p != null -> S(R.string.sstv_progress, p.image.lines, p.image.height)
                    p != null -> S(if (p.image.complete) R.string.sstv_complete else R.string.sstv_cut, p.image.lines, p.image.height)
                    else -> S(R.string.sstv_frequency_line, c.frequencyMhz.replace('.', ','))
                },
                style = rf(14, 20, tnum = true),
            )
            Row(Modifier.padding(top = 12.dp)) {
                when {
                    c.error != null -> HeroChip(c.error!!, AntTheme.net.poor)
                    c.starting -> HeroChip(S(R.string.starting_the_hackrf), AntTheme.net.fair, blink = true)
                    c.receiving != null -> HeroChip(S(R.string.sstv_receiving), AntTheme.net.good, blink = true)
                    c.running -> HeroChip(S(R.string.sstv_listening, "%.0f".format(Locale.getDefault(), c.levelDb)), AntTheme.net.fair, blink = true)
                    device == null -> HeroChip(S(R.string.no_hackrf_plugged_in), AntTheme.net.poor)
                    else -> HeroChip(S(R.string.ready, device!!.name), AntTheme.net.good)
                }
            }
        }
        val d = device
        if (c.running || c.starting) StartButton(S(R.string.stop), Sym.Stop) { c.stop() }
        else StartButton(S(R.string.start_receiving), Sym.PlayArrow, enabled = d != null && c.parsedHz() != null) { d?.let(c::start) }
        val tab = ToolTabRow("SstvTool0", map = false)
        val p = c.current
        if (tab == ToolTab.Main) {
            if (p != null) {
                Image(
                    p.bitmap, p.image.mode, Modifier.fillMaxWidth().aspectRatio(p.image.width.toFloat() / p.image.height).clip(RoundedCornerShape(24.dp)),
                    contentScale = ContentScale.FillBounds,
                )
            } else if (c.running) ToolEmpty(Sym.PhotoCamera, S(R.string.sstv_none_yet), S(R.string.sstv_none_yet_body), null)
        }
        if (tab == ToolTab.Main) {
            if (c.history.size > 1 || (c.history.size == 1 && c.history[0] !== p)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    c.history.forEach { h ->
                        Image(
                            h.bitmap, h.image.mode,
                            Modifier.height(72.dp).aspectRatio(h.image.width.toFloat() / h.image.height).clip(RoundedCornerShape(12.dp)).clickable { c.select(h) },
                            contentScale = ContentScale.FillBounds,
                        )
                    }
                }
            }
        }
        if (tab == ToolTab.Settings && device?.kind == com.allnetworktools.data.sdr.SdrKind.HackRf) {
            TxSettingsCard()
            SendPictureCard(c, device)
        }
        if (tab == ToolTab.Settings) {
            SectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(S(R.string.frequency), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("145.800" to S(R.string.sstv_iss), "144.500" to S(R.string.sstv_2m), "433.400" to "70 cm").forEach { (f, l) ->
                            AntFilterChip("${f.replace('.', ',')} · $l", c.frequencyMhz == f, { if (c.frequencyMhz != f && !c.starting) { if (c.running) c.stop(); c.frequencyMhz = f } })
                        }
                    }
                    if (!c.running) HostInputField(c.frequencyMhz, { c.frequencyMhz = it }, S(R.string.frequency_mhz), Sym.Antenna, keyboardType = KeyboardType.Decimal)
                    GainSettings(c.gain) { c.applyGains() }
                }
            }
        }
        if (tab == ToolTab.Settings) {
            SectionCard {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                    Text(S(R.string.sstv_how_it_works) + "\n\n" + S(R.string.sstv_tx_note), style = rf(13, 18), color = cs.onSurfaceVariant)
                }
            }
        }
    }
}

/** Sends a photo from the phone as SSTV, with the call sign written on it. */
@Composable
private fun SendPictureCard(c: SstvController, device: com.allnetworktools.data.sdr.SdrDevice?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) c.photo = runCatching {
            val src = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
            android.graphics.ImageDecoder.decodeBitmap(src) { d, info, _ ->
                // Small enough to work with: the largest mode is 640×496.
                val s = maxOf(1, minOf(info.size.width / 640, info.size.height / 496))
                d.setTargetSize(info.size.width / s, info.size.height / s)
                d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull()
    }
    val why = com.allnetworktools.data.sdr.TxPolicy.refuse(c.sendEmission())
    val mode = com.allnetworktools.data.sdr.Sstv.modes.first { it.name == c.sendMode }
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(S(R.string.sstv_send_title), style = rf(15, 20, 600))
            val preview = remember(c.photo, c.sendMode, com.allnetworktools.data.sdr.TxSettings.callsign) {
                c.photo?.let { b ->
                    val px = sstvPixels(b, mode.width, mode.height, com.allnetworktools.data.sdr.TxSettings.callsign)
                    android.graphics.Bitmap.createBitmap(IntArray(px.size) { px[it] or (0xFF shl 24) }, mode.width, mode.height, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
                }
            }
            if (preview != null) {
                androidx.compose.foundation.Image(preview, null, Modifier.fillMaxWidth().aspectRatio(mode.width.toFloat() / mode.height).clip(RoundedCornerShape(16.dp)))
            }
            ToolButtons(ToolButton(S(R.string.sstv_send_pick), Sym.PhotoCamera, BtnKind.Outline) {
                pick.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
            })
            Text(S(R.string.sstv_send_mode), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                com.allnetworktools.data.sdr.SstvEncoder.SendModes.forEach { n ->
                    val m = com.allnetworktools.data.sdr.Sstv.modes.first { it.name == n }
                    AntFilterChip("$n · ${Math.round(m.totalLines * m.lineMs / 1000)} s", c.sendMode == n, { c.sendMode = n })
                }
            }
            Text(S(R.string.frequency), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                c.sendChannels.forEach { ch -> AntFilterChip(ch.label, c.sendChannel == ch, { c.sendChannel = ch }) }
            }
            if (c.tx.transmitting) StartButton(S(R.string.stop), Sym.Stop) { c.tx.stop() }
            else StartButton(S(R.string.sstv_send_go), Sym.Antenna, enabled = why == null && c.photo != null) { c.sendPicture(device) }
            if (why != null) Text(why, style = rf(12, 16), color = cs.onSurfaceVariant)
            TxStatus(c.tx)
        }
    }
}
