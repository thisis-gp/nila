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

        assertEquals(
            setOf(ModelCatalog.GEMMA_3_1B, ModelCatalog.GEMMA_4_E2B),
            choices.filter { it.installed }.map { it.model }.toSet(),
        )
        assertEquals(
            ModelCatalog.GEMMA_3_270M,
            choices.first { it.model == ModelCatalog.GEMMA_3_270M }.model,
        )
        assertFalse(choices.first { it.model == ModelCatalog.GEMMA_3_270M }.installed)
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

    @Test
    fun assistantLoadsCpuFirstOnSmallRamPhones() {
        // The Pixel 4a's GPU-first load hung without ever falling back (measured); 8 GB+ keeps GPU.
        assertFalse(ModelCatalog.assistantGpuFirst(DeviceMemory(6L * GIB, 2L * GIB, false)))
        assertTrue(ModelCatalog.assistantGpuFirst(DeviceMemory(12L * GIB, 4L * GIB, false)))
    }

    @Test
    fun theSmallestFittingAssistantIsTheSafeDefault() {
        // The 4a passes both static gates after a reboot but only the 270M class survives load (measured).
        val installed = setOf(ModelCatalog.GEMMA_3_270M.filename, ModelCatalog.GEMMA_3_1B.filename)
        val fourA = DeviceMemory(6L * GIB, (GIB + GIB / 2), false)
        assertEquals(ModelCatalog.GEMMA_3_270M, ModelCatalog.selectAssistant(installed, fourA))
        // Nothing fits when free memory is under the smallest gate.
        assertNull(ModelCatalog.selectAssistant(installed, DeviceMemory(6L * GIB, GIB / 2, false)))
    }

}
