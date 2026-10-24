package com.nila.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherSpacesTest {
    @Test
    fun appLaunchRequiresAnUnambiguousInstalledApp() {
        val apps = listOf("YouTube" to "youtube", "YouTube Music" to "music", "Maps" to "maps")
        assertEquals("Maps" to "maps", resolveAppByName("maps", apps))
        assertEquals(null, resolveAppByName("you", apps))
        assertEquals("YouTube Music" to "music", resolveAppByName("youtube music", apps))
    }

    @Test
    fun removesPinsForAppsNoLongerInstalled() {
        assertEquals(
            listOf("com.example.keep"),
            reconcileHomePins(
                savedPins = setOf("com.example.gone", "com.example.keep"),
                installedPackages = setOf("com.example.keep"),
                suggestedPackages = emptyList(),
            ),
        )
    }
}
