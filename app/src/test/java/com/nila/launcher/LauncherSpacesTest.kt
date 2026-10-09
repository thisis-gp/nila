package com.nila.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherSpacesTest {
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
