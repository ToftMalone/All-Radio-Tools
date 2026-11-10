package com.allnetworktools.data.sdr

import com.allnetworktools.ui.pages.sdr.fitSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SdrSettingsTest {
    private class Fake : SdrRadio {
        override val caps = SdrCaps("fake", 0, 1, 1)
        val calls = ArrayList<String>()
        override fun setSampleRate(hz: Int, filterHz: Int) = true
        override fun setFrequency(hz: Long) = true
        override fun setLnaGain(db: Int) = true.also { calls += "lna $db" }
        override fun setVgaGain(db: Int) = true.also { calls += "vga $db" }
        override fun setAmp(on: Boolean) = true.also { calls += "amp $on" }
        override fun setAntennaPower(on: Boolean) = true.also { calls += "bias $on" }
        override fun setNativeGain(value: Int) = true.also { calls += "native $value" }
        override fun startRx(onSamples: (ByteArray, Int) -> Unit, onError: (String) -> Unit) {}
        override fun close() {}
    }

    @Test fun spectrumWidthsFollowTheRadio() {
        assertEquals(listOf(1, 2, 3), SdrKind.RtlSdr.spectrumSpans("Blog V4"))
        assertEquals(listOf(2, 10), SdrKind.Airspy.spectrumSpans("AIRSPY"))
        assertEquals(listOf(2, 3, 6), SdrKind.Airspy.spectrumSpans("AIRSPY MINI"))
        // 20 MHz asked of an RTL-SDR: its widest, 3 MHz. 5 asked of an Airspy R2: 2 (10 is wider than asked).
        assertEquals(3, fitSpan(20, SdrKind.RtlSdr.spectrumSpans(null)))
        assertEquals(2, fitSpan(5, SdrKind.Airspy.spectrumSpans(null)))
        assertEquals(10, fitSpan(20, SdrKind.Airspy.spectrumSpans(null)))
        assertEquals(1, fitSpan(0, SdrKind.RtlSdr.spectrumSpans(null)))
    }

    @Test fun aPickedNativeGainReplacesTheHackRfScale() {
        val f = Fake()
        val r = ManagedRadio(f, SdrKind.RtlSdr)
        SdrSettings.nativeAuto = false
        SdrSettings.nativeGain = 29
        r.setLnaGain(32); r.setVgaGain(20); r.setAmp(true)
        assertEquals(listOf("native 29", "native 29", "native 29"), f.calls)
        // Automatic: the tools' HackRF-scale values go through, the driver maps them.
        f.calls.clear()
        SdrSettings.nativeAuto = true
        r.setLnaGain(32)
        assertEquals(listOf("lna 32"), f.calls)
    }

    @Test fun theHackRfKeepsItsOwnGains() {
        val f = Fake()
        val r = ManagedRadio(f, SdrKind.HackRf)
        SdrSettings.nativeAuto = false
        r.setVgaGain(20)
        assertEquals(listOf("vga 20"), f.calls)
        SdrSettings.nativeAuto = true
    }

    @Test fun biasTeeAppliesToTheRadioInUse() {
        val f = Fake()
        SdrSettings.active = ManagedRadio(f, SdrKind.RtlSdr)
        SdrSettings.biasTee = true
        SdrSettings.applyBiasTee()
        SdrSettings.biasTee = false
        SdrSettings.applyBiasTee()
        assertEquals(listOf("bias true", "bias false"), f.calls)
        SdrSettings.active = null
        assertTrue(SdrKind.Pluto.biasTee.not())
    }
}
