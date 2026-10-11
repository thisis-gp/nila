package com.nila.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SidePanelTest {
    @Test
    fun aFastFlingWinsOverDistance() {
        assertTrue(panelSettlesOpen(progress = 0.1f, velocity = 1500f))
        assertFalse(panelSettlesOpen(progress = 0.9f, velocity = -1500f))
    }

    @Test
    fun aSlowReleaseSettlesByHowFarItWasDragged() {
        assertTrue(panelSettlesOpen(progress = 0.5f, velocity = 0f))
        assertFalse(panelSettlesOpen(progress = 0.2f, velocity = 200f))
    }
}
