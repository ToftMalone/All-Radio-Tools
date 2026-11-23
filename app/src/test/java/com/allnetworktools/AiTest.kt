package com.allnetworktools

import com.allnetworktools.ai.Prompts
import com.allnetworktools.ai.clipFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiTest {
    @Test fun shortFactsStayWhole() {
        assertEquals("a\nb", clipFacts("a\nb\n"))
    }

    @Test fun longFactsKeepTheHeaderAndTheNewestLines() {
        val csv = "time,text\n" + (1..2000).joinToString("\n") { "$it,message number $it" }
        val c = clipFacts(csv, 1000)
        assertTrue(c.length <= 1000)
        assertTrue(c.startsWith("time,text\n"))
        assertTrue("newest kept", c.endsWith("2000,message number 2000"))
        assertFalse("oldest dropped", c.contains("\n1,message number 1\n"))
    }

    @Test fun promptsCarryTheFactsTheToolAndTheRules() {
        val p = Prompts.explain("Ping", "seq=1 15 ms")
        assertTrue(p.contains("\"Ping\""))
        assertTrue(p.contains("seq=1 15 ms"))
        assertTrue("no invention", p.contains("Never invent"))
        val a = Prompts.assistant("Pourquoi ?", "", listOf("Ping", "Test de débit"))
        assertTrue(a.contains("Ping; Test de débit"))
        assertTrue(a.contains("(nothing measured yet)"))
    }
}
