package com.nila.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {
    @Test
    fun pixelSelectsInstalledTextModelAndReportsMissingMedia() {
        val selected = ModelCatalog.selectAssistant(
            installed = setOf(ModelCatalog.JIMI_GEMMA_3.filename, ModelCatalog.GEMMA_4_E2B.filename),
            memory = DeviceMemory(totalBytes = 5_870_000_000, availableBytes = 2_300_000_000, lowMemory = false),
        )

        assertEquals(ModelCatalog.JIMI_GEMMA_3, selected)
        assertTrue(ModelCapability.TEXT in selected!!.capabilities)
        assertFalse(ModelCapability.AUDIO in selected.capabilities)
        assertEquals("Voice input needs an audio-capable on-device model. You can still type.", selected.missingReason(ModelCapability.AUDIO))
        assertEquals("Report image reading needs a vision-capable on-device model.", selected.missingReason(ModelCapability.VISION))
    }

    @Test
    fun capableDevicePrefersInstalledMultimodalModel() {
        val selected = ModelCatalog.selectAssistant(
            installed = setOf(ModelCatalog.JIMI_GEMMA_3.filename, ModelCatalog.GEMMA_4_E2B.filename),
            memory = DeviceMemory(totalBytes = 12L * GIB, availableBytes = 7L * GIB, lowMemory = false),
        )

        assertEquals(ModelCatalog.GEMMA_4_E2B, selected)
        assertTrue(ModelCapability.AUDIO in selected!!.capabilities)
        assertTrue(ModelCapability.VISION in selected.capabilities)
        assertNull(selected.missingReason(ModelCapability.AUDIO))
    }

    @Test
    fun incompatibleOrMissingModelsAreNotSelected() {
        val pixel = DeviceMemory(totalBytes = 5_870_000_000, availableBytes = 1_470_000_000, lowMemory = false)
        assertNull(ModelCatalog.selectAssistant(setOf(ModelCatalog.JIMI_GEMMA_3.filename), pixel))
        assertNull(ModelCatalog.selectAssistant(setOf(ModelCatalog.GEMMA_4_E2B.filename), pixel))
        assertNull(ModelCatalog.selectAssistant(emptySet(), pixel))
    }

    @Test
    fun routerStaysOnDemandWhenMeasuredMemoryIsTight() {
        assertEquals(
            RouterLoadPolicy.ON_DEMAND,
            ModelCatalog.routerPolicy(DeviceMemory(5_870_000_000, 1_470_000_000, lowMemory = false)),
        )
        assertEquals(
            RouterLoadPolicy.ON_DEMAND,
            ModelCatalog.routerPolicy(DeviceMemory(12L * GIB, 2L * GIB, lowMemory = true)),
        )
        assertEquals(
            RouterLoadPolicy.RESIDENT,
            ModelCatalog.routerPolicy(DeviceMemory(12L * GIB, 4L * GIB, lowMemory = false)),
        )
    }
}
