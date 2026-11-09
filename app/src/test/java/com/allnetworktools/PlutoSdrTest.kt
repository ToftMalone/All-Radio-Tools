package com.allnetworktools

import com.allnetworktools.data.sdr.IiodClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The iiod text protocol of the PlutoSDR driver against a scripted pipe that cuts its answers into odd pieces. */
class PlutoSdrTest {
    private class Pipe(val answers: List<ByteArray>) {
        val sent = StringBuilder()
        private var next = 0
        fun read(b: ByteArray, n: Int): Int {
            if (next >= answers.size) return -1
            val a = answers[next++]
            System.arraycopy(a, 0, b, 0, a.size)
            return a.size
        }
        fun write(b: ByteArray, n: Int): Int { sent.append(String(b, 0, n, Charsets.ISO_8859_1)); return n }
    }

    private fun bytes(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    @Test fun attributeWriteSendsTheHeaderAndTheValue() {
        val pipe = Pipe(listOf(bytes("10\n")))
        val c = IiodClient(pipe::read, pipe::write)
        assertTrue(c.writeAttr("iio:device1", "OUTPUT", "altvoltage0", "frequency", "2400000000"))
        assertEquals("WRITE iio:device1 OUTPUT altvoltage0 frequency 10\r\n2400000000", pipe.sent.toString())
    }

    @Test fun anErrorAnswerIsAFailure() {
        val pipe = Pipe(listOf(bytes("-22\n")))
        assertFalse(IiodClient(pipe::read, pipe::write).writeAttr("d", null, null, "a", "1"))
    }

    @Test fun theContextXmlListsTheDevices() {
        val xml = """<context><device id="iio:device1" name="ad9361-phy"></device><device id="iio:device4" name="cf-ad9361-lpc"></device></context>"""
        val pipe = Pipe(listOf(bytes("${xml.length}\n"), bytes(xml.substring(0, 30)), bytes(xml.substring(30) + "\n")))
        val got = IiodClient(pipe::read, pipe::write).print()
        assertEquals(xml, got)
        val d = IiodClient.devices(got!!)
        assertEquals("iio:device1", d["ad9361-phy"])
        assertEquals("iio:device4", d["cf-ad9361-lpc"])
    }

    @Test fun openSendsTheChannelMask() {
        val pipe = Pipe(listOf(bytes("0\n")))
        assertTrue(IiodClient(pipe::read, pipe::write).open("iio:device4", 32768, 3))
        assertEquals("OPEN iio:device4 32768 00000003\r\n", pipe.sent.toString())
    }

    @Test fun aBufferReadIsPutBackTogether() {
        val payload = ByteArray(16) { it.toByte() }
        // First answer: length, the mask line and a first half; then the rest of the first chunk, and a second chunk.
        val pipe = Pipe(listOf(
            bytes("8\n") + bytes("00000003\n") + payload.copyOfRange(0, 3),
            payload.copyOfRange(3, 8),
            bytes("8\n") + payload.copyOfRange(8, 16),
        ))
        val dst = ByteArray(16)
        assertTrue(IiodClient(pipe::read, pipe::write).readBuffer("iio:device4", 16, dst))
        assertEquals(payload.toList(), dst.toList())
        assertEquals("READBUF iio:device4 16\r\n", pipe.sent.toString())
    }

    @Test fun aLostPipeFailsTheRead() {
        val pipe = Pipe(emptyList())
        assertFalse(IiodClient(pipe::read, pipe::write).readBuffer("d", 8, ByteArray(8)))
    }
}
