package com.nila.launcher

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
    suspend fun liveRouteCard(destinationQuery: String): NilaResponse.RouteCard? = withContext(Dispatchers.IO) {
        val destination = MapsClient.searchPlace(destinationQuery) ?: return@withContext null
        // No device-location permission wired up yet, so this proves the OSM
        // path with a fixed origin rather than claiming a real "from here" fix.
        val origin = MapsClient.searchPlace("Bengaluru, India") ?: return@withContext null
        val route = MapsClient.route(origin, destination) ?: return@withContext null
        NilaResponse.RouteCard(
            destination = destination.name,
            distanceKm = route.distanceKm,
            durationMin = route.durationMin,
            source = "LIVE OSM",
        )
    }
}
