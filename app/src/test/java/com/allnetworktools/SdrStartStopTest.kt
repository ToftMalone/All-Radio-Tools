package com.allnetworktools

import androidx.test.core.app.ApplicationProvider
import com.allnetworktools.data.sdr.SdrCaps
import com.allnetworktools.data.sdr.SdrDevice
import com.allnetworktools.data.sdr.SdrRadio
import com.allnetworktools.data.sdr.SdrRepository
import com.allnetworktools.ui.pages.sdr.DscController
import com.allnetworktools.ui.pages.sdr.SpectrumController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A tool stopped while its radio is still opening (Stop pressed during "Starting", or another tool taking over) must not
 * start receiving afterwards: two tools would then share the radio.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = FakeApp::class)
class SdrStartStopTest {
    private class SlowRadio : SdrRadio {
        override val caps = SdrCaps("slow", 0, 1, 1)
        @Volatile var receiving = false
        @Volatile var closed = false
        override fun setSampleRate(hz: Int, filterHz: Int) = true
        override fun setFrequency(hz: Long) = true
        override fun setLnaGain(db: Int) = true
        override fun setVgaGain(db: Int) = true
        override fun setAmp(on: Boolean) = true
        override fun setAntennaPower(on: Boolean) = true
        override fun startRx(onSamples: (ByteArray, Int) -> Unit, onError: (String) -> Unit) { receiving = true }
        override fun close() { receiving = false; closed = true }
    }

    private class SlowRepo(val radio: SlowRadio) : SdrRepository(ApplicationProvider.getApplicationContext()) {
        override fun hasPermission(d: SdrDevice) = true
        override fun open(d: SdrDevice): SdrRadio { Thread.sleep(300); return radio }
    }

    private fun waitWhile(cond: () -> Boolean) {
        val until = System.currentTimeMillis() + 3_000
        while (cond() && System.currentTimeMillis() < until) Thread.sleep(20)
    }

    @Test fun aReceiverStoppedWhileOpeningNeverStarts() {
        val radio = SlowRadio()
        val c = DscController(SlowRepo(radio), CoroutineScope(Dispatchers.Default))
        c.start(SdrDevice("slow", null))
        Thread.sleep(50)
        assertTrue(c.starting)
        c.stop()
        waitWhile { c.starting }
        Thread.sleep(100)
        assertFalse(c.running)
        assertFalse(radio.receiving)
        assertTrue(radio.closed)
    }

    @Test fun theSpectrumStoppedWhileOpeningNeverStarts() {
        val radio = SlowRadio()
        val c = SpectrumController(SlowRepo(radio), CoroutineScope(Dispatchers.Default))
        c.start(SdrDevice("slow", null))
        Thread.sleep(50)
        c.stop()
        waitWhile { c.starting }
        Thread.sleep(100)
        assertFalse(c.running)
        assertFalse(radio.receiving)
        assertTrue(radio.closed)
    }

    @Test fun aReceiverLeftAloneStarts() {
        val radio = SlowRadio()
        val c = DscController(SlowRepo(radio), CoroutineScope(Dispatchers.Default))
        c.start(SdrDevice("slow", null))
        waitWhile { c.starting }
        assertTrue(c.running)
        assertTrue(radio.receiving)
        c.stop()
        waitWhile { !radio.closed }
        assertTrue(radio.closed)
    }
}
