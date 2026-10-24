package com.nila.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlin.coroutines.resume

/** Where the phone is now, named the way an address line would name it. */
data class CurrentPlace(
    val area: String,
    val city: String,
    val pincode: String,
    val street: String,
    val lat: Double,
    val lng: Double,
) {
    val title: String get() = listOf(area, city).filter(String::isNotBlank).distinct().joinToString(", ")
}

internal object CurrentPlaces {
    private const val FIX_TIMEOUT_MS = 6_000L

    private const val USER_AGENT = "NILA-Launcher-Hackathon/0.1 (contact: team@nila.app)"
    private val http = OkHttpClient()

    fun allowed(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Null when location is off, not allowed, or OSM can't name the spot. */
    suspend fun locate(context: Context): CurrentPlace? {
        if (!allowed(context)) return null
        val fix = withTimeoutOrNull(FIX_TIMEOUT_MS) { currentFix(context) } ?: lastKnown(context) ?: return null
        return withContext(Dispatchers.IO) { reverse(fix.latitude, fix.longitude) }
    }

    @SuppressLint("MissingPermission") // allowed() is checked in locate().
    private suspend fun currentFix(context: Context): Location? {
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) } ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return manager.getLastKnownLocation(provider)
        return suspendCancellableCoroutine { cont ->
            manager.getCurrentLocation(provider, null, context.mainExecutor) { cont.resume(it) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(context: Context): Location? {
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        return manager.getProviders(true).mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    private fun reverse(lat: Double, lng: Double): CurrentPlace? = runCatching {
        val url = "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=18&addressdetails=1&lat=$lat&lon=$lng"
        http.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val address = JSONObject(resp.body?.string().orEmpty()).optJSONObject("address") ?: return null
            fun first(vararg keys: String) = keys.firstNotNullOfOrNull { address.optString(it).takeIf(String::isNotBlank) }.orEmpty()
            CurrentPlace(
                area = first("suburb", "neighbourhood", "quarter", "city_district", "village"),
                city = first("city", "town", "state_district", "county"),
                pincode = address.optString("postcode"),
                street = first("road", "residential", "amenity"),
                lat = lat,
                lng = lng,
            )
        }
    }.getOrNull()

    /**
     * How close an address line is to [here], for ranking the picker: pincode, then area, then city.
     * Never a match by itself; [matchAddress] decides whether two addresses are one place.
     */
    fun matchScore(line: String, here: CurrentPlace): Int {
        val text = line.lowercase()
        fun has(part: String) = part.length > 2 && text.contains(part.lowercase())
        return (if (has(here.pincode)) 4 else 0) + (if (has(here.area)) 2 else 0) + (if (has(here.city)) 1 else 0)
    }
}
