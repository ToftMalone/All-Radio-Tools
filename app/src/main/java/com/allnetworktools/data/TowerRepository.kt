package com.allnetworktools.data

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.allnetworktools.R
import com.allnetworktools.util.S

/** The four French mobile network operators, with their name in the ANFR database. */
enum class FrOperator(val label: String, val anfr: String, val mncs: Set<String>) {
    Orange("Orange", "ORANGE", setOf("01", "02")),
    Sfr("SFR", "SFR", setOf("09", "10", "11", "13")),
    Free("Free Mobile", "FREE MOBILE", setOf("15", "16")),
    Bouygues("Bouygues Telecom", "BOUYGUES TELECOM", setOf("20", "21", "88")),
    ;

    companion object {
        fun ofPlmn(plmn: String?): FrOperator? {
            if (plmn == null || !plmn.startsWith("208") || plmn.length < 5) return null
            val mnc = plmn.substring(3).padStart(2, '0').takeLast(2)
            return entries.firstOrNull { mnc in it.mncs }
        }
    }
}

/** Which operator's sites to show, and why. */
data class OperatorChoice(val operator: FrOperator, val plmn: String, val fromSim: Boolean)

/**
 * The subscription's operator (SIM) wins: a Free customer wants Free sites. When the SIM belongs to
 * an MVNO its own code is not an operator with sites, so the network it is registered on is used.
 */
fun chooseOperator(simPlmn: String?, networkPlmn: String?): OperatorChoice? =
    FrOperator.ofPlmn(simPlmn)?.let { OperatorChoice(it, simPlmn!!, true) }
        ?: FrOperator.ofPlmn(networkPlmn)?.let { OperatorChoice(it, networkPlmn!!, false) }

/** One radio system of a site, as declared to the ANFR. */
data class Emitter(
    val system: String,
    val generation: String,
    val status: String,
    val since: LocalDate?,
    val azimuths: List<Int>,
)

data class TowerSite(
    val supportId: Long,
    val lat: Double,
    val lon: Double,
    val heightM: Double?,
    val address: String,
    val stations: List<String>,
    val emitters: List<Emitter>,
    val updated: LocalDate?,
) {
    val generations: List<String> get() = emitters.map { it.generation }.distinct().sortedDescending()
    val azimuths: List<Int> get() = emitters.flatMap { it.azimuths }.distinct().sorted()
    val bestGeneration: String get() = generations.firstOrNull() ?: "?"
    val inService: Boolean get() = emitters.any { it.status == StatusOn || it.status == StatusTechOn }

    /** Great-circle distance in metres (haversine, mean Earth radius). */
    fun distanceTo(lat0: Double, lon0: Double): Double {
        val p1 = Math.toRadians(lat0)
        val p2 = Math.toRadians(lat)
        val dp = p2 - p1
        val dl = Math.toRadians(lon - lon0)
        val a = kotlin.math.sin(dp / 2).let { it * it } + kotlin.math.cos(p1) * kotlin.math.cos(p2) * kotlin.math.sin(dl / 2).let { it * it }
        return 2 * 6_371_008.8 * kotlin.math.asin(kotlin.math.sqrt(a))
    }

    /** Initial bearing from the observer to the site, degrees clockwise from north. */
    fun bearingFrom(lat0: Double, lon0: Double): Double {
        val p1 = Math.toRadians(lat0)
        val p2 = Math.toRadians(lat)
        val dl = Math.toRadians(lon - lon0)
        val y = kotlin.math.sin(dl) * kotlin.math.cos(p2)
        val x = kotlin.math.cos(p1) * kotlin.math.sin(p2) - kotlin.math.sin(p1) * kotlin.math.cos(p2) * kotlin.math.cos(dl)
        return (Math.toDegrees(kotlin.math.atan2(y, x)) + 360) % 360
    }

    companion object {
        const val StatusOn = "En service"
        const val StatusTechOn = "Techniquement opérationnel"
        const val StatusPlanned = "Projet approuvé"
    }
}

/**
 * A search around a point, or inside the visible map area when [box] (south, west, north, east) is
 * given; [lat]/[lon] then only order the results by distance.
 */
data class TowerQuery(val operator: FrOperator, val lat: Double, val lon: Double, val radiusM: Int, val box: DoubleArray? = null) {
    override fun equals(other: Any?) = other is TowerQuery && other.operator == operator && other.lat == lat && other.lon == lon &&
        other.radiusM == radiusM && other.box.contentEquals(box)

    override fun hashCode() = listOf(operator, lat, lon, radiusM, box?.toList()).hashCode()

    companion object {
        fun area(op: FrOperator, south: Double, west: Double, north: Double, east: Double, fromLat: Double, fromLon: Double) =
            TowerQuery(op, fromLat, fromLon, 0, doubleArrayOf(south, west, north, east))
    }
}

