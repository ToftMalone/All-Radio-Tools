package com.allnetworktools.data

/**
 * What a fake base station (IMSI catcher) tends to give away, among what Android lets an app see. None of these
 * is proof on its own: Android does not expose whether ciphering is on, which is the real tell.
 */
enum class CellAnomalyKind(val severity: Int) {
    /** MCC 001 or 999: test networks, which is what off-the-shelf fake base stations broadcast by default. */
    TestNetwork(3),
    /** The same cell (identity, PCI and channel) heard with another tracking/location area code. */
    TacMismatch(3),
    /** Dropped from 4G/5G to 2G on the spot: the classic way to reach a 2G-only fake cell. */
    Downgrade2G(2),
    /** The area code changed while the phone stood still. */
    TacChangedStill(2),
    /** No site of this operator offers this technology nearby, according to the ANFR. */
    NotInAnfr(2),
    /** A serving cell that announces no neighbours, while cells around did. */
    NoNeighbours(1),
    /** A cell never heard before, received unusually strong. */
    StrongNewCell(1),
    /** A French network code that belongs to none of the four operators. */
    UnknownOperator(1),
}

data class CellAnomaly(val kind: CellAnomalyKind, val timeMs: Long, val cell: String, val detail: String)

/** Pure detection logic, fed with every cell update; kept free of Android so it can be tested. */
class CellWatch {
    private data class CellId(val tech: RadioTech, val cellId: Long, val pci: Int?, val arfcn: Int?)

    private val tacOf = HashMap<CellId, Int>()
    private val known = HashSet<CellId>()
    private var lastTech: RadioTech? = null
    private var lastTac: Int? = null
    private var lastTacPos: Pair<Double, Double>? = null
    private var neighboursSeen = false
    private val lastRaised = HashMap<CellAnomalyKind, Long>()

    /** A serving cell heard for the first time, to check against the ANFR sites around (done by the caller, online). */
    data class NewCell(val operator: FrOperator, val generation: String, val label: String, val lat: Double, val lon: Double)

    var newCell: NewCell? = null
        private set

    fun reset() {
        tacOf.clear(); known.clear(); lastRaised.clear()
        lastTech = null; lastTac = null; lastTacPos = null; neighboursSeen = false
    }

    fun observe(s: CellState?, pos: Pair<Double, Double>?, now: Long): List<CellAnomaly> {
        val c = s?.serving ?: return emptyList()
        val out = ArrayList<CellAnomaly>()
        val label = label(c)
        fun raise(k: CellAnomalyKind, detail: String) {
            // Once per kind every 10 minutes: a steady condition should not flood the list.
            if (now - (lastRaised[k] ?: -1_000_000L) < 600_000L) return
            lastRaised[k] = now
            out += CellAnomaly(k, now, label, detail)
        }

        val mcc = c.mcc
        if (mcc == "001" || mcc == "999") raise(CellAnomalyKind.TestNetwork, "${c.mcc}-${c.mnc}")
        if (mcc == "208" && c.mnc != null && FrOperator.ofPlmn("208${c.mnc}") == null) raise(CellAnomalyKind.UnknownOperator, "208-${c.mnc}")

        val prev = lastTech
        if (c.tech == RadioTech.GSM && (prev == RadioTech.LTE || prev == RadioTech.NR) && !s.roaming) {
            raise(CellAnomalyKind.Downgrade2G, "${prev.short} → 2G")
        }
        lastTech = c.tech

        val id = c.cellId?.let { CellId(c.tech, it, c.pci, c.arfcn) }
        val tac = c.tac
        if (id != null && tac != null) {
            val before = tacOf[id]
            if (before != null && before != tac) raise(CellAnomalyKind.TacMismatch, "$before → $tac")
            tacOf[id] = tac
        }
        if (tac != null) {
            val lt = lastTac
            val lp = lastTacPos
            if (lt != null && lt != tac && pos != null && lp != null && TrackerDetect.distanceM(lp.first, lp.second, pos.first, pos.second) < 150) {
                raise(CellAnomalyKind.TacChangedStill, "$lt → $tac")
            }
            if (lt != tac || lp == null) { lastTac = tac; lastTacPos = pos }
        }

        if (s.neighbors.isNotEmpty()) neighboursSeen = true
        else if (neighboursSeen && c.tech != RadioTech.NR) raise(CellAnomalyKind.NoNeighbours, label)

        val fresh = id != null && known.add(id)
        val level = c.level
        if (fresh && level != null && known.size > 1 && level > strong(c.tech)) raise(CellAnomalyKind.StrongNewCell, "$level dBm")

        newCell = null
        if (fresh && pos != null && mcc == "208") {
            val op = FrOperator.ofPlmn("208${c.mnc}")
            val gen = when (c.tech) { RadioTech.GSM -> "2G"; RadioTech.WCDMA -> "3G"; RadioTech.LTE -> "4G"; RadioTech.NR -> "5G" }
            if (op != null) newCell = NewCell(op, gen, label, pos.first, pos.second)
        }
        return out
    }

    /** The ANFR answer for [newCell]: no site of that operator with that technology within a few km. */
    fun anfrMissing(cell: NewCell, now: Long): CellAnomaly? {
        if (now - (lastRaised[CellAnomalyKind.NotInAnfr] ?: -1_000_000L) < 600_000L) return null
        lastRaised[CellAnomalyKind.NotInAnfr] = now
        return CellAnomaly(CellAnomalyKind.NotInAnfr, now, cell.label, "${cell.operator.label} ${cell.generation}")
    }

    /** Above this, a cell is closer than any real mast usually is (dBm, RSRP for LTE/NR, RSSI for 2G/3G). */
    private fun strong(t: RadioTech) = when (t) { RadioTech.LTE, RadioTech.NR -> -55; else -> -45 }

    companion object {
        fun label(c: CellMeasure) = listOfNotNull(c.tech.short, c.mcc?.let { "$it-${c.mnc}" }, c.tac?.let { "TAC $it" }, c.cellId?.let { "ID $it" }).joinToString(" · ")
    }
}
