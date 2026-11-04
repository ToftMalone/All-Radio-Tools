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
        val p = c.current
        if (p != null) {
            Image(
                p.bitmap, p.image.mode, Modifier.fillMaxWidth().aspectRatio(p.image.width.toFloat() / p.image.height).clip(RoundedCornerShape(24.dp)),
                contentScale = ContentScale.FillBounds,
            )
        } else if (c.running) ToolEmpty(Sym.PhotoCamera, S(R.string.sstv_none_yet), S(R.string.sstv_none_yet_body), null)
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
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(S(R.string.frequency), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("145.800" to S(R.string.sstv_iss), "144.500" to S(R.string.sstv_2m), "433.400" to "70 cm").forEach { (f, l) ->
                        AntFilterChip("${f.replace('.', ',')} · $l", c.frequencyMhz == f, { if (c.frequencyMhz != f && !c.starting) { if (c.running) c.stop(); c.frequencyMhz = f } })
                    }
                }
                if (!c.running) HostInputField(c.frequencyMhz, { c.frequencyMhz = it }, S(R.string.frequency_mhz), Sym.Stream, keyboardType = KeyboardType.Decimal)
                GainSettings(c.gain) { c.applyGains() }
            }
        }
        SectionCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                Text(S(R.string.sstv_how_it_works), style = rf(13, 18), color = cs.onSurfaceVariant)
            }
        }
    }
}
