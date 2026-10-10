package com.nila.launcher

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Live lookups behind tools FunctionGemma picks. NilaEngine stays pure/offline
 * (AGENTS.md); network calls happen here, each result labelled real or fixture.
 */
internal object NilaIntegrations {
    /**
     * "Answer when simple" — a route question gets NILA's own generated card first
     * (AGENTS.md); opening the real Maps app for turn-by-turn stays a separate button
     * on that card, reserved for when immersion matters. Null means the OSM lookup
     * failed, so the caller falls back to the plain Navigate card.
     */
    suspend fun liveRouteCard(context: Context, destinationQuery: String): NilaResponse.RouteCard? {
        // From where the phone is, and the place searched near it: "the airport" found Helsinki
        // (8823 km) when the search had no location and the origin was a fixed city centre.
        val here = CurrentPlaces.locate(context)
        return withContext(Dispatchers.IO) {
            val origin = here?.let { MapsClient.Place(it.title, it.title, it.lat, it.lng) }
                ?: MapsClient.searchPlace("Bengaluru, India")
                ?: return@withContext null
            val destination = MapsClient.searchPlace(destinationQuery, near = origin) ?: return@withContext null
            val route = MapsClient.route(origin, destination) ?: return@withContext null
            NilaResponse.RouteCard(
                destination = destination.name,
                distanceKm = route.distanceKm,
                durationMin = route.durationMin,
                source = if (here != null) "LIVE OSM · FROM ${here.area.ifBlank { here.city }.uppercase()}" else "LIVE OSM",
            )
        }
    }
}
