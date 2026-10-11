package com.nila.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NilaNavigationTest {
    @Test
    fun returnsThroughVisitedScreens() {
        val navigation = NilaNavigation(NilaScreen.Home)

        navigation.open(NilaScreen.Food)
        navigation.open(NilaScreen.PreparedOrder)

        assertEquals(NilaScreen.PreparedOrder, navigation.current)
        assertTrue(navigation.canGoBack)
        assertEquals(NilaScreen.Food, navigation.back())
        assertEquals(NilaScreen.Home, navigation.back())
        assertFalse(navigation.canGoBack)
    }

    @Test
    fun doesNotDuplicateCurrentScreen() {
        val navigation = NilaNavigation(NilaScreen.Home)

        navigation.open(NilaScreen.Home)

        assertFalse(navigation.canGoBack)
        assertEquals(NilaScreen.Home, navigation.current)
    }

    @Test
    fun selectingLauncherSpaceClearsNestedHistory() {
        val navigation = NilaNavigation(NilaScreen.Home)
        navigation.open(NilaScreen.Settings)

        assertEquals(NilaScreen.Apps, navigation.select(NilaScreen.Apps))
        assertFalse(navigation.canGoBack)
        assertEquals(NilaScreen.Apps, navigation.current)
    }

    @Test
    fun replacingTheTopScreenLeavesNothingBehindIt() {
        val navigation = NilaNavigation(NilaScreen.Home)
        navigation.open(NilaScreen.AppReview)
        assertEquals(NilaScreen.MiniApp, navigation.replace(NilaScreen.MiniApp))
        assertEquals(NilaScreen.Home, navigation.back())
    }
}
