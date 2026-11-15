package com.allnetworktools

import com.allnetworktools.util.Csv
import org.junit.Assert.assertEquals
import org.junit.Test

class CsvTest {
    @Test fun formulasBecomeText() {
        assertEquals("a,'=1+1,b\r\n", Csv.guard("a,=1+1,b\r\n"))
        assertEquals("'@SUM(A1),'+cmd|' /C calc'!A0,'-2+3\n", Csv.guard("@SUM(A1),+cmd|' /C calc'!A0,-2+3\n"))
        assertEquals("x,\"'=HYPERLINK(\"\"http://e\"\")\",y\r\n", Csv.guard("x,\"=HYPERLINK(\"\"http://e\"\")\",y\r\n"))
        assertEquals("'\tcmd\n", Csv.guard("\tcmd\n"))
    }

    @Test fun numbersAndPlainTextAreLeftAlone() {
        val s = "t,-12.5,+3,1e-3,-0.5,\"-7\",abc,a=b,\"x,=y\"\r\n"
        assertEquals(s, Csv.guard(s))
    }

    @Test fun aCellAfterAQuotedCommaIsStillChecked() {
        assertEquals("\"a,b\",'=1\n", Csv.guard("\"a,b\",=1\n"))
    }

    @Test fun accentsSurvive() {
        val s = "é,ü,日本\n"
        assertEquals(s, Csv.guard(s))
        assertEquals("'=é\n", Csv.guard("=é\n"))
    }
}
