package com.nila.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class ScreenGuidanceTest {
    @Test fun parsesOnlyBoundedTargetsWithNormalizedCoordinates() {
        val targets = parseScreenTargets("""{"targets":[{"label":"Continue","x":0.25,"y":0.8}]}""")!!
        assertEquals(listOf(ScreenTarget("Continue", 0.25f, 0.8f)), targets)
        assertEquals(emptyList<ScreenTarget>(), parseScreenTargets("""{"targets":[]}"""))
    }

    @Test fun rejectsMalformedOrUnsafeTargets() {
        assertNull(parseScreenTargets("not json"))
        assertNull(parseScreenTargets("""{"targets":[{"label":"Continue","x":1.2,"y":0.5}]}"""))
        assertNull(parseScreenTargets("""{"targets":[{"label":"","x":0.2,"y":0.5}]}"""))
        val tooMany = (0..8).joinToString(",") { """{"label":"button","x":0.5,"y":0.5}""" }
        assertNull(parseScreenTargets("""{"targets":[$tooMany]}"""))
    }

    @Test fun mappingIsUsableOnlyForSamePackageAndVersion() {
        val mapping = ScreenMapping("com.example", 4, listOf(ScreenTarget("Continue", .5f, .5f)))
        assertNotNull(currentScreenMapping(mapping, "com.example", 4))
        assertNull(currentScreenMapping(mapping, "com.example", 5))
        assertNull(currentScreenMapping(mapping, "com.other", 4))
    }

    @Test fun screenVisionRequiresLoadedVisionModelAndOneGiBAvailable() {
        val vision = ModelCatalog.GEMMA_4_E2B
        val text = ModelCatalog.GEMMA_3_1B
        assertEquals(true, canRunScreenGuidance(vision, true, DeviceMemory(8 * GIB, GIB, false)))
        assertEquals(false, canRunScreenGuidance(text, true, DeviceMemory(8 * GIB, 2 * GIB, false)))
        assertEquals(false, canRunScreenGuidance(vision, false, DeviceMemory(8 * GIB, 2 * GIB, false)))
        assertEquals(false, canRunScreenGuidance(vision, true, DeviceMemory(6 * GIB, GIB - 1, false)))
        assertEquals(false, canRunScreenGuidance(vision, true, DeviceMemory(8 * GIB, 2 * GIB, true)))
    }
}
