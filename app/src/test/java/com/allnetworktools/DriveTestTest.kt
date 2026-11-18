package com.allnetworktools

import com.allnetworktools.data.DrivePoint
import com.allnetworktools.data.DriveQualities
import com.allnetworktools.data.DriveQuality
import com.allnetworktools.data.RadioTech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveTestTest {
    private fun p(i: Int, level: Int?, tech: String = "4G", handover: Boolean = false, lat: Double = 48.85 + i * 0.001) = DrivePoint(
        i * 5_000L, lat, 2.35, 5f, 50f, "Orange", tech, if (level == null) null else RadioTech.LTE, "LTE 1234", "B20", 101, 6300,
        level, -10, 12, null, level, DriveQualities.of(if (level == null) null else RadioTech.LTE, level, level != null), handover,
    )

    @Test fun thresholdsFollowTheTechnology() {
        assertEquals(DriveQuality.Excellent, DriveQualities.of(RadioTech.LTE, -85, true))
        assertEquals(DriveQuality.Fair, DriveQualities.of(RadioTech.NR, -105, true))
        assertEquals(DriveQuality.Poor, DriveQualities.of(RadioTech.LTE, -118, true))
        assertEquals(DriveQuality.Good, DriveQualities.of(RadioTech.GSM, -80, true))
        assertEquals(DriveQuality.NoService, DriveQualities.of(RadioTech.LTE, -80, false))
    }

    @Test fun summaryWeighsByDistance() {
        // About 111 m between points: four segments, three good and one without service.
        val pts = listOf(p(0, -85), p(1, -86), p(2, -87, handover = true), p(3, null, tech = "—"), p(4, -90))
        val s = DriveQualities.summary(pts)
        assertEquals(0.444, s.distanceKm, 0.01)
        assertEquals(0.75, s.quality[DriveQuality.Excellent] ?: 0.0, 0.01)
        assertEquals(0.25, s.quality[DriveQuality.NoService] ?: 0.0, 0.01)
        assertEquals(1, s.handovers)
        assertEquals(-90, s.worst!!.levelDbm)
    }

    @Test fun aJumpIsNotDrivenDistance() {
        val s = DriveQualities.summary(listOf(p(0, -85), p(1, -85, lat = 49.5)))
        assertEquals(0.0, s.distanceKm, 1e-9)
    }

    @Test fun kmlDrawsOneLinePerQualityRunAndEscapes() {
        val pts = listOf(p(0, -85), p(1, -86), p(2, -112, handover = true), p(3, -113))
        val k = DriveQualities.kml(pts, "Trip <A&B>")
        assertEquals(2, Regex("<LineString>").findAll(k).count())
        assertTrue("Trip &lt;A&amp;B&gt;" in k)
        assertEquals(1, Regex("<Point>").findAll(k).count())
        assertTrue(k.trimEnd().endsWith("</kml>"))
    }

    @Test fun csvHasOneRowPerPoint() {
        val c = DriveQualities.csv(listOf(p(0, -85), p(1, -100)))
        assertEquals(3, c.trim().lines().size)
        assertTrue(c.lines()[1].contains("excellent"))
    }
}