data class TowerResult(val query: TowerQuery, val sites: List<TowerSite>, val emitterCount: Int, val truncated: Boolean, val fetchedAtMs: Long) {
    val dataUpdated: LocalDate? get() = sites.mapNotNull { it.updated }.maxOrNull()
}

/**
 * Radio sites declared to the ANFR (Agence nationale des fréquences), from its open data
 * "Observatoire du déploiement 2G/3G/4G/5G" (data.anfr.fr, Licence Ouverte). Every record is one
 * emitter of one operator on one support, with the support's surveyed coordinates.
 */
open class TowerRepository {
    open suspend fun fetch(q: TowerQuery): TowerResult = withContext(Dispatchers.IO) {
        val url = Endpoint + "?dataset=observatoire_2g_3g_4g" +
            "&rows=$MaxRows" +
            "&refine.adm_lb_nom=" + URLEncoder.encode(q.operator.anfr, "UTF-8") +
            (q.box?.let { (s, w, n, e) -> "&geofilter.polygon=($s,$w),($s,$e),($n,$e),($n,$w)" } ?: "&geofilter.distance=${q.lat},${q.lon},${q.radiusM}") +
            "&fields=" + Fields.joinToString(",")
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "AllRadioTools")
        try {
            if (c.responseCode != 200) throw java.io.IOException(S(R.string.anfr_answered, c.responseCode))
            parse(q, c.inputStream.bufferedReader().readText(), System.currentTimeMillis())
        } finally {
            c.disconnect()
        }
    }

    companion object {
        const val Endpoint = "https://data.anfr.fr/d4c/api/records/1.0/search/"
        const val MaxRows = 10_000
        private val Fields = listOf(
            "sup_id", "sta_nm_anfr", "emr_lb_systeme", "generation", "statut", "emr_dt", "coordonnees", "list_azimut",
            "sup_nm_haut", "adr_lb_lieu", "adr_lb_add1", "adr_lb_add2", "adr_lb_add3", "adr_nm_cp", "date_maj", "adm_lb_nom",
        )

        private fun date(s: String?): LocalDate? = s?.takeIf { it.length >= 10 }?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }

        fun parse(q: TowerQuery, json: String, at: Long): TowerResult {
            val o = JSONObject(json)
            val nhits = o.optInt("nhits")
            val records = o.optJSONArray("records")
            class Row(val sup: Long, val f: JSONObject)
            val rows = buildList {
                for (i in 0 until (records?.length() ?: 0)) {
                    val f = records!!.getJSONObject(i).optJSONObject("fields") ?: continue
                    // The filter is also applied here, so a change in the API cannot leak another operator's sites.
                    if (f.optString("adm_lb_nom").isNotEmpty() && f.optString("adm_lb_nom") != q.operator.anfr) continue
                    if (!f.has("sup_id") || !f.has("coordonnees")) continue
                    add(Row(f.getLong("sup_id"), f))
                }
            }
            val sites = rows.groupBy { it.sup }.mapNotNull { (sup, list) ->
                val f0 = list.first().f
                val coords = f0.getString("coordonnees").split(',').mapNotNull { it.trim().toDoubleOrNull() }
                if (coords.size != 2) return@mapNotNull null
                val address = listOf("adr_lb_lieu", "adr_lb_add1", "adr_lb_add2", "adr_lb_add3")
                    .mapNotNull { k -> f0.optString(k).takeIf { it.isNotBlank() && it != "null" } }
                    .distinct().joinToString(", ")
                val cp = f0.optString("adr_nm_cp").takeIf { it.isNotBlank() && it != "null" }
                TowerSite(
                    supportId = sup,
                    lat = coords[0],
                    lon = coords[1],
                    heightM = f0.optString("sup_nm_haut").replace(',', '.').toDoubleOrNull(),
                    address = listOfNotNull(address.ifEmpty { null }, cp).joinToString(" · ").ifEmpty { S(R.string.no_address_given) },
                    stations = list.mapNotNull { it.f.optString("sta_nm_anfr").takeIf { s -> s.isNotBlank() } }.distinct(),
                    emitters = list.map { r ->
                        Emitter(
                            system = r.f.optString("emr_lb_systeme"),
                            generation = r.f.optString("generation"),
                            status = r.f.optString("statut"),
                            since = date(r.f.optString("emr_dt")),
                            azimuths = r.f.optString("list_azimut").split('|').mapNotNull { it.trim().toDoubleOrNull()?.toInt() },
                        )
                    }.sortedWith(compareByDescending<Emitter> { it.generation }.thenBy { it.system }),
                    updated = list.mapNotNull { date(it.f.optString("date_maj")) }.maxOrNull(),
                )
            }.sortedBy { it.distanceTo(q.lat, q.lon) }
            return TowerResult(q, sites, rows.size, nhits > rows.size, at)
        }
    }
}
