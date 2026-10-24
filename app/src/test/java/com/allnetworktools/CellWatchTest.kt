package com.allnetworktools

import com.allnetworktools.data.CellAnomalyKind
import com.allnetworktools.data.CellMeasure
import com.allnetworktools.data.CellState
import com.allnetworktools.data.CellWatch
import com.allnetworktools.data.FrOperator
import com.allnetworktools.data.RadioTech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CellWatchTest {
    private fun cell(tech: RadioTech, id: Long, tac: Int, mcc: String = "208", mnc: String = "01", level: Int = -95, pci: Int = 10, arfcn: Int = 6300) = CellMeasure(
        tech = tech, registered = true, band = null, arfcnLabel = null, arfcn = arfcn, pci = pci, tac = tac, cellId = id, nodeId = null, sector = null,
        mcc = mcc, mnc = mnc, rsrp = if (tech == RadioTech.LTE || tech == RadioTech.NR) level else null, rsrq = null, sinr = null,
        rssi = if (tech == RadioTech.GSM) level else null, cqi = null, timingAdvance = null, bandwidthKhz = null, downlinkMhz = null,
    )

    private fun state(c: CellMeasure, neighbours: Int = 3, roaming: Boolean = false) = CellState(
        "Orange", 0, c.tech.short, c.tech.short, c.tech.short, roaming, true, c,
        List(neighbours) { c.copy(registered = false, cellId = 900L + it, pci = 100 + it) }, emptyList(), 3, true,
    )

    private val here = 48.85 to 2.35

    @Test fun quietNetworkRaisesNothing() {
        val w = CellWatch()
        var t = 0L
        repeat(5) { assertTrue(w.observe(state(cell(RadioTech.LTE, 1, 100)), here, t).isEmpty()); t += 3_000 }
        assertTrue(w.observe(state(cell(RadioTech.LTE, 2, 100)), 48.86 to 2.36, t).isEmpty())
    }

    @Test fun downgradeTo2gIsFlagged() {
        val w = CellWatch()
        w.observe(state(cell(RadioTech.LTE, 1, 100)), here, 0)
        val a = w.observe(state(cell(RadioTech.GSM, 5, 200, arfcn = 60)), here, 3_000)
        assertTrue(a.any { it.kind == CellAnomalyKind.Downgrade2G })
    }

    @Test fun roamingDowngradeIsNotFlagged() {
        val w = CellWatch()
        w.observe(state(cell(RadioTech.LTE, 1, 100)), here, 0)
        assertTrue(w.observe(state(cell(RadioTech.GSM, 5, 200, arfcn = 60), roaming = true), here, 3_000).none { it.kind == CellAnomalyKind.Downgrade2G })
    }

    @Test fun sameCellWithAnotherAreaCode() {
        val w = CellWatch()
        w.observe(state(cell(RadioTech.LTE, 1, 100)), here, 0)
        w.observe(state(cell(RadioTech.LTE, 2, 100)), here, 3_000)
        val a = w.observe(state(cell(RadioTech.LTE, 1, 777)), here, 6_000)
        assertTrue(a.any { it.kind == CellAnomalyKind.TacMismatch })
        assertTrue(a.any { it.kind == CellAnomalyKind.TacChangedStill })
    }

    @Test fun areaChangeWhileMovingIsNormal() {
        val w = CellWatch()
        w.observe(state(cell(RadioTech.LTE, 1, 100)), here, 0)
        assertTrue(w.observe(state(cell(RadioTech.LTE, 2, 101)), 48.90 to 2.40, 60_000).isEmpty())
    }

    @Test fun testNetworkAndUnknownOperator() {
        val w = CellWatch()
        assertTrue(w.observe(state(cell(RadioTech.GSM, 1, 1, mcc = "001", mnc = "01")), here, 0).any { it.kind == CellAnomalyKind.TestNetwork })
        assertTrue(CellWatch().observe(state(cell(RadioTech.LTE, 1, 1, mnc = "77")), here, 0).any { it.kind == CellAnomalyKind.UnknownOperator })
    }

    @Test fun lonelyAndStrongNewCell() {
        val w = CellWatch()
        w.observe(state(cell(RadioTech.LTE, 1, 100)), here, 0)
        val a = w.observe(state(cell(RadioTech.LTE, 9, 100, level = -48, pci = 300), neighbours = 0), here, 3_000)
        assertTrue(a.any { it.kind == CellAnomalyKind.NoNeighbours })
        assertTrue(a.any { it.kind == CellAnomalyKind.StrongNewCell })
    }

    @Test fun repeatedSignsAreRateLimited() {
        val w = CellWatch()
        w.observe(state(cell(RadioTech.LTE, 1, 100)), here, 0)
        assertEquals(1, w.observe(state(cell(RadioTech.GSM, 5, 200, arfcn = 60)), here, 1_000).count { it.kind == CellAnomalyKind.Downgrade2G })
        w.observe(state(cell(RadioTech.LTE, 1, 100)), here, 2_000)
        assertEquals(0, w.observe(state(cell(RadioTech.GSM, 5, 200, arfcn = 60)), here, 3_000).count { it.kind == CellAnomalyKind.Downgrade2G })
    }

    @Test fun newCellIsOfferedForTheAnfrCheck() {
        val w = CellWatch()
        w.observe(state(cell(RadioTech.LTE, 1, 100)), here, 0)
        val n = w.newCell
        assertNotNull(n)
        assertEquals(FrOperator.Orange, n!!.operator)
        assertEquals("4G", n.generation)
        assertEquals(CellAnomalyKind.NotInAnfr, w.anfrMissing(n, 1_000)!!.kind)
    }
}
