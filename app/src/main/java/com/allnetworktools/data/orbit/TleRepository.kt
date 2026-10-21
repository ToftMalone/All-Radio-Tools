package com.allnetworktools.data.orbit

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.allnetworktools.R
import com.allnetworktools.util.S

/** Orbital elements of the GNSS satellites, with the time they were downloaded. */
data class TleSet(val sats: List<OrbitSat>, val fetchedAtMs: Long, val fromCache: Boolean) {
    /** Epoch (ms) of the oldest element set, to warn when the data gets stale. */
    val oldestEpochMs: Long get() = sats.minOfOrNull { ((it.sgp4.jdEpoch - 2440587.5) * 86_400_000.0).toLong() } ?: 0L
}

/**
 * Downloads the "gnss" group of CelesTrak (GP data published from the US Space Force catalogue) and
 * keeps the last copy in the cache directory so predictions still work offline.
 */
open class TleRepository(context: Context?) {
    private val file = context?.let { File(it.cacheDir, "gnss.tle") }

    open suspend fun load(forceRefresh: Boolean = false): TleSet = withContext(Dispatchers.IO) {
        val cached = file?.takeIf { it.exists() && it.length() > 0 }
        val fresh = cached != null && System.currentTimeMillis() - cached.lastModified() < MaxAgeMs
        if (cached != null && fresh && !forceRefresh) return@withContext parse(cached.readText(), cached.lastModified(), true)
        try {
            val text = download()
            file?.writeText(text)
            parse(text, System.currentTimeMillis(), false)
        } catch (e: Exception) {
            if (cached != null) parse(cached.readText(), cached.lastModified(), true) else throw e
        }
    }

    private fun download(): String {
        val c = URL(Url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "AllRadioTools")
        try {
            if (c.responseCode != 200) throw java.io.IOException(S(R.string.celestrak_answered, c.responseCode))
            val text = c.inputStream.bufferedReader().readText()
            if (Tle.parseAll(text).isEmpty()) throw java.io.IOException(S(R.string.unreadable_answer_from_celestrak))
            return text
        } finally {
            c.disconnect()
        }
    }

    companion object {
        const val Url = "https://celestrak.org/NORAD/elements/gp.php?GROUP=gnss&FORMAT=tle"
        /** CelesTrak refreshes GNSS elements a few times a day; asking more often is useless. */
        const val MaxAgeMs = 6 * 3_600_000L

        fun parse(text: String, at: Long, fromCache: Boolean) = TleSet(Tle.parseAll(text).map(OrbitSat::of), at, fromCache)
    }
}
