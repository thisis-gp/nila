package com.nila.launcher

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenStreetMap-based place search and routing — the documented free, no-key
 * fallback (DECISIONS.md: "Optional free fallback: OpenStreetMap through
 * Nominatim ... and OSRM for routes") used instead of Google Maps Grounding
 * Lite, since no API key has been issued yet. Powers NILA's own generated
 * route card (distance/ETA shown in-app); opening the real Maps app stays a
 * separate, secondary action reserved for actual turn-by-turn navigation,
 * per AGENTS.md's "open the full experience only when immersion matters."
 * Nominatim's usage policy caps public requests at ~1/sec and requires a
 * descriptive User-Agent, which this client sets; results must be attributed
 * "© OpenStreetMap contributors" wherever shown.
 */
internal object MapsClient {
    private val http = OkHttpClient()
    private const val USER_AGENT = "NILA-Launcher-Hackathon/0.1 (contact: team@nila.app)"

    data class Place(val name: String, val address: String, val lat: Double, val lon: Double)
    data class Route(val summary: String, val distanceKm: Double, val durationMin: Double)

    /** How far around [near] a place search looks first, in degrees (about 55 km). */
    private const val NEARBY_DEGREES = 0.5

    /** Matches compared by importance before one is picked. */
    private const val CANDIDATES = 5

    /**
     * Returns null on failure — caller falls back to the Maps redirect only. With [near], places within about
     * 55 km are searched first, so "the airport" means the local one; the open search is the fallback.
     */
    fun searchPlace(query: String, near: Place? = null): Place? =
        near?.let { search(query, "&viewbox=${it.lon - NEARBY_DEGREES},${it.lat + NEARBY_DEGREES},${it.lon + NEARBY_DEGREES},${it.lat - NEARBY_DEGREES}&bounded=1") }
            ?: search(query, "")

    private fun search(query: String, area: String): Place? = runCatching {
        val url = "https://nominatim.openstreetmap.org/search" +
            "?q=${java.net.URLEncoder.encode(query, "UTF-8")}" +
            "&format=json&limit=$CANDIDATES$area"
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val results = JSONArray(resp.body?.string().orEmpty())
            if (results.length() == 0) return null
            // The best-known place wins: "the airport" near Ashokanagar first matched a hotel called
            // "The Belvedere - Airport District"; Nominatim's importance ranks Kempegowda far above it.
            val first = (0 until results.length()).map { results.getJSONObject(it) }.maxByOrNull { it.optDouble("importance", 0.0) }!!
            Place(
                name = first.optString("display_name").substringBefore(","),
                address = first.optString("display_name"),
                lat = first.optString("lat").toDouble(),
                lon = first.optString("lon").toDouble(),
            )
        }
    }.onFailure { Log.e("NilaMaps", "searchPlace failed", it) }.getOrNull()

    /** Driving route between two points via OSRM's public demo server. */
    fun route(from: Place, to: Place): Route? = runCatching {
        val url = "https://router.project-osrm.org/route/v1/driving/" +
            "${from.lon},${from.lat};${to.lon},${to.lat}?overview=false"
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val json = JSONObject(resp.body?.string().orEmpty())
            val route = json.optJSONArray("routes")?.optJSONObject(0) ?: return null
            Route(
                summary = "${from.name} to ${to.name}",
                distanceKm = route.optDouble("distance", 0.0) / 1000.0,
                durationMin = route.optDouble("duration", 0.0) / 60.0,
            )
        }
    }.onFailure { Log.e("NilaMaps", "route failed", it) }.getOrNull()
}
