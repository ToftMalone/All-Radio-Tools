package com.allnetworktools.ai

import com.allnetworktools.MainViewModel
import com.allnetworktools.R
import com.allnetworktools.data.CoverageMap
import com.allnetworktools.data.DriveTest
import com.allnetworktools.data.HandoverLog
import com.allnetworktools.model.Tool
import com.allnetworktools.ui.pages.wifi.CensorController
import com.allnetworktools.util.S

/**
 * What a tool has measured, as text, for Gemini to explain: the tool's own export (report or CSV) wherever it has
 * one, so the model reads exactly what the app shows. Read only when a Gemini button is tapped: nothing here starts a
 * measurement. Null for a tool with nothing to explain; an empty string when it has not measured anything yet.
 */
fun toolFacts(tool: Tool, vm: MainViewModel): (() -> String)? {
    val t = vm.tools
    return when (tool) {
        Tool.Ping -> { { if (t.ping.results.isEmpty()) "" else t.ping.report() } }
        Tool.CellPing -> { { if (t.cellPing.results.isEmpty()) "" else t.cellPing.report() } }
        Tool.Trace -> { { if (t.trace.hops.isEmpty()) "" else t.trace.report() } }
        Tool.CellTrace -> { { if (t.cellTrace.hops.isEmpty()) "" else t.cellTrace.report() } }
        Tool.Ports -> { { t.ports.report() } }
        Tool.Dns -> { { t.dns.report() } }
        Tool.CellDns -> { { t.cellDns.report() } }
        Tool.Speed -> { { if (t.speed.snap == com.allnetworktools.data.net.CfSnapshot.Empty) "" else t.speed.csv() } }
        Tool.CellSpeed -> { { if (t.cellSpeed.snap == com.allnetworktools.data.net.CfSnapshot.Empty) "" else t.cellSpeed.csv() } }
        Tool.Censor -> { { censor(t.censor) } }
        Tool.CellCensor -> { { censor(t.cellCensor) } }
        Tool.Audit -> { {
            t.audit.checks.orEmpty().joinToString("\n") { "[${it.level}] ${it.title}: ${it.detail}" }
                .let { if (it.isEmpty()) "" else "Network: ${t.audit.auditedSsid ?: "?"}\n$it" }
        } }
        Tool.Mitm -> { {
            val now = t.mitm.current
            buildString {
                now?.let { s ->
                    appendLine("Gateway ${s.gateway ?: "?"}, DHCP server ${s.dhcpServer ?: "?"}, DNS ${s.dns.joinToString()}, first hop ${s.firstHop ?: "?"}")
                    com.allnetworktools.data.MitmWatch.checks(s).forEach { appendLine("[${it.level}] ${it.title}: ${it.detail}") }
                }
                if (t.mitm.events.isNotEmpty()) appendLine("Changes seen (${t.mitm.rounds} checks):")
                t.mitm.events.take(30).forEach { appendLine("[${it.level}] ${it.text}") }
            }
        } }
        Tool.CellDetail -> { {
            val s = vm.cell.value.state
            s?.serving?.let { com.allnetworktools.ui.pages.cell.report(it, s) }.orEmpty()
        } }
        Tool.Handover -> { { HandoverLog.events.value.let { if (it.isEmpty()) "" else HandoverLog.csv(it) } } }
        Tool.DriveTest -> { { DriveTest.points.value.let { if (it.isEmpty()) "" else com.allnetworktools.data.DriveQualities.csv(it) } } }
        Tool.Coverage -> { { t.coverage.samples.toList().let { if (it.isEmpty()) "" else CoverageMap.csv(it) } } }
        Tool.Gatt -> { { t.gatt.report() } }
        Tool.UnknownTrackers -> { {
            t.unknownTrackers.candidates.values.sortedByDescending { it.assessment.level.ordinal }.take(20).joinToString("\n") { c ->
                val a = c.assessment
                "${c.title} (${c.kind.label}): ${a.level}, followed for ${a.durationMs / 60_000} min" +
                    (a.spreadM?.let { ", seen over ${it.toInt()} m" } ?: "") + ", heard ${c.sightings.size} times"
            }
        } }
        // SDR: the decoded messages, newest last.
        Tool.Acars -> { { t.acars.csv() } }
        Tool.Vdl2 -> { { t.vdl2.csv() } }
        Tool.Pocsag -> { { t.pocsag.csv() } }
        Tool.Tpms -> { { t.tpms.csv() } }
        Tool.Lpwan -> { { t.lpwan.csv() } }
        Tool.Meshtastic -> { { t.meshtastic.csv() } }
        Tool.MeshCore -> { { t.meshcore.csv() } }
        Tool.Sarsat -> { { t.sarsat.csv() } }
        Tool.Zwave -> { { t.zwave.csv() } }
        Tool.Zigbee -> { { t.zigbee.csv() } }
        Tool.AntPlus -> { { t.ant.csv() } }
        Tool.Dsc -> { { t.dsc.csv(S(R.string.dsc_kinds).split('|'), S(R.string.dsc_natures).split('|')) } }
        Tool.Ism -> { { t.ism.csv() } }
        else -> null
    }
}

private fun censor(c: CensorController): String = if (c.results.isEmpty()) "" else c.csv()

/** Room for the facts in Gemini Nano's small window (about 4 000 tokens, prompt and answer together). */
const val MAX_FACT_CHARS = 5_000

/**
 * [text] cut to [max] characters: the first line (a CSV header, a report's title) and the newest lines, which come
 * last in every export.
 */
fun clipFacts(text: String, max: Int = MAX_FACT_CHARS): String {
    if (text.length <= max) return text.trim()
    val lines = text.trim().lines()
    val head = lines.first()
    val tail = ArrayList<String>()
    var used = head.length + 40
    for (l in lines.drop(1).asReversed()) {
        if (used + l.length + 1 > max) break
        tail += l
        used += l.length + 1
    }
    return head + "\n(…older lines left out…)\n" + tail.asReversed().joinToString("\n")
}
