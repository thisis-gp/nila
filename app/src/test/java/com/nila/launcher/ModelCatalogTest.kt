package com.nila.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {
    @Test
    fun taskFineTuneIsNotSelectedAsTheGeneralAssistant() {
        val selected = ModelCatalog.selectAssistant(
            installed = setOf("nila-assistant-jimi-gemma3-1b-v8-wi8-afp32.litertlm"),
            memory = DeviceMemory(totalBytes = 5_870_000_000, availableBytes = 2_300_000_000, lowMemory = false),
        )

        assertNull(selected)
    }

    @Test
    fun capableDevicePrefersInstalledMultimodalModel() {
        val selected = ModelCatalog.selectAssistant(
            installed = setOf(ModelCatalog.GEMMA_4_E2B.filename),
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
        assertNull(ModelCatalog.selectAssistant(setOf("nila-assistant-jimi-gemma3-1b-v8-wi8-afp32.litertlm"), pixel))
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
