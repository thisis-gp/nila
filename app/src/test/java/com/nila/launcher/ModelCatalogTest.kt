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
    fun settingsOffersOnlyInstalledModelsThatFitAndHonorsSelection() {
        val installed = setOf(ModelCatalog.GEMMA_3_1B.filename, ModelCatalog.GEMMA_4_E2B.filename)
        val memory = DeviceMemory(12L * GIB, 5L * GIB, lowMemory = false)
        val choices = ModelCatalog.choices(installed, memory)

        assertTrue(choices.all { it.installed && it.fitsMemory })
        assertEquals(ModelCatalog.GEMMA_4_E2B, ModelCatalog.selectAssistant(installed, memory, ModelCatalog.GEMMA_4_E2B.id))
    }

    @Test
    fun taskFineTuneDoesNotAppearAsGeneralAssistantChoice() {
        val choices = ModelCatalog.choices(
            setOf("nila-assistant-jimi-gemma3-1b-v8-wi8-afp32.litertlm"),
            DeviceMemory(6L * GIB, 3L * GIB, lowMemory = false),
        )

        assertTrue(choices.none { it.installed })
        assertNull(ModelCatalog.selectAssistant(setOf("nila-assistant-jimi-gemma3-1b-v8-wi8-afp32.litertlm"), DeviceMemory(6L * GIB, 3L * GIB, false)))
    }

}
